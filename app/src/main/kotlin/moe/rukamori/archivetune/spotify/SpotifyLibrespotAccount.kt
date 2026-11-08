package moe.rukamori.archivetune.spotify

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.constants.SpotifySpDcKey
import moe.rukamori.archivetune.constants.SpotifyStreamEnabledKey
import moe.rukamori.archivetune.shared.domain.source.SPOTIFY_OAUTH_ME_ENDPOINT
import moe.rukamori.archivetune.shared.domain.source.SPOTIFY_OAUTH_TOKEN_ENDPOINT
import moe.rukamori.archivetune.shared.domain.source.SpotifyOAuthAccountFacts
import moe.rukamori.archivetune.shared.domain.source.SpotifyPremiumPolicy
import moe.rukamori.archivetune.shared.domain.source.SpotifyProduct
import moe.rukamori.archivetune.shared.domain.source.SpotifySignInDecision
import moe.rukamori.archivetune.shared.domain.source.SpotifySignInRefusal
import moe.rukamori.archivetune.shared.domain.source.SpotifyStreamEngine
import moe.rukamori.archivetune.shared.domain.source.SpotifyStreamingPolicy
import moe.rukamori.archivetune.shared.domain.source.parseSpotifyOAuthMe
import moe.rukamori.archivetune.shared.domain.source.parseSpotifyOAuthTokenGrant
import moe.rukamori.archivetune.shared.domain.source.spotifyOAuthAuthorizeUrl
import moe.rukamori.archivetune.shared.domain.source.spotifyPkceChallenge
import moe.rukamori.archivetune.shared.domain.source.spotifyTokenExchangeBody
import moe.rukamori.archivetune.shared.domain.source.spotifyTokenRefreshBody
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.withAppProxy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/**
 * The `natune://spotify-callback` deep link the callback activity receives. The pending OAuth
 * session collects it here instead of each caller wiring the activity itself.
 */
object SpotifyLibrespotAuthCoordinator {
    val redirects =
        MutableSharedFlow<Uri>(
            replay = 1,
            extraBufferCapacity = 1,
        )

    fun emit(uri: Uri) {
        redirects.tryEmit(uri)
    }
}

data class SpotifyLibrespotAuthorizationSession(
    val state: String,
    val codeVerifier: String,
    val authorizationUri: Uri,
)

/**
 * The OAuth grant the Librespot engine streams with. It is the library account's own session — the
 * authorize step verifies the account equals the catalog sign-in and is Premium — so the engine
 * appears to Spotify as an ordinary Connect device the listener owns. The grant lives apart from
 * the catalog's sp_dc session and mints its own access tokens by refresh grant; the stored access
 * token is only a cache so the engine can start without waiting on the token endpoint.
 *
 * Token storage mirrors the dedicated streaming account's shape: the refresh token is the session,
 * sign-out drops every stored credential, and nothing here ever reads or writes the dedicated
 * `SpotifyStream*` account or the catalog's global `Spotify.accessToken`.
 */
object SpotifyLibrespotAccount {
    /** The registered redirect for the Android and iOS OAuth flow, a deep link into the callback activity. */
    const val REDIRECT_URI = "natune://spotify-callback"

    private const val TAG = "SpotifyLibrespotAccount"
    private const val TOKEN_EXPIRY_MARGIN_MS = 60_000L
    private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

    private val client =
        OkHttpClient
            .Builder()
            .withAppProxy()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    private val secureRandom = SecureRandom()

    // Account mutations share the same commit-boundary lock the dedicated streaming account uses.
    // The pending OAuth session lives under it too, so begin/cancel/complete cannot interleave.
    private val tokenMutex = StreamingAccountMutationLock()

    /** The in-flight authorization; only the newest one is valid, and completing consumes it. */
    @Volatile
    private var pendingSession: SpotifyLibrespotAuthorizationSession? = null

    /** The pending flow's CSRF state, so a redirect collector can tell the real callback from a stale one. */
    val pendingAuthorizationState: String?
        get() = pendingSession?.state

    // The catalog sp_dc whose account the grant was last verified against, and the verdict — checked
    // once per catalog session, the same cadence as the dedicated account's collision check.
    @Volatile
    private var comparedCatalogSpDc: String? = null

    @Volatile
    private var catalogMatchesLibrespot = false

    sealed class SignInError : Exception() {
        class NotConfigured : SignInError()

        class AuthorizationRejected(val reason: String?) : SignInError()

        class SessionLost : SignInError()

        class StateMismatch : SignInError()

        class ExchangeFailed : SignInError()

        class CatalogUnverifiable : SignInError()

        class DifferentFromCatalog : SignInError()

        class NotPremium : SignInError()

        class ProfileUnavailable : SignInError()
    }

    /** A build without the registered app id cannot start the flow; the sign-in reports not configured. */
    val isConfigured: Boolean
        get() = BuildConfig.ARCHIVETUNE_SPOTIFY_OAUTH_CLIENT_ID.isNotBlank()

    /** Starts an authorization-code PKCE flow. The URI is for the system browser, never a WebView. */
    suspend fun beginAuthorization(): SpotifyLibrespotAuthorizationSession? =
        tokenMutex.withLock {
            if (!isConfigured) return@withLock null
            val state = randomUrlSafeString(32)
            val verifier = randomUrlSafeString(64)
            val challenge = spotifyPkceChallenge(verifier)
            val uri =
                Uri.parse(
                    spotifyOAuthAuthorizeUrl(
                        clientId = BuildConfig.ARCHIVETUNE_SPOTIFY_OAUTH_CLIENT_ID,
                        redirectUri = REDIRECT_URI,
                        state = state,
                        challenge = challenge,
                    ),
                )
            SpotifyLibrespotAuthorizationSession(state, verifier, uri).also { pendingSession = it }
        }

    /** Drops an in-progress authorization; a redirect that lands later belongs to a cancelled attempt. */
    suspend fun cancelAuthorization() {
        tokenMutex.withLock { pendingSession = null }
    }

    /**
     * Completes the flow from a `natune://spotify-callback` redirect: state check, code exchange, then
     * the two account rules — the authorized account must be the catalog account and it must be Premium.
     * Nothing is stored unless every step succeeds, so a refused sign-in leaves no half-session.
     */
    suspend fun completeAuthorization(
        context: Context,
        redirect: Uri,
    ): Result<SpotifyOAuthAccountFacts> =
        withContext(Dispatchers.IO) {
            tokenMutex.withLock {
                runCatching {
                    if (redirect.scheme != "natune" || redirect.host != "spotify-callback") {
                        throw SignInError.ExchangeFailed()
                    }
                    redirect.getQueryParameter("error")?.let { error ->
                        pendingSession = null
                        throw SignInError.AuthorizationRejected(error)
                    }
                    val session = pendingSession ?: throw SignInError.SessionLost()
                    val code = redirect.getQueryParameter("code")?.takeIf { it.isNotBlank() }
                    if (redirect.getQueryParameter("state") != session.state || code == null) {
                        throw SignInError.StateMismatch()
                    }
                    pendingSession = null

                    val grant =
                        exchangeCode(code, session.codeVerifier) ?: throw SignInError.ExchangeFailed()
                    // The authorization-code grant always carries a refresh token; a missing one means
                    // the response is not the session this flow was started for.
                    val refreshToken =
                        grant.refreshToken?.takeIf { it.isNotBlank() }
                            ?: throw SignInError.ExchangeFailed()
                    val me = fetchMe(grant.accessToken) ?: throw SignInError.ProfileUnavailable()

                    val prefs = context.dataStore.data.first()
                    val catalogSignedIn = !prefs[SpotifySpDcKey].isNullOrBlank()
                    val catalogSpDc = prefs[SpotifySpDcKey].orEmpty()
                    val catalogUserId =
                        if (catalogSignedIn) {
                            SpotifyStreamingAccount.catalogToken(context)
                                ?.let(SpotifyStreamingAccount::fetchProfile)
                                ?.userId
                        } else {
                            null
                        }
                    val decision =
                        SpotifyStreamingPolicy.decideLibrespotSignIn(
                            catalogSignedIn = catalogSignedIn,
                            librespotUserId = me.userId,
                            product = me.product,
                            catalogUserId = { catalogUserId },
                        )
                    if (decision is SpotifySignInDecision.Refuse) {
                        throw decision.reason.toSignInError()
                    }

                    val now = System.currentTimeMillis()
                    context.dataStore.edit {
                        // The catalog session the decision compared against must not have been
                        // replaced in the meantime; if it was, abort the whole edit so nothing
                        // half-stores a grant bound to a vanished account.
                        if (it[SpotifySpDcKey].orEmpty() != catalogSpDc) {
                            throw SignInError.CatalogUnverifiable()
                        }
                        it[SpotifyLibrespotRefreshTokenKey] = refreshToken
                        it[SpotifyLibrespotAccessTokenKey] = grant.accessToken
                        it[SpotifyLibrespotAccessTokenExpiresAtKey] =
                            now + grant.expiresInSeconds * 1000L
                        it[SpotifyLibrespotUserIdKey] = me.userId
                        it[SpotifyLibrespotAccountNameKey] = me.displayName.orEmpty()
                        // The catalog cookie this grant was minted under is its binding: the grant
                        // dies with that cookie even if this sign-out lands late.
                        it[SpotifyLibrespotCatalogSpDcKey] = catalogSpDc
                        // The grant is the library account, so its product read refreshes the library
                        // account's own Premium observation.
                        me.product
                            ?.let { product -> it[SpotifyProductKey] = product }
                            ?: it.remove(SpotifyProductKey)
                        it[SpotifyProductCheckedAtKey] = now
                    }
                    me
                }.onFailure { if (it is CancellationException) throw it }
            }
        }

    /**
     * A valid access token for the librespot session — the exact credential `Credentials::with_access_token`
     * consumes when the engine binds the library account. The stored token is returned while fresh;
     * otherwise the refresh grant mints a new one and updates the stored session. Returns null when no
     * grant exists or the refresh is rejected, and the caller must then treat the session as signed out.
     */
    suspend fun accessToken(context: Context): String? =
        withContext(Dispatchers.IO) {
            tokenMutex.withLock {
                val prefs = context.dataStore.data.first()
                val catalogSpDc = prefs[SpotifySpDcKey].orEmpty()
                // The stored fingerprint is the catalog sp_dc the grant was minted under. The grant
                // dies with that cookie — a replaced catalog session invalidates it here, before
                // any cached or refreshed token can leave, even if the repository's own sign-out
                // has not landed yet. An unbound grant (catalog signed out at authorization) falls
                // back to the account-id check.
                val boundCatalogSpDc = prefs[SpotifyLibrespotCatalogSpDcKey].orEmpty()
                if (boundCatalogSpDc.isNotBlank() && boundCatalogSpDc != catalogSpDc) {
                    context.dataStore.edit { it.clearLibrespotSession() }
                    return@withLock null
                }
                if (boundCatalogSpDc.isBlank() && catalogSpDc.isNotBlank()) {
                    val librespotUserId = prefs[SpotifyLibrespotUserIdKey].orEmpty()
                    if (librespotUserId.isNotBlank()) {
                        if (comparedCatalogSpDc != catalogSpDc) {
                            val catalogUserId =
                                SpotifyStreamingAccount.catalogToken(context)
                                    ?.let(SpotifyStreamingAccount::fetchProfile)
                                    ?.userId
                            // Unverifiable denies the token for now without destroying the grant.
                            if (catalogUserId == null) return@withLock null
                            catalogMatchesLibrespot = catalogUserId == librespotUserId
                            comparedCatalogSpDc = catalogSpDc
                        }
                        if (!catalogMatchesLibrespot) {
                            context.dataStore.edit { it.clearLibrespotSession() }
                            return@withLock null
                        }
                        // Bind a grant created while the catalog was signed out as soon as the
                        // same-account comparison succeeds, including the cached-token path.
                        var catalogChanged = false
                        context.dataStore.edit {
                            if (it[SpotifySpDcKey].orEmpty() != catalogSpDc) {
                                it.clearLibrespotSession()
                                catalogChanged = true
                            } else {
                                it[SpotifyLibrespotCatalogSpDcKey] = catalogSpDc
                            }
                        }
                        if (catalogChanged) return@withLock null
                    }
                }

                val stored = prefs[SpotifyLibrespotAccessTokenKey].orEmpty()
                val expiresAt = prefs[SpotifyLibrespotAccessTokenExpiresAtKey] ?: 0L
                if (
                    stored.isNotBlank() &&
                    expiresAt - TOKEN_EXPIRY_MARGIN_MS > System.currentTimeMillis()
                ) {
                    return@withLock stored
                }
                val refresh = prefs[SpotifyLibrespotRefreshTokenKey].orEmpty()
                if (refresh.isBlank()) return@withLock null

                val grant = refreshAccessToken(refresh) ?: return@withLock null
                val now = System.currentTimeMillis()
                // The catalog cookie is re-read inside the commit edit: a session swapped
                // mid-refresh must not leave a token minted under the old binding, so the
                // grant goes with it instead.
                var catalogChanged = false
                context.dataStore.edit {
                    if (it[SpotifySpDcKey].orEmpty() != catalogSpDc) {
                        it.clearLibrespotSession()
                        catalogChanged = true
                    } else {
                        it[SpotifyLibrespotAccessTokenKey] = grant.accessToken
                        it[SpotifyLibrespotAccessTokenExpiresAtKey] =
                            now + grant.expiresInSeconds * 1000L
                        grant.refreshToken
                            ?.takeIf { it.isNotBlank() }
                            ?.let { rotated -> it[SpotifyLibrespotRefreshTokenKey] = rotated }
                        if (boundCatalogSpDc.isBlank() && catalogSpDc.isNotBlank()) {
                            it[SpotifyLibrespotCatalogSpDcKey] = catalogSpDc
                        }
                    }
                }
                if (catalogChanged) return@withLock null
                refreshLibraryProductIfDue(context, grant.accessToken, catalogSpDc)
                // One last binding read before the token leaves: a catalog session swapped during
                // the product recheck must not hand out a token minted under the old binding.
                if (context.dataStore.data.first()[SpotifySpDcKey].orEmpty() != catalogSpDc) {
                    context.dataStore.edit { it.clearLibrespotSession() }
                    return@withLock null
                }
                grant.accessToken
            }
        }

    /** Drops the stored grant; streaming turns off when the stored engine is the one losing its session. */
    suspend fun signOut(context: Context): Unit = tokenMutex.withLock {
        comparedCatalogSpDc = null
        catalogMatchesLibrespot = false
        context.dataStore.edit { it.clearLibrespotSession() }
        Unit
    }

    /**
     * The refresh-mint plan re-check, the same bounded read the dedicated account does: only when the
     * library product observation is stale does a minted token re-read `/v1/me` product. A failed read
     * keeps the stored product, so an outage cannot masquerade as a lost subscription.
     */
    private suspend fun refreshLibraryProductIfDue(
        context: Context,
        accessToken: String,
        catalogSpDc: String,
    ) {
        val prefs = context.dataStore.data.first()
        val now = System.currentTimeMillis()
        if (
            !SpotifyPremiumPolicy.recheckDue(
                SpotifyProduct.of(prefs[SpotifyProductKey]),
                prefs[SpotifyProductCheckedAtKey] ?: 0L,
                now,
                tokenJustMinted = true,
            )
        ) {
            return
        }
        val product = fetchMe(accessToken)?.product ?: return
        context.dataStore.edit {
            // The /v1/me read ran under the binding captured before the fetch; if the catalog
            // session changed since, its product would land on the new account — the grant goes
            // instead of a write meant for the old one.
            if (it[SpotifySpDcKey].orEmpty() != catalogSpDc) {
                it.clearLibrespotSession()
            } else {
                it[SpotifyProductKey] = product
                it[SpotifyProductCheckedAtKey] = now
            }
        }
    }

    private fun exchangeCode(code: String, codeVerifier: String) =
        postForm(
            spotifyTokenExchangeBody(
                clientId = BuildConfig.ARCHIVETUNE_SPOTIFY_OAUTH_CLIENT_ID,
                redirectUri = REDIRECT_URI,
                code = code,
                verifier = codeVerifier,
            ),
        )?.let(::parseSpotifyOAuthTokenGrant)

    private fun refreshAccessToken(refreshToken: String) =
        postForm(
            spotifyTokenRefreshBody(
                clientId = BuildConfig.ARCHIVETUNE_SPOTIFY_OAUTH_CLIENT_ID,
                refreshToken = refreshToken,
            ),
        )?.let(::parseSpotifyOAuthTokenGrant)

    private fun postForm(body: String): String? =
        runCatching {
            val request =
                Request
                    .Builder()
                    .url(SPOTIFY_OAUTH_TOKEN_ENDPOINT)
                    .header("Accept", "application/json")
                    .post(body.toRequestBody(FORM_MEDIA_TYPE))
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).w("Spotify OAuth token request failed with HTTP ${response.code}")
                    return@use null
                }
                response.body?.string()
            }
        }.getOrNull()

    /** The `/v1/me` read for the grant's own account: id for the same-account rule, product for Premium. */
    private fun fetchMe(accessToken: String): SpotifyOAuthAccountFacts? =
        runCatching {
            val request =
                Request
                    .Builder()
                    .url(SPOTIFY_OAUTH_ME_ENDPOINT)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/json")
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parseSpotifyOAuthMe(response.body?.string().orEmpty())
            }
        }.getOrNull()

    private fun SpotifySignInRefusal.toSignInError(): SignInError =
        when (this) {
            SpotifySignInRefusal.DIFFERENT_FROM_CATALOG -> SignInError.DifferentFromCatalog()
            SpotifySignInRefusal.NOT_PREMIUM -> SignInError.NotPremium()
            SpotifySignInRefusal.CATALOG_UNVERIFIABLE -> SignInError.CatalogUnverifiable()
            else -> SignInError.ProfileUnavailable()
        }

    private fun randomUrlSafeString(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
    }
}

/**
 * Removes the whole Librespot grant inside a caller's datastore edit, so a catalog credential
 * replacement and the grant's death commit atomically. Streaming turns off when the stored engine
 * is the one losing its session — the same rule the shared settings policy encodes.
 */
internal fun MutablePreferences.clearLibrespotSession() {
    remove(SpotifyLibrespotRefreshTokenKey)
    remove(SpotifyLibrespotAccessTokenKey)
    remove(SpotifyLibrespotAccessTokenExpiresAtKey)
    remove(SpotifyLibrespotUserIdKey)
    remove(SpotifyLibrespotAccountNameKey)
    remove(SpotifyLibrespotCatalogSpDcKey)
    if (SpotifyStreamEngine.fromPreference(this[SpotifyStreamEngineKey]) == SpotifyStreamEngine.LIBRESPOT) {
        this[SpotifyStreamEnabledKey] = false
    }
}

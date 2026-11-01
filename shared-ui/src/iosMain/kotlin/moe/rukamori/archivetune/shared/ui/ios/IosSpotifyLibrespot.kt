package moe.rukamori.archivetune.shared.ui.ios

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.data.platform.ios.IosProviderCredentials
import moe.rukamori.archivetune.shared.data.platform.ios.iosSecureRandomBytes
import moe.rukamori.archivetune.shared.domain.platform.BrowserAuthPort
import moe.rukamori.archivetune.shared.domain.platform.BrowserAuthRequest
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SpotifyLibrespotAccessTokenExpiresAtKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyLibrespotAccessTokenKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyLibrespotCatalogSpDcKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyLibrespotRefreshTokenKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyLibrespotUserIdKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyProductCheckedAtKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyProductKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifySpDcKey
import moe.rukamori.archivetune.shared.domain.settings.clearSpotifyLibrespotSession
import moe.rukamori.archivetune.shared.domain.settings.recordSpotifyLibraryProduct
import moe.rukamori.archivetune.shared.domain.settings.setSpotifyLibrespotSession
import moe.rukamori.archivetune.shared.domain.source.SPOTIFY_OAUTH_ME_ENDPOINT
import moe.rukamori.archivetune.shared.domain.source.SPOTIFY_OAUTH_TOKEN_ENDPOINT
import moe.rukamori.archivetune.shared.domain.source.SpotifyOAuthTokenGrant
import moe.rukamori.archivetune.shared.domain.source.SpotifyPremiumPolicy
import moe.rukamori.archivetune.shared.domain.source.SpotifyProduct
import moe.rukamori.archivetune.shared.domain.source.SpotifySignInDecision
import moe.rukamori.archivetune.shared.domain.source.SpotifySignInRefusal
import moe.rukamori.archivetune.shared.domain.source.SpotifyStreamingPolicy
import moe.rukamori.archivetune.shared.domain.source.parseSpotifyOAuthMe
import moe.rukamori.archivetune.shared.domain.source.parseSpotifyOAuthTokenGrant
import moe.rukamori.archivetune.shared.domain.source.spotifyOAuthAuthorizeUrl
import moe.rukamori.archivetune.shared.domain.source.spotifyPkceChallenge
import moe.rukamori.archivetune.shared.domain.source.spotifyTokenExchangeBody
import moe.rukamori.archivetune.shared.domain.source.spotifyTokenRefreshBody
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem

/** The way an iOS Librespot authorization attempt ends, mapped by the streaming owner to the refusal row. */
internal sealed class IosLibrespotSignInOutcome {
    data object SignedIn : IosLibrespotSignInOutcome()

    class Refused(val refusal: SpotifySignInRefusal) : IosLibrespotSignInOutcome()

    data object Cancelled : IosLibrespotSignInOutcome()

    data object BrowserUnavailable : IosLibrespotSignInOutcome()
}

/**
 * The iOS half of the Librespot engine's catalog-bound OAuth session. Authorization runs the shared
 * PKCE flow through the system authentication sheet ([BrowserAuthPort], ASWebAuthenticationSession
 * with an ephemeral session per attempt): the callback `natune://spotify-callback` returns the
 * authorization code inside the sheet, with the port's strict redirect and state match — no deep-link
 * intake touches the grant. [accessToken] mirrors the Android and desktop guards exactly: the stored
 * catalog-cookie fingerprint is checked before any cached or minted token leaves, the refresh commit
 * re-reads the cookie inside the same edit, the Premium recheck never writes an old account's
 * product, and a final binding read stands between the last write and the returned token. All
 * mutations run under [gate], the same commit-boundary discipline the other account owners keep.
 */
internal class IosSpotifyLibrespot(
    private val graph: IosSessionGraph,
    private val browserAuth: BrowserAuthPort,
    private val catalogUserId: suspend (PreferenceSnapshot) -> String?,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val gate = Mutex()

    // The catalog sp_dc whose account the grant was last verified against, and the verdict — checked
    // once per catalog session, like the dedicated account's collision check on every platform.
    @Volatile
    private var comparedCatalogSpDc: String? = null

    @Volatile
    private var catalogMatchesLibrespot = false

    val isConfigured: Boolean
        get() = IosProviderCredentials.spotifyOauthClientId.isNotBlank()

    /**
     * Runs the system authentication sheet, code exchange and the two account rules — the authorized
     * account must be the catalog account and it must be Premium. Nothing is stored unless every step
     * succeeds, so a refused sign-in leaves no half-session.
     */
    suspend fun authorize(): IosLibrespotSignInOutcome = gate.withLock {
        val clientId =
            IosProviderCredentials.spotifyOauthClientId.takeIf { it.isNotBlank() }
                ?: return@withLock IosLibrespotSignInOutcome.Refused(SpotifySignInRefusal.INVALID_SESSION)
        val state = randomUrlSafeString(32)
        val verifier = randomUrlSafeString(64)
        val authorizeUrl =
            spotifyOAuthAuthorizeUrl(
                clientId = clientId,
                redirectUri = REDIRECT_URI,
                state = state,
                challenge = spotifyPkceChallenge(verifier),
            )
        val callbackUri =
            try {
                browserAuth
                    .authorize(
                        BrowserAuthRequest(
                            provider = SPOTIFY_PROVIDER,
                            scope = CredentialScope.SPOTIFY_STREAMING,
                            authorizationUrl = authorizeUrl,
                            redirectUri = REDIRECT_URI,
                            state = state,
                        ),
                    ).callbackUri
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                // The port's request or redirect validation refused the callback.
                return@withLock IosLibrespotSignInOutcome.Refused(SpotifySignInRefusal.INVALID_SESSION)
            } catch (failure: IllegalStateException) {
                return@withLock when (failure.message) {
                    "Authentication sheet unavailable" -> IosLibrespotSignInOutcome.BrowserUnavailable
                    else -> IosLibrespotSignInOutcome.Cancelled
                }
            }
        val code =
            queryParameter(callbackUri, "code")?.takeIf { it.isNotBlank() }
                ?: return@withLock IosLibrespotSignInOutcome.Refused(SpotifySignInRefusal.INVALID_SESSION)
        val grant =
            exchangeCode(clientId, code, verifier)
                ?: return@withLock IosLibrespotSignInOutcome.Refused(SpotifySignInRefusal.INVALID_SESSION)
        // The authorization-code grant always carries a refresh token; a missing one means the
        // response is not the session this flow was started for.
        val refreshToken =
            grant.refreshToken?.takeIf { it.isNotBlank() }
                ?: return@withLock IosLibrespotSignInOutcome.Refused(SpotifySignInRefusal.INVALID_SESSION)
        val me =
            fetchMe(grant.accessToken)
                ?: return@withLock IosLibrespotSignInOutcome.Refused(SpotifySignInRefusal.PROFILE_UNAVAILABLE)
        val prefs = snapshot()
        val catalogSpDc = prefs[SpotifySpDcKey].orEmpty()
        val catalogSignedIn = catalogSpDc.isNotBlank()
        val catalogId = if (catalogSignedIn) catalogUserId(prefs) else null
        when (
            val decision =
                SpotifyStreamingPolicy.decideLibrespotSignIn(
                    catalogSignedIn = catalogSignedIn,
                    librespotUserId = me.userId,
                    product = me.product,
                    catalogUserId = { catalogId },
                )
        ) {
            is SpotifySignInDecision.Refuse -> IosLibrespotSignInOutcome.Refused(decision.reason)
            SpotifySignInDecision.Accept -> {
                val now = nowMs()
                var catalogChanged = false
                graph.settings.edit {
                    // The catalog session the decision compared against must not have been replaced
                    // in the meantime; a drift aborts the whole edit so nothing half-stores a grant
                    // bound to a vanished account.
                    if (this[SpotifySpDcKey].orEmpty() != catalogSpDc) {
                        catalogChanged = true
                    } else {
                        setSpotifyLibrespotSession(
                            refreshToken = refreshToken,
                            accessToken = grant.accessToken,
                            accessTokenExpiresAtMs = now + grant.expiresInSeconds * 1000L,
                            userId = me.userId,
                            accountName = me.displayName.orEmpty(),
                            catalogSpDc = catalogSpDc,
                        )
                        recordSpotifyLibraryProduct(me.product, now)
                    }
                }
                if (catalogChanged) {
                    IosLibrespotSignInOutcome.Refused(SpotifySignInRefusal.CATALOG_UNVERIFIABLE)
                } else {
                    IosLibrespotSignInOutcome.SignedIn
                }
            }
        }
    }

    /**
     * A valid access token for the librespot session — the credential the native engine binds with.
     * Null when no grant exists, the catalog session behind it was replaced, or the refresh was
     * rejected; the caller then treats the session as signed out.
     */
    suspend fun accessToken(): String? = gate.withLock {
        val prefs = snapshot()
        val catalogSpDc = prefs[SpotifySpDcKey].orEmpty()
        // The stored fingerprint is the catalog sp_dc the grant was minted under. The grant dies
        // with that cookie — a replaced catalog session invalidates it here, before any cached or
        // refreshed token can leave. An unbound grant (catalog signed out at authorization) falls
        // back to the account-id check.
        val boundCatalogSpDc = prefs[SpotifyLibrespotCatalogSpDcKey].orEmpty()
        if (boundCatalogSpDc.isNotBlank() && boundCatalogSpDc != catalogSpDc) {
            graph.settings.edit { clearSpotifyLibrespotSession() }
            return@withLock null
        }
        if (boundCatalogSpDc.isBlank() && catalogSpDc.isNotBlank()) {
            val librespotUserId = prefs[SpotifyLibrespotUserIdKey].orEmpty()
            if (librespotUserId.isNotBlank()) {
                if (comparedCatalogSpDc != catalogSpDc) {
                    val catalogId = catalogUserId(prefs) ?: return@withLock null
                    catalogMatchesLibrespot = catalogId == librespotUserId
                    comparedCatalogSpDc = catalogSpDc
                }
                if (!catalogMatchesLibrespot) {
                    graph.settings.edit { clearSpotifyLibrespotSession() }
                    return@withLock null
                }
            }
        }

        val stored = prefs[SpotifyLibrespotAccessTokenKey].orEmpty()
        val expiresAt = prefs[SpotifyLibrespotAccessTokenExpiresAtKey] ?: 0L
        if (stored.isNotBlank() && expiresAt - TOKEN_EXPIRY_MARGIN_MS > nowMs()) {
            return@withLock stored
        }
        val refresh = prefs[SpotifyLibrespotRefreshTokenKey].orEmpty()
        if (refresh.isBlank()) return@withLock null

        val grant = refreshAccessToken(refresh) ?: return@withLock null
        val now = nowMs()
        // The catalog cookie is re-read inside the commit edit: a session swapped mid-refresh must
        // not leave a token minted under the old binding, so the grant goes with it.
        var catalogChanged = false
        graph.settings.edit {
            if (this[SpotifySpDcKey].orEmpty() != catalogSpDc) {
                clearSpotifyLibrespotSession()
                catalogChanged = true
            } else {
                this[SpotifyLibrespotAccessTokenKey] = grant.accessToken
                this[SpotifyLibrespotAccessTokenExpiresAtKey] = now + grant.expiresInSeconds * 1000L
                grant.refreshToken
                    ?.takeIf { rotated -> rotated.isNotBlank() }
                    ?.let { rotated -> this[SpotifyLibrespotRefreshTokenKey] = rotated }
                if (boundCatalogSpDc.isBlank() && catalogSpDc.isNotBlank()) {
                    this[SpotifyLibrespotCatalogSpDcKey] = catalogSpDc
                }
            }
        }
        if (catalogChanged) return@withLock null
        refreshLibraryProductIfDue(grant.accessToken, catalogSpDc)
        // One last binding read before the token leaves: a catalog session swapped during the
        // product recheck must not hand out a token minted under the old binding.
        if (snapshot()[SpotifySpDcKey].orEmpty() != catalogSpDc) {
            graph.settings.edit { clearSpotifyLibrespotSession() }
            return@withLock null
        }
        grant.accessToken
    }

    /**
     * Cancels an in-flight sheet and drops the stored grant; the shared clear also turns streaming
     * off when Librespot is the stored engine.
     */
    suspend fun signOut(): Unit = gate.withLock {
        comparedCatalogSpDc = null
        catalogMatchesLibrespot = false
        browserAuth.clearProfile(SPOTIFY_PROVIDER, CredentialScope.SPOTIFY_STREAMING)
        graph.settings.edit { clearSpotifyLibrespotSession() }
        Unit
    }

    /**
     * The refresh-mint plan re-check, the same bounded read the Android slice runs: only when the
     * library product observation is stale does a minted token re-read the profile's product. The
     * catalog cookie is re-checked inside the edit, so a session swapped during the network read
     * never writes the old account's product onto the new one.
     */
    private suspend fun refreshLibraryProductIfDue(
        accessToken: String,
        catalogSpDc: String,
    ) {
        val prefs = snapshot()
        val now = nowMs()
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
        graph.settings.edit {
            if (this[SpotifySpDcKey].orEmpty() != catalogSpDc) {
                clearSpotifyLibrespotSession()
            } else {
                recordSpotifyLibraryProduct(product, now)
            }
        }
    }

    private fun snapshot(): PreferenceSnapshot = graph.settings.snapshots.value ?: PreferenceSnapshot.EMPTY

    /** The `/v1/me` read the OAuth token's `user-read-private` scope authorizes. */
    private suspend fun fetchMe(accessToken: String) =
        try {
            graph.http
                .get(SPOTIFY_OAUTH_ME_ENDPOINT) {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/json")
                }.takeIf { it.status.isSuccess() }
                ?.bodyAsText()
                ?.let(::parseSpotifyOAuthMe)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private suspend fun exchangeCode(
        clientId: String,
        code: String,
        verifier: String,
    ): SpotifyOAuthTokenGrant? =
        postForm(
            spotifyTokenExchangeBody(
                clientId = clientId,
                redirectUri = REDIRECT_URI,
                code = code,
                verifier = verifier,
            ),
        )?.let(::parseSpotifyOAuthTokenGrant)

    private suspend fun refreshAccessToken(refreshToken: String): SpotifyOAuthTokenGrant? =
        postForm(
            spotifyTokenRefreshBody(
                clientId = IosProviderCredentials.spotifyOauthClientId,
                refreshToken = refreshToken,
            ),
        )?.let(::parseSpotifyOAuthTokenGrant)

    private suspend fun postForm(body: String): String? =
        try {
            graph.http
                .post(SPOTIFY_OAUTH_TOKEN_ENDPOINT) {
                    header("Accept", "application/json")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(body)
                }.takeIf { it.status.isSuccess() }
                ?.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    /** One decoded query field of the callback URL, or null when it is absent. */
    private fun queryParameter(url: String, name: String): String? =
        NSURLComponents
            .componentsWithString(url)
            ?.queryItems
            ?.filterIsInstance<NSURLQueryItem>()
            ?.firstOrNull { it.name == name }
            ?.value

    @OptIn(ExperimentalEncodingApi::class)
    private fun randomUrlSafeString(byteCount: Int): String =
        Base64.UrlSafe.encode(iosSecureRandomBytes(byteCount)).trimEnd('=')

    private companion object {
        const val SPOTIFY_PROVIDER = "spotify"
        const val REDIRECT_URI = "natune://spotify-callback"
        const val TOKEN_EXPIRY_MARGIN_MS = 60_000L
    }
}

package moe.rukamori.archivetune.shared.ui.ios

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.rukamori.archivetune.shared.data.platform.ios.IosCookieOnlyBrowserAuth
import moe.rukamori.archivetune.shared.data.provider.KtorSpotifyAuth
import moe.rukamori.archivetune.shared.data.provider.KtorSpotifyStreamingAccount
import moe.rukamori.archivetune.shared.domain.platform.AuthorizedHttpsOrigin
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginOutcome
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginRequest
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SpotifyAccessTokenExpiresAtKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyAccessTokenKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifySpDcKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifySpKeyKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyStreamAccessTokenExpiresAtKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyStreamAccessTokenKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyStreamAccountNameKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyStreamSpDcKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyStreamSpKeyKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyStreamUserIdKey
import moe.rukamori.archivetune.shared.domain.source.SpotifySignInDecision
import moe.rukamori.archivetune.shared.domain.source.SpotifySignInRefusal
import moe.rukamori.archivetune.shared.domain.source.SpotifyStreamingPolicy
import moe.rukamori.archivetune.shared.ui.settings.preferredCookieValue

private const val SPOTIFY_PROVIDER = "spotify"
private const val TOKEN_EXPIRY_GRACE_MS = 60_000L
private const val SPOTIFY_ME_URL = "https://api.spotify.com/v1/me"

/** The session cookies are set for .spotify.com, so the web player origin carries them after the sign-in returns. */
private val PLAYER_ORIGIN = AuthorizedHttpsOrigin("open.spotify.com")

/*
 * The same official sign-in flow the catalog account uses, but the attempt is stored under its own
 * SPOTIFY_STREAMING cookie scope, so the two profiles never share a sheet.
 */
private val SPOTIFY_LOGIN_ORIGINS: Set<AuthorizedHttpsOrigin> = listOf(
    "accounts.spotify.com", "open.spotify.com", "www.spotify.com", "challenge.spotify.com", "www.google.com",
    "accounts.google.com", "appleid.apple.com", "www.facebook.com",
).map { AuthorizedHttpsOrigin(it) }.toSet()

/** A streaming account as Spotify's own REST profile reports it. */
private class IosSpotifyStreamingProfile(val userId: String, val displayName: String?)

/**
 * The iOS Spotify streaming account, kept apart from the catalog account exactly as on Android and the desktop
 * host. The sign-in runs the same cookie sheet under the separate [CredentialScope.SPOTIFY_STREAMING] profile, and
 * [SpotifyStreamingPolicy.decideSignIn] decides it: nothing is stored unless the cookie mints a signed-in token, the
 * profile is readable and the account is not the catalog account. The catalog token is read only for that comparison
 * and never written back; the catalog access token is never touched from here.
 */
internal class IosSpotifyStreamingAccount(
    private val graph: IosSessionGraph,
    private val browser: IosCookieOnlyBrowserAuth,
) {
    private val auth = KtorSpotifyAuth(graph.http)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var generation = 0L
    private var attempt: Job? = null

    /**
     * The stored streaming session's own token mint/persist path, shared with any resolver this host adds later. Its
     * collision check reads the catalog account only for the same-account comparison.
     */
    private val streamingSession = KtorSpotifyStreamingAccount(
        auth = auth,
        nowMs = { Clock.System.now().toEpochMilliseconds() },
        streamSpDc = { snapshot()[SpotifyStreamSpDcKey].orEmpty() },
        streamSpKey = { snapshot()[SpotifyStreamSpKeyKey].orEmpty() },
        cachedToken = {
            val prefs = snapshot()
            prefs[SpotifyStreamAccessTokenKey].orEmpty().takeIf { it.isNotBlank() }
                ?.let { it to (prefs[SpotifyStreamAccessTokenExpiresAtKey] ?: 0L) }
        },
        persistToken = { accessToken, expiresAtMs ->
            graph.settings.edit {
                this[SpotifyStreamAccessTokenKey] = accessToken
                this[SpotifyStreamAccessTokenExpiresAtKey] = expiresAtMs
            }
        },
        collidesWithCatalog = { spDc -> collidesWithCatalog(spDc) },
    )

    private val busyFlow = MutableStateFlow(false)
    val signInInProgress: StateFlow<Boolean> = busyFlow.asStateFlow()
    private val refusalFlow = MutableStateFlow<SpotifySignInRefusal?>(null)
    val refusal: StateFlow<SpotifySignInRefusal?> = refusalFlow.asStateFlow()

    private fun snapshot(): PreferenceSnapshot = graph.settings.snapshots.value ?: PreferenceSnapshot.EMPTY

    /** A fresh bearer token for the stored streaming session, or null while it stands down or is signed out. */
    suspend fun accessToken(): String? = streamingSession.accessToken()

    fun dismissRefusal() {
        refusalFlow.value = null
    }

    /** Opens the streaming sign-in sheet. The sign-in row is disabled while this runs. */
    fun signIn() {
        if (busyFlow.value) return
        refusalFlow.value = null
        busyFlow.value = true
        val attemptGeneration = ++generation
        attempt = graph.scope.launch {
            try {
                val refusal = signInAttempt(attemptGeneration)
                if (attemptGeneration == generation) refusalFlow.value = refusal
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("spotify streaming sign-in failed: ${failure::class.simpleName}")
                if (attemptGeneration == generation) refusalFlow.value = SpotifySignInRefusal.PROFILE_UNAVAILABLE
            } finally {
                busyFlow.value = false
            }
        }
    }

    /** Drops an in-progress sign-in; any result that still lands belongs to a cancelled attempt. */
    fun cancelSignIn() {
        generation += 1
        attempt?.cancel()
        busyFlow.value = false
    }

    /** The page commits the stored streaming session away after this; only an open sheet is left to close. */
    suspend fun signOut() {
        generation += 1
        attempt?.cancel()
        browser.clearProfile(SPOTIFY_PROVIDER, CredentialScope.SPOTIFY_STREAMING)
    }

    /**
     * Runs the cookie sheet under the streaming scope and applies the sign-in policy. Null means the session was
     * accepted and stored; a refusal means nothing was written.
     */
    private suspend fun signInAttempt(attemptGeneration: Long): SpotifySignInRefusal? {
        val context = when (
            val outcome = browser.login(
                CookieOnlyLoginRequest(
                    provider = SPOTIFY_PROVIDER,
                    scope = CredentialScope.SPOTIFY_STREAMING,
                    startUrl = KtorSpotifyAuth.LOGIN_URL,
                    authorizedOrigins = SPOTIFY_LOGIN_ORIGINS,
                    generation = attemptGeneration,
                )
            )
        ) {
            is CookieOnlyLoginOutcome.Completed -> outcome.context
            CookieOnlyLoginOutcome.Cancelled -> return null
            is CookieOnlyLoginOutcome.Failed -> return SpotifySignInRefusal.INVALID_SESSION
        }
        val cookies = try {
            context.cookiesFor(PLAYER_ORIGIN)
        } finally {
            context.close()
        }
        if (attemptGeneration != generation) return null
        val spDc = preferredCookieValue(cookies, "sp_dc")
        if (spDc.isBlank()) return SpotifySignInRefusal.INVALID_SESSION
        return verifyAndStore(spDc, preferredCookieValue(cookies, "sp_key"))
    }

    /** Applies [SpotifyStreamingPolicy.decideSignIn] and stores the session only when it accepts. */
    private suspend fun verifyAndStore(spDc: String, spKey: String): SpotifySignInRefusal? = lock.withLock {
        val prefs = snapshot()
        val catalogSpDc = prefs[SpotifySpDcKey].orEmpty()
        val sameCookie = catalogSpDc.isNotBlank() && catalogSpDc == spDc
        val token = if (sameCookie) null else {
            auth.fetchAccessToken(
                spDc = spDc,
                spKey = spKey,
                nowMs = { Clock.System.now().toEpochMilliseconds() },
            ).getOrNull()?.takeUnless { it.isAnonymous }
        }
        val profile = token?.let { fetchStreamingProfile(it.accessToken) }
        val catalogUserId = if (catalogSpDc.isNotBlank() && profile != null) {
            catalogTokenForComparison(prefs)?.let { fetchStreamingProfile(it)?.userId }
        } else {
            null
        }
        when (
            val decision = SpotifyStreamingPolicy.decideSignIn(
                catalogSpDc = catalogSpDc,
                candidateSpDc = spDc,
                tokenMinted = token != null,
                streamingUserId = profile?.userId,
                catalogUserId = { catalogUserId },
            )
        ) {
            is SpotifySignInDecision.Refuse -> decision.reason
            SpotifySignInDecision.Accept -> {
                val accepted = checkNotNull(token)
                val account = checkNotNull(profile)
                graph.settings.edit {
                    this[SpotifyStreamSpDcKey] = spDc
                    this[SpotifyStreamSpKeyKey] = spKey
                    this[SpotifyStreamAccessTokenKey] = accepted.accessToken
                    this[SpotifyStreamAccessTokenExpiresAtKey] = accepted.accessTokenExpirationTimestampMs
                    this[SpotifyStreamUserIdKey] = account.userId
                    this[SpotifyStreamAccountNameKey] = account.displayName.orEmpty()
                }
                null
            }
        }
    }

    /**
     * Whether the stored catalog sign-in is the streaming account. The catalog cookie is compared first, then the
     * account ids; the catalog token is minted only for this comparison and is never persisted.
     */
    private suspend fun collidesWithCatalog(streamingSpDc: String): Boolean {
        val prefs = snapshot()
        val catalogSpDc = prefs[SpotifySpDcKey].orEmpty()
        val streamingUserId = prefs[SpotifyStreamUserIdKey].orEmpty()
        val catalogUserId = if (catalogSpDc.isNotBlank() && catalogSpDc != streamingSpDc && streamingUserId.isNotBlank()) {
            catalogTokenForComparison(prefs)?.let { fetchStreamingProfile(it)?.userId }
        } else {
            null
        }
        return SpotifyStreamingPolicy.streamingStandsDown(
            catalogSpDc = catalogSpDc,
            streamingSpDc = streamingSpDc,
            streamingUserId = streamingUserId,
            catalogUserId = { catalogUserId },
        )
    }

    /*
     * The catalog token for the one comparison at streaming sign-in. A valid stored token is used as is; otherwise one
     * is minted from the catalog cookie and discarded, so the catalog session is never changed from here.
     */
    private suspend fun catalogTokenForComparison(prefs: PreferenceSnapshot): String? {
        val stored = prefs[SpotifyAccessTokenKey].orEmpty()
        if (stored.isNotBlank() &&
            (prefs[SpotifyAccessTokenExpiresAtKey] ?: 0L) - TOKEN_EXPIRY_GRACE_MS > Clock.System.now().toEpochMilliseconds()
        ) {
            return stored
        }
        val catalogSpDc = prefs[SpotifySpDcKey].orEmpty().ifBlank { return null }
        return auth.fetchAccessToken(
            spDc = catalogSpDc,
            spKey = prefs[SpotifySpKeyKey].orEmpty(),
            nowMs = { Clock.System.now().toEpochMilliseconds() },
        ).getOrNull()?.takeUnless { it.isAnonymous }?.accessToken
    }

    /**
     * The account behind [token] as the public REST profile reports it: the user id the same-account check needs and
     * the display name the settings page shows. Null when the profile cannot be read.
     */
    private suspend fun fetchStreamingProfile(token: String): IosSpotifyStreamingProfile? {
        val body = try {
            graph.http.get(SPOTIFY_ME_URL) {
                header("Authorization", "Bearer $token")
                header("Accept", "application/json")
            }.takeIf { it.status.isSuccess() }?.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        val profile = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val userId = profile.text("id")?.takeIf { it.isNotBlank() } ?: return null
        return IosSpotifyStreamingProfile(userId, profile.text("display_name")?.takeIf { it.isNotBlank() })
    }
}

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

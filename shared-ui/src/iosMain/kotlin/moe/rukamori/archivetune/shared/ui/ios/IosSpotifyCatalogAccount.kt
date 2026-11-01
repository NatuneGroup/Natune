package moe.rukamori.archivetune.shared.ui.ios

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import moe.rukamori.archivetune.shared.data.platform.ios.IosCookieOnlyBrowserAuth
import moe.rukamori.archivetune.shared.data.provider.KtorSpotifyAuth
import moe.rukamori.archivetune.shared.domain.platform.AuthorizedHttpsOrigin
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginFailure
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginOutcome
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginRequest
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.settings.InlineSpotifyProfileArtistsKey
import moe.rukamori.archivetune.shared.domain.settings.InlineSpotifyProfileCacheTsKey
import moe.rukamori.archivetune.shared.domain.settings.InlineSpotifyProfileRecentItemsKey
import moe.rukamori.archivetune.shared.domain.settings.InlineSpotifyProfileTopTracksKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceEditor
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SpotifyAccessTokenExpiresAtKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyAccessTokenKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyAccountAvatarUrlKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyAccountNameKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyLibraryPlaylistsCacheKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyProductCheckedAtKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyProductKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyRecentlyPlayedCacheFetchedAtKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyRecentlyPlayedCacheKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifySpDcKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifySpKeyKey
import moe.rukamori.archivetune.shared.domain.settings.clearSpotifyLibrespotSession
import moe.rukamori.archivetune.shared.ui.settings.SpotifyCatalogAccountState
import moe.rukamori.archivetune.shared.ui.settings.preferredCookieValue

private const val SPOTIFY_PROVIDER = "spotify"
private const val TOKEN_EXPIRY_GRACE_MS = 60_000L
private const val SPOTIFY_ME_URL = "https://api.spotify.com/v1/me"
private const val SPOTIFY_MY_PLAYLISTS_URL = "https://api.spotify.com/v1/me/playlists"

/** The session cookies are set for .spotify.com, so the web player origin carries them after the sign-in returns. */
private val PLAYER_ORIGIN = AuthorizedHttpsOrigin("open.spotify.com")

/*
 * The Spotify sign-in, its challenge page and the third-party sign-in options it offers. The native sheet refuses
 * every other navigation, so this list is the whole official flow.
 */
private val SPOTIFY_LOGIN_ORIGINS: Set<AuthorizedHttpsOrigin> = listOf(
    "accounts.spotify.com", "open.spotify.com", "www.spotify.com", "challenge.spotify.com", "www.google.com",
    "accounts.google.com", "appleid.apple.com", "www.facebook.com",
).map { AuthorizedHttpsOrigin(it) }.toSet()

/** Why a catalog sign-in or reload failed, mapped to a lane string where the page is drawn. */
internal enum class IosSpotifyCatalogError { SIGN_IN_FAILED, BROWSER_UNAVAILABLE }

/**
 * The iOS Spotify catalog account: the same cookie-window sign-in the desktop host runs, over the in-memory
 * WKWebView sheet of [IosCookieOnlyBrowserAuth]. The sp_dc cookie mints a web-player token through
 * [KtorSpotifyAuth], and the account name, avatar and playlist count come from Spotify's own REST profile, so the
 * row never names an account Spotify has not confirmed. Streaming sign-in is the separate account of
 * [IosSpotifyStreamingAccount]; it shares this sheet shape but not its cookie scope or settings keys.
 */
internal class IosSpotifyCatalogAccount(
    private val graph: IosSessionGraph,
    private val browser: IosCookieOnlyBrowserAuth,
) {
    private val auth = KtorSpotifyAuth(graph.http)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var generation = 0L

    private val stateFlow = MutableStateFlow(SpotifyCatalogAccountState())
    val state: StateFlow<SpotifyCatalogAccountState> = stateFlow.asStateFlow()
    private val errorFlow = MutableStateFlow<IosSpotifyCatalogError?>(null)
    val error: StateFlow<IosSpotifyCatalogError?> = errorFlow.asStateFlow()

    private fun snapshot(): PreferenceSnapshot = graph.settings.snapshots.value ?: PreferenceSnapshot.EMPTY

    fun dismissError() {
        errorFlow.value = null
    }

    /**
     * Restores the stored catalog session when the integration page opens: a stored token that is still valid is
     * reused, otherwise a fresh one is minted from the cookie. A session that cannot mint a token reads as signed
     * out, as on the other hosts.
     */
    suspend fun restore() {
        if (stateFlow.value.isLoading) return
        if (snapshot()[SpotifySpDcKey].isNullOrBlank()) {
            stateFlow.value = SpotifyCatalogAccountState()
            return
        }
        stateFlow.value = SpotifyCatalogAccountState(isAuthenticated = true, isLoading = true)
        val name = try {
            lock.withLock {
                refreshTokenLocked()
                refreshProfileLocked()
            }
            snapshot()[SpotifyAccountNameKey].orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("spotify catalog session not restored: ${failure::class.simpleName}")
            stateFlow.value = SpotifyCatalogAccountState()
            return
        }
        stateFlow.value = SpotifyCatalogAccountState(
            isAuthenticated = true,
            accountName = name,
            playlistCount = fetchPlaylistCount() ?: 0,
        )
    }

    /** Opens the catalog sign-in sheet. The connect row is disabled while this runs. */
    fun connect() {
        if (stateFlow.value.isLoading) return
        errorFlow.value = null
        stateFlow.value = stateFlow.value.copy(isLoading = true)
        val attempt = ++generation
        graph.scope.launch {
            try {
                signIn(attempt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("spotify catalog sign-in failed: ${failure::class.simpleName}")
                if (attempt == generation) {
                    errorFlow.value = IosSpotifyCatalogError.SIGN_IN_FAILED
                    stateFlow.value = SpotifyCatalogAccountState()
                }
            }
        }
    }

    private suspend fun signIn(attempt: Long) {
        val context = when (
            val outcome = browser.login(
                CookieOnlyLoginRequest(
                    provider = SPOTIFY_PROVIDER,
                    scope = CredentialScope.SPOTIFY_CATALOG,
                    startUrl = KtorSpotifyAuth.LOGIN_URL,
                    authorizedOrigins = SPOTIFY_LOGIN_ORIGINS,
                    generation = attempt,
                )
            )
        ) {
            is CookieOnlyLoginOutcome.Completed -> outcome.context
            CookieOnlyLoginOutcome.Cancelled -> {
                if (attempt == generation) stateFlow.value = stateFlow.value.copy(isLoading = false)
                return
            }
            is CookieOnlyLoginOutcome.Failed -> {
                if (attempt == generation) {
                    errorFlow.value = when (outcome.failure) {
                        CookieOnlyLoginFailure.BROWSER_UNAVAILABLE -> IosSpotifyCatalogError.BROWSER_UNAVAILABLE
                        else -> IosSpotifyCatalogError.SIGN_IN_FAILED
                    }
                    stateFlow.value = stateFlow.value.copy(isLoading = false)
                }
                return
            }
        }
        val cookies = try {
            context.cookiesFor(PLAYER_ORIGIN)
        } finally {
            context.close()
        }
        if (attempt != generation) return
        val spDc = preferredCookieValue(cookies, "sp_dc")
        if (spDc.isBlank()) {
            errorFlow.value = IosSpotifyCatalogError.SIGN_IN_FAILED
            stateFlow.value = SpotifyCatalogAccountState()
            return
        }
        connectWithCookies(spDc, preferredCookieValue(cookies, "sp_key"))
    }

    /** Commits the cookies, then proves the session by minting a token and reading the profile before it shows. */
    private suspend fun connectWithCookies(spDc: String, spKey: String) {
        lock.withLock {
            val prefs = snapshot()
            val changed = prefs[SpotifySpDcKey] != spDc || prefs[SpotifySpKeyKey].orEmpty() != spKey
            graph.settings.edit {
                this[SpotifySpDcKey] = spDc
                this[SpotifySpKeyKey] = spKey.ifBlank { null }
                this[SpotifyLibraryPlaylistsCacheKey] = null
                if (changed) {
                    this[SpotifyAccessTokenKey] = null
                    this[SpotifyAccessTokenExpiresAtKey] = null
                    clearCatalogSnapshots()
                }
            }
            refreshTokenLocked()
            refreshProfileLocked()
        }
        stateFlow.value = SpotifyCatalogAccountState(
            isAuthenticated = true,
            accountName = snapshot()[SpotifyAccountNameKey].orEmpty(),
            playlistCount = fetchPlaylistCount() ?: 0,
        )
    }

    /** Reloads the playlist count on demand; failures surface as the page's error, never as a fake count. */
    suspend fun reload() {
        if (stateFlow.value.isLoading || !stateFlow.value.isAuthenticated) return
        stateFlow.value = stateFlow.value.copy(isLoading = true)
        try {
            val count = lock.withLock {
                refreshTokenLocked()
                refreshProfileLocked()
                fetchPlaylistCount()
            }
            stateFlow.value = SpotifyCatalogAccountState(
                isAuthenticated = true,
                accountName = snapshot()[SpotifyAccountNameKey].orEmpty(),
                playlistCount = count ?: 0,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("spotify playlists not reloaded: ${failure::class.simpleName}")
            errorFlow.value = IosSpotifyCatalogError.SIGN_IN_FAILED
            stateFlow.value = stateFlow.value.copy(isLoading = false)
        }
    }

    suspend fun logOut() {
        generation += 1
        lock.withLock {
            graph.settings.edit {
                this[SpotifySpDcKey] = null
                this[SpotifySpKeyKey] = null
                this[SpotifyAccessTokenKey] = null
                this[SpotifyAccessTokenExpiresAtKey] = null
                this[SpotifyAccountNameKey] = null
                this[SpotifyAccountAvatarUrlKey] = null
                this[SpotifyProductKey] = null
                this[SpotifyProductCheckedAtKey] = null
                this[SpotifyLibraryPlaylistsCacheKey] = null
                clearCatalogSnapshots()
                clearSpotifyLibrespotSession()
            }
        }
        browser.clearProfile(SPOTIFY_PROVIDER, CredentialScope.SPOTIFY_CATALOG)
        errorFlow.value = null
        stateFlow.value = SpotifyCatalogAccountState()
    }

    /** Reuses a stored token that is still valid; otherwise mints one from the cookie and persists it. */
    private suspend fun refreshTokenLocked() {
        val prefs = snapshot()
        val spDc = prefs[SpotifySpDcKey].orEmpty()
        check(spDc.isNotBlank()) { "spotify not connected" }
        val stored = prefs[SpotifyAccessTokenKey].orEmpty()
        val expiresAt = prefs[SpotifyAccessTokenExpiresAtKey] ?: 0L
        if (stored.isNotBlank() && expiresAt > Clock.System.now().toEpochMilliseconds() + TOKEN_EXPIRY_GRACE_MS) return
        val token = auth.fetchAccessToken(
            spDc = spDc,
            spKey = prefs[SpotifySpKeyKey].orEmpty(),
            nowMs = { Clock.System.now().toEpochMilliseconds() },
        ).getOrThrow()
        graph.settings.edit {
            this[SpotifyAccessTokenKey] = token.accessToken
            this[SpotifyAccessTokenExpiresAtKey] = token.accessTokenExpirationTimestampMs
        }
    }

    /** The account name and avatar as Spotify reports them; a failed read keeps the previously stored values. */
    private suspend fun refreshProfileLocked() {
        val me = spotifyGet(SPOTIFY_ME_URL) ?: return
        graph.settings.edit {
            this[SpotifyAccountNameKey] = me.text("display_name").orEmpty()
            this[SpotifyAccountAvatarUrlKey] =
                (me["images"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.text("url") }
            this[SpotifyProductKey] = me.text("product")?.takeIf { it.isNotBlank() }
            this[SpotifyProductCheckedAtKey] = Clock.System.now().toEpochMilliseconds()
        }
    }

    /** One playlist page read; only its `total` is used, so the count is the service's own number. */
    private suspend fun fetchPlaylistCount(): Int? =
        spotifyGet(SPOTIFY_MY_PLAYLISTS_URL, "limit" to "1")?.int("total")

    private suspend fun spotifyGet(url: String, vararg params: Pair<String, String>): JsonObject? {
        val token = snapshot()[SpotifyAccessTokenKey].orEmpty()
        if (token.isBlank()) return null
        return try {
            val body = graph.http.get(url) {
                header("Authorization", "Bearer $token")
                header("Accept", "application/json")
                params.forEach { (key, value) -> parameter(key, value) }
            }.takeIf { it.status.isSuccess() }?.bodyAsText()
            body?.let { json.parseToJsonElement(it) as? JsonObject }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}

/** The catalog snapshots kept per account; a new or removed account must not inherit them. */
private fun PreferenceEditor.clearCatalogSnapshots() {
    this[InlineSpotifyProfileRecentItemsKey] = null
    this[InlineSpotifyProfileTopTracksKey] = null
    this[InlineSpotifyProfileArtistsKey] = null
    this[InlineSpotifyProfileCacheTsKey] = null
    this[SpotifyRecentlyPlayedCacheKey] = null
    this[SpotifyRecentlyPlayedCacheFetchedAtKey] = null
}

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.content?.toIntOrNull()

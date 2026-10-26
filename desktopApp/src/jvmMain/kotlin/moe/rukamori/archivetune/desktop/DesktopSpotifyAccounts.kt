package moe.rukamori.archivetune.desktop

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.desktop.integration.auth.DesktopCookieOnlyBrowserAuth
import moe.rukamori.archivetune.shared.domain.platform.AuthorizedHttpsOrigin
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginFailure
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginOutcome
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginRequest
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.platform.PrivateCookie
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
import moe.rukamori.archivetune.shared.domain.settings.SpotifyRecentlyPlayedCacheFetchedAtKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyRecentlyPlayedCacheKey
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
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.SpotifyCatalogAccountState
import moe.rukamori.archivetune.spotify.Spotify
import moe.rukamori.archivetune.spotify.SpotifyAuth
import moe.rukamori.archivetune.spotify.SpotifyHashProvider
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylistTracksRef

private const val SPOTIFY_PROVIDER = "spotify"
private const val TOKEN_EXPIRY_GRACE_MS = 60_000L
private const val PLAYLIST_PAGE_SIZE = 50

/** In-flight parallel track-count fetches in [DesktopSpotifyAccounts.fetchAllPlaylists] — the Android cap. */
private const val COUNT_FETCH_CONCURRENCY = 8
private const val GQL_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
private const val REST_ME_URL = "https://api.spotify.com/v1/me"
private const val WEB_PLAYER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/** The session cookies are set for .spotify.com, so the web player origin carries them after the sign-in returns. */
private val PLAYER_ORIGIN = AuthorizedHttpsOrigin("open.spotify.com")

/*
 * The Spotify sign-in, its challenge page and the third-party sign-in options it offers. The native window refuses
 * every other navigation, so this list is the whole official flow.
 */
private val SPOTIFY_LOGIN_ORIGINS: Set<AuthorizedHttpsOrigin> = listOf(
    "accounts.spotify.com", "open.spotify.com", "www.spotify.com", "challenge.spotify.com", "www.google.com",
    "accounts.google.com", "appleid.apple.com", "www.facebook.com",
).map { AuthorizedHttpsOrigin(it) }.toSet()

private val cacheJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
private val playlistCacheSerializer = ListSerializer(serializer<SpotifyPlaylist>())

/** A Spotify account as its own profile reports it. */
internal data class SpotifyAccountProfile(val userId: String, val displayName: String?)

private class SpotifyCookies(val spDc: String, val spKey: String)

/** How the cookie window ended. Only [Captured] carries cookies. */
private sealed interface SpotifyCookieResult {
    class Captured(val cookies: SpotifyCookies) : SpotifyCookieResult
    data object Cancelled : SpotifyCookieResult
    data object NoSession : SpotifyCookieResult
    data object BrowserUnavailable : SpotifyCookieResult
}

/**
 * The two Spotify accounts of the desktop host, kept apart as on Android.
 *
 * The catalog account signs in through the native cookie window, mints its web-player token with [SpotifyAuth] and is
 * the only user of the global [Spotify.accessToken]. Token refreshes and every catalog mutation share one lock.
 *
 * The streaming account signs in through its own window and is verified with [SpotifyStreamingPolicy]: nothing is
 * stored unless its cookie mints a signed-in token, its profile is readable and it is not the catalog account. It never
 * reads the catalog token for anything but that comparison and never sets [Spotify.accessToken].
 */
internal class DesktopSpotifyAccounts(
    private val graph: DesktopSessionGraph,
    private val controller: SettingsController,
) {
    private val browser = DesktopCookieOnlyBrowserAuth()
    private val generations = AtomicLong()
    private val catalogLock = Mutex()
    private val streamingLock = Mutex()
    private var playlists: List<SpotifyPlaylist>? = null
    private var catalogAttempt: Job? = null
    private var streamingAttempt: Job? = null

    private val catalogState = MutableStateFlow(SpotifyCatalogAccountState())
    val catalog: StateFlow<SpotifyCatalogAccountState> = catalogState.asStateFlow()
    private val catalogErrorState = MutableStateFlow<String?>(null)
    val catalogError: StateFlow<String?> = catalogErrorState.asStateFlow()
    private val streamingBusyState = MutableStateFlow(false)
    val streamingSignInInProgress: StateFlow<Boolean> = streamingBusyState.asStateFlow()
    private val refusalState = MutableStateFlow<SpotifySignInRefusal?>(null)
    val streamingRefusal: StateFlow<SpotifySignInRefusal?> = refusalState.asStateFlow()

    init {
        // Response bodies stay out of the log: only the line before the separator, which names the call and status.
        Spotify.logger = { level, message ->
            if (level == "E" || level == "W") graph.report("spotify: ${message.substringBefore(" — ").take(160)}")
        }
    }

    private fun snapshot(): PreferenceSnapshot = graph.settings.snapshots.value ?: PreferenceSnapshot.EMPTY

    /**
     * Restores the stored catalog session when the integration page opens: the cached playlists first, then a token
     * when the stored one has expired. A session that cannot mint a token reads as signed out, as on Android.
     */
    suspend fun restore() {
        if (catalogState.value.isLoading) return
        val prefs = snapshot()
        if (prefs[SpotifySpDcKey].isNullOrBlank()) {
            catalogState.value = SpotifyCatalogAccountState()
            return
        }
        restoreCachedPlaylists(prefs)
        try {
            refreshToken(rejected = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("spotify catalog session not restored: ${failure.javaClass.simpleName}")
            catalogState.value = SpotifyCatalogAccountState()
            return
        }
        catalogState.value = SpotifyCatalogAccountState(
            isAuthenticated = true,
            accountName = snapshot()[SpotifyAccountNameKey].orEmpty(),
            playlistCount = playlists?.size ?: 0,
        )
    }

    /** Opens the catalog sign-in window. The connect row is disabled while this runs. */
    fun connectCatalog() {
        if (catalogState.value.isLoading) return
        catalogAttempt?.cancel()
        catalogErrorState.value = null
        catalogState.update { it.copy(isLoading = true) }
        catalogAttempt = graph.scope.launch {
            try {
                when (val result = captureCookies(CredentialScope.SPOTIFY_CATALOG)) {
                    is SpotifyCookieResult.Captured -> {
                        connectWithCookies(result.cookies)
                        loadPlaylists()
                    }
                    SpotifyCookieResult.Cancelled -> Unit
                    SpotifyCookieResult.NoSession -> catalogErrorState.value = DesktopStrings["spotify_not_connected"]
                    SpotifyCookieResult.BrowserUnavailable ->
                        catalogErrorState.value = DesktopStrings["native_browser_unavailable"]
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("spotify catalog sign-in failed: ${failure.javaClass.simpleName}")
                catalogErrorState.value = failure.message ?: DesktopStrings["spotify_not_connected"]
            } finally {
                catalogState.update { it.copy(isLoading = false) }
            }
        }
    }

    suspend fun reloadPlaylists() {
        if (catalogState.value.isLoading) return
        catalogState.update { it.copy(isLoading = true) }
        try {
            loadPlaylists()
        } finally {
            catalogState.update { it.copy(isLoading = false) }
        }
    }

    suspend fun logOutCatalog() {
        catalogAttempt?.cancel()
        catalogLock.withLock {
            controller.edit {
                this[SpotifySpDcKey] = null
                this[SpotifySpKeyKey] = null
                this[SpotifyAccessTokenKey] = null
                this[SpotifyAccessTokenExpiresAtKey] = null
                this[SpotifyAccountNameKey] = null
                this[SpotifyAccountAvatarUrlKey] = null
                this[SpotifyLibraryPlaylistsCacheKey] = null
                clearCatalogSnapshots()
            }
            Spotify.accessToken = null
            Spotify.forgetAccount()
            playlists = null
        }
        browser.clearProfile(SPOTIFY_PROVIDER, CredentialScope.SPOTIFY_CATALOG)
        catalogErrorState.value = null
        catalogState.value = SpotifyCatalogAccountState()
    }

    fun dismissCatalogError() {
        catalogErrorState.value = null
    }

    /** Opens the streaming sign-in window. [show] receives the hint and any window failure. */
    fun signInStreaming(show: (String) -> Unit) {
        if (streamingBusyState.value) return
        refusalState.value = null
        streamingBusyState.value = true
        show(DesktopStrings["spotify_login_window_hint"])
        streamingAttempt = graph.scope.launch {
            try {
                when (val result = captureCookies(CredentialScope.SPOTIFY_STREAMING)) {
                    is SpotifyCookieResult.Captured -> refusalState.value = verifyAndStoreStreaming(result.cookies)
                    SpotifyCookieResult.Cancelled -> Unit
                    SpotifyCookieResult.NoSession -> refusalState.value = SpotifySignInRefusal.INVALID_SESSION
                    SpotifyCookieResult.BrowserUnavailable -> show(DesktopStrings["native_browser_unavailable"])
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("spotify streaming sign-in failed: ${failure.javaClass.simpleName}")
                refusalState.value = SpotifySignInRefusal.PROFILE_UNAVAILABLE
            } finally {
                streamingBusyState.value = false
            }
        }
    }

    fun cancelStreamingSignIn() {
        streamingAttempt?.cancel()
        streamingBusyState.value = false
    }

    fun dismissStreamingRefusal() {
        refusalState.value = null
    }

    /** The page commits the stored streaming session away after this; only an open window is left to close. */
    suspend fun signOutStreaming() {
        streamingAttempt?.cancel()
        browser.clearProfile(SPOTIFY_PROVIDER, CredentialScope.SPOTIFY_STREAMING)
    }

    private suspend fun captureCookies(scope: CredentialScope): SpotifyCookieResult {
        val request = CookieOnlyLoginRequest(
            provider = SPOTIFY_PROVIDER,
            scope = scope,
            startUrl = SpotifyAuth.LOGIN_URL,
            authorizedOrigins = SPOTIFY_LOGIN_ORIGINS,
            generation = generations.incrementAndGet(),
        )
        val context = when (val outcome = browser.login(request)) {
            is CookieOnlyLoginOutcome.Completed -> outcome.context
            CookieOnlyLoginOutcome.Cancelled -> return SpotifyCookieResult.Cancelled
            is CookieOnlyLoginOutcome.Failed ->
                return if (outcome.failure == CookieOnlyLoginFailure.BROWSER_UNAVAILABLE) {
                    SpotifyCookieResult.BrowserUnavailable
                } else {
                    SpotifyCookieResult.NoSession
                }
        }
        val cookies = try {
            context.cookiesFor(PLAYER_ORIGIN)
        } finally {
            context.close()
        }
        val spDc = cookieValue(cookies, "sp_dc")
        if (spDc.isBlank()) return SpotifyCookieResult.NoSession
        return SpotifyCookieResult.Captured(SpotifyCookies(spDc, cookieValue(cookies, "sp_key")))
    }

    private suspend fun connectWithCookies(cookies: SpotifyCookies) {
        catalogLock.withLock {
            val prefs = snapshot()
            val changed = prefs[SpotifySpDcKey] != cookies.spDc || prefs[SpotifySpKeyKey].orEmpty() != cookies.spKey
            controller.edit {
                this[SpotifySpDcKey] = cookies.spDc
                this[SpotifySpKeyKey] = cookies.spKey.ifBlank { null }
                this[SpotifyLibraryPlaylistsCacheKey] = null
                if (changed) {
                    this[SpotifyAccessTokenKey] = null
                    this[SpotifyAccessTokenExpiresAtKey] = null
                    clearCatalogSnapshots()
                }
            }
            if (changed) {
                Spotify.accessToken = null
                Spotify.forgetAccount()
            }
            playlists = null
            refreshTokenLocked(rejected = null)
        }
        catalogState.value = SpotifyCatalogAccountState(
            isAuthenticated = true,
            isLoading = true,
            accountName = snapshot()[SpotifyAccountNameKey].orEmpty(),
        )
    }

    private suspend fun loadPlaylists() {
        catalogErrorState.value = null
        try {
            refreshToken(rejected = null)
            refreshProfile()
            val loaded = fetchAllPlaylists()
            playlists = loaded
            controller.edit { this[SpotifyLibraryPlaylistsCacheKey] = cacheJson.encodeToString(playlistCacheSerializer, loaded) }
            catalogState.update {
                it.copy(playlistCount = loaded.size, accountName = snapshot()[SpotifyAccountNameKey].orEmpty())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("spotify playlists not loaded: ${failure.javaClass.simpleName}")
            catalogErrorState.value = failure.message
        }
    }

    /*
     * Same method as the Android library page (SpotifyLibraryRepository.fetchAllPlaylists): the libraryV3 GraphQL
     * response often omits `tracks.totalCount` for leaf playlists, so the missing counts are looked up one request per
     * playlist with bounded concurrency. Eight in flight keeps the burst under Spotify's 429 threshold while cutting
     * the wall time ~8x against serial reads; each lookup is one `playlistTracks(limit = 1)` total read through the
     * same token-retry path.
     */
    private suspend fun fetchAllPlaylists(): List<SpotifyPlaylist> {
        val loaded = ArrayList<SpotifyPlaylist>()
        var offset = 0
        while (true) {
            val page = withTokenRetry { Spotify.myPlaylists(limit = PLAYLIST_PAGE_SIZE, offset = offset).getOrThrow() }
            if (page.items.isEmpty()) break
            val enriched =
                coroutineScope {
                    val semaphore = Semaphore(COUNT_FETCH_CONCURRENCY)
                    page.items
                        .map { playlist ->
                            async(Dispatchers.IO) {
                                if (playlist.tracks?.total != null) {
                                    playlist
                                } else {
                                    semaphore.withPermit {
                                        playlistTrackCount(playlist.id)
                                            ?.let { playlist.copy(tracks = SpotifyPlaylistTracksRef(total = it)) }
                                            ?: playlist
                                    }
                                }
                            }
                        }.awaitAll()
                }
            loaded += enriched
            offset += page.items.size
            if (offset >= page.total || page.items.size < PLAYLIST_PAGE_SIZE) break
        }
        return loaded
    }

    private suspend fun playlistTrackCount(playlistId: String): Int? =
        try {
            withTokenRetry {
                Spotify
                    .playlistTracks(playlistId = playlistId, limit = 1, offset = 0)
                    .getOrThrow()
            }.total
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            graph.report("spotify playlist track count failed: ${error.javaClass.simpleName}")
            null
        }

    private fun restoreCachedPlaylists(prefs: PreferenceSnapshot) {
        if (!playlists.isNullOrEmpty()) return
        val cached = prefs[SpotifyLibraryPlaylistsCacheKey].orEmpty()
        if (cached.isBlank()) return
        playlists = try {
            cacheJson.decodeFromString(playlistCacheSerializer, cached)
        } catch (_: Exception) {
            graph.report("spotify playlist cache unreadable")
            null
        }
    }

    private suspend fun refreshToken(rejected: String?) = catalogLock.withLock { refreshTokenLocked(rejected) }

    /** Reuses a stored token that is still valid and was not just rejected; otherwise mints one from the cookie. */
    private suspend fun refreshTokenLocked(rejected: String?) {
        val prefs = snapshot()
        val spDc = prefs[SpotifySpDcKey].orEmpty()
        check(spDc.isNotBlank()) { DesktopStrings["spotify_not_connected"] }
        val stored = prefs[SpotifyAccessTokenKey].orEmpty()
        val expiresAt = prefs[SpotifyAccessTokenExpiresAtKey] ?: 0L
        if (stored.isNotBlank() && expiresAt > System.currentTimeMillis() + TOKEN_EXPIRY_GRACE_MS && stored != rejected) {
            Spotify.accessToken = stored
            return
        }
        val token = SpotifyAuth.fetchAccessToken(spDc = spDc, spKey = prefs[SpotifySpKeyKey].orEmpty()).getOrThrow()
        Spotify.accessToken = token.accessToken
        controller.edit {
            this[SpotifyAccessTokenKey] = token.accessToken
            this[SpotifyAccessTokenExpiresAtKey] = token.accessTokenExpirationTimestampMs
        }
        refreshProfile()
    }

    private suspend fun refreshProfile() {
        val user = Spotify.me().getOrNull() ?: return
        controller.edit {
            this[SpotifyAccountNameKey] = user.displayName.orEmpty()
            this[SpotifyAccountAvatarUrlKey] = user.images.firstOrNull()?.url
        }
    }

    private suspend fun <T> withTokenRetry(block: suspend () -> T): T {
        val rejected = Spotify.accessToken
        return try {
            block()
        } catch (failure: Spotify.SpotifyException) {
            if (failure.statusCode != 401 || snapshot()[SpotifySpDcKey].isNullOrBlank()) throw failure
            refreshToken(rejected)
            block()
        }
    }

    /** Applies [SpotifyStreamingPolicy.decideSignIn] and stores the session only when it accepts. */
    private suspend fun verifyAndStoreStreaming(cookies: SpotifyCookies): SpotifySignInRefusal? = streamingLock.withLock {
        val prefs = snapshot()
        val catalogSpDc = prefs[SpotifySpDcKey].orEmpty()
        val sameCookie = catalogSpDc.isNotBlank() && catalogSpDc == cookies.spDc
        val token = if (sameCookie) null else {
            SpotifyAuth.fetchAccessToken(cookies.spDc, cookies.spKey).getOrNull()?.takeUnless { it.isAnonymous }
        }
        val profile = token?.let { fetchSpotifyProfile(graph.http, it.accessToken) }
        val catalogUserId = if (catalogSpDc.isNotBlank() && profile != null) {
            catalogTokenForComparison(prefs)?.let { fetchSpotifyProfile(graph.http, it) }?.userId
        } else {
            null
        }
        val decision = SpotifyStreamingPolicy.decideSignIn(
            catalogSpDc = catalogSpDc,
            candidateSpDc = cookies.spDc,
            tokenMinted = token != null,
            streamingUserId = profile?.userId,
            catalogUserId = { catalogUserId },
        )
        when (decision) {
            is SpotifySignInDecision.Refuse -> decision.reason
            SpotifySignInDecision.Accept -> {
                val accepted = checkNotNull(token)
                val account = checkNotNull(profile)
                controller.edit {
                    this[SpotifyStreamSpDcKey] = cookies.spDc
                    this[SpotifyStreamSpKeyKey] = cookies.spKey
                    this[SpotifyStreamAccessTokenKey] = accepted.accessToken
                    this[SpotifyStreamAccessTokenExpiresAtKey] = accepted.accessTokenExpirationTimestampMs
                    this[SpotifyStreamUserIdKey] = account.userId
                    this[SpotifyStreamAccountNameKey] = account.displayName.orEmpty()
                }
                null
            }
        }
    }

    /*
     * The catalog token for the one comparison at streaming sign-in. A valid stored token is used as is; otherwise one
     * is minted from the catalog cookie and discarded, so the catalog session is never changed from here.
     */
    private suspend fun catalogTokenForComparison(prefs: PreferenceSnapshot): String? {
        val stored = prefs[SpotifyAccessTokenKey].orEmpty()
        if (stored.isNotBlank() && (prefs[SpotifyAccessTokenExpiresAtKey] ?: 0L) - TOKEN_EXPIRY_GRACE_MS > System.currentTimeMillis()) {
            return stored
        }
        val spDc = prefs[SpotifySpDcKey].orEmpty().ifBlank { return null }
        return SpotifyAuth.fetchAccessToken(spDc, prefs[SpotifySpKeyKey].orEmpty()).getOrNull()
            ?.takeUnless { it.isAnonymous }?.accessToken
    }
}

/** The catalog snapshots Android keeps per account; a new or removed account must not inherit them. */
private fun PreferenceEditor.clearCatalogSnapshots() {
    this[InlineSpotifyProfileRecentItemsKey] = null
    this[InlineSpotifyProfileTopTracksKey] = null
    this[InlineSpotifyProfileArtistsKey] = null
    this[InlineSpotifyProfileCacheTsKey] = null
    this[SpotifyRecentlyPlayedCacheKey] = null
    this[SpotifyRecentlyPlayedCacheFetchedAtKey] = null
}

/* A host-only cookie is more specific than the shared .spotify.com one, so it wins when both are present. */
private fun cookieValue(cookies: List<PrivateCookie>, name: String): String =
    cookies.filter { it.name == name }.maxByOrNull { it.domain.removePrefix(".").length }?.value?.reveal().orEmpty()

/**
 * Reads the profile of the account behind [token]: the web player's GraphQL profile first, with the previous query hash
 * as a fallback, then the public REST profile. Null when neither answers.
 */
internal suspend fun fetchSpotifyProfile(http: HttpClient, token: String): SpotifyAccountProfile? =
    profileFromGraphQl(http, token) ?: profileFromRest(http, token)

private suspend fun profileFromGraphQl(http: HttpClient, token: String): SpotifyAccountProfile? {
    val primary = SpotifyHashProvider.getHash("profileAttributes")
    val hashes = listOfNotNull(primary, SpotifyHashProvider.getPreviousHash("profileAttributes")?.takeIf { it != primary })
    for (hash in hashes) {
        val body = buildJsonObject {
            put("variables", JsonObject(emptyMap()))
            put("operationName", "profileAttributes")
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", hash)
                }
            }
        }
        val text = spotifyRequest {
            http.post(GQL_URL) {
                header("Authorization", "Bearer $token")
                header("Accept", "application/json")
                header("app-platform", "WebPlayer")
                header("Origin", "https://open.spotify.com")
                header("Referer", "https://open.spotify.com/")
                header("User-Agent", WEB_PLAYER_USER_AGENT)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.takeIf { it.status.isSuccess() }?.bodyAsText()
        } ?: continue
        val profile = parseObject(text)?.child("data")?.child("me")?.child("profile") ?: continue
        val userId = profile.text("uri")?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: continue
        return SpotifyAccountProfile(userId, profile.text("name")?.takeIf { it.isNotBlank() })
    }
    return null
}

private suspend fun profileFromRest(http: HttpClient, token: String): SpotifyAccountProfile? {
    val text = spotifyRequest {
        http.get(REST_ME_URL) {
            header("Authorization", "Bearer $token")
            header("Accept", "application/json")
        }.takeIf { it.status.isSuccess() }?.bodyAsText()
    } ?: return null
    val json = parseObject(text) ?: return null
    val userId = json.text("id")?.takeIf { it.isNotBlank() } ?: return null
    return SpotifyAccountProfile(userId, json.text("display_name")?.takeIf { it.isNotBlank() })
}

private suspend fun spotifyRequest(block: suspend () -> String?): String? =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

private fun parseObject(text: String): JsonObject? =
    try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    }

private fun JsonObject.child(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

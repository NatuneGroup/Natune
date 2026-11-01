@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.toByteArray
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.rukamori.archivetune.shared.data.lyrics.AppleWebPlayJwt
import moe.rukamori.archivetune.shared.data.platform.ios.iosBuildSetting
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.data.provider.KtorSpotifyAuth
import moe.rukamori.archivetune.shared.domain.settings.CanvasHealthProvider
import moe.rukamori.archivetune.shared.domain.settings.CanvasResolverEndpointsKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SpotifyAccessTokenExpiresAtKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyAccessTokenKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifySpDcKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifySpKeyKey
import moe.rukamori.archivetune.shared.domain.settings.comparableSongTitle
import moe.rukamori.archivetune.shared.domain.settings.parseCanvasResolverEndpoints
import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCompatibilityMapping

/**
 * One resolved canvas for the current occurrence: the fields the providers answer and the two URLs the owner picks
 * between, mirroring the desktop `CanvasArtwork` model. [preferredAnimationUrl] is the landscape/square loop;
 * [preferredVerticalAnimationUrl] is the tall one, picked when the first is absent.
 */
internal data class IosCanvasResult(
    val provider: CanvasHealthProvider,
    val name: String? = null,
    val artist: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val static: String? = null,
    val animated: String? = null,
    val animatedVertical: String? = null,
    val videoUrl: String? = null,
    val videoUrlVertical: String? = null,
) {
    val preferredAnimationUrl: String? get() = animated ?: videoUrl
    val preferredVerticalAnimationUrl: String? get() = animatedVertical ?: videoUrlVertical
}

/**
 * The four remote canvas lookups, in the same order and behind the same gates as the desktop `:canvas` providers:
 * BetterLyrics (the shared catalogue), Apple Music (a scraped web-player token against AMP), TIDAL (the embedded
 * search token the build supplies) and Spotify (the official canvaz endpoint, then the community resolvers).
 * Every lookup runs over the session's one HTTP client — the providers only add their own headers, timeouts and
 * response shapes — and every fetch passes [fetch]'s policy check before and after its request so a canvas the
 * settings page shows closed is never asked for.
 */
internal class IosCanvasProviders(
    private val http: HttpClient,
    private val preferences: () -> PreferenceSnapshot?,
    private val persistSpotifyToken: suspend (accessToken: String, expiresAtMs: Long) -> Unit,
    private val report: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(
        provider: CanvasHealthProvider,
        title: String,
        artist: String,
        videoId: String,
        storefront: String,
        check: (CanvasHealthProvider) -> Unit,
    ): IosCanvasResult? = when (provider) {
        CanvasHealthProvider.BETTER_LYRICS -> betterLyrics(title, artist, storefront, check)
        CanvasHealthProvider.APPLE_MUSIC -> appleMusic(title, artist, storefront, check)
        CanvasHealthProvider.TIDAL -> tidal(title, artist, storefront, check)
        CanvasHealthProvider.SPOTIFY -> spotify(videoId, title, artist, check)
    }

    // ── BetterLyrics ──────────────────────────────────────────────────────────

    private val betterLyricsLock = Mutex()
    private val betterLyricsCache = LinkedHashMap<String, Pair<IosCanvasResult, Long>>()

    /**
     * The shared canvas catalogue. The desktop walks the same endpoint and then applies the song-identity rule the
     * artwork itself carries: the hit must name the same song and artist, and carry an animated URL. A miss is not
     * cached here — the next provider in line still answers.
     */
    private suspend fun betterLyrics(
        song: String,
        artist: String,
        storefront: String,
        check: (CanvasHealthProvider) -> Unit,
    ): IosCanvasResult? {
        check(CanvasHealthProvider.BETTER_LYRICS)
        val key = "s=${song.trim().lowercase()}|a=${artist.trim().lowercase()}|" +
            "storefront=${storefront.trim().lowercase()}"
        val now = iosNowMillis()
        betterLyricsLock.withLock {
            betterLyricsCache[key]?.takeIf { it.second > now }?.let { return it.first }
        }
        val artwork = try {
            val response = http.get(BETTER_LYRICS_URL) {
                parameter("s", song)
                parameter("a", artist)
                parameter("storefront", storefront)
                timeout { requestTimeoutMillis = 18_000L; connectTimeoutMillis = 12_000L }
            }
            check(CanvasHealthProvider.BETTER_LYRICS)
            if (response.status != HttpStatusCode.OK) return null
            val root = runCatching { json.parseToJsonElement(response.bodyAsText()) as? JsonObject }
                .getOrNull() ?: return null
            IosCanvasResult(
                provider = CanvasHealthProvider.BETTER_LYRICS,
                name = root.text("name"),
                artist = root.text("artist"),
                albumId = root.text("albumId"),
                albumName = root.text("albumName"),
                static = root.text("static"),
                animated = root.text("animated"),
                animatedVertical = root.text("animatedVertical"),
                videoUrl = root.text("videoUrl"),
                videoUrlVertical = root.text("videoUrlVertical"),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("canvas betterlyrics failed: ${failure::class.simpleName}")
            return null
        }
        if (!artwork.matchesSongIdentity(song, artist) || artwork.preferredAnimationUrl.isNullOrBlank()) return null
        betterLyricsLock.withLock {
            if (betterLyricsCache.size >= BETTER_LYRICS_CACHE_MAX) betterLyricsCache.clear()
            betterLyricsCache[key] = artwork to iosNowMillis() + BETTER_LYRICS_TTL_MS
        }
        return artwork
    }

    // ── Apple Music ───────────────────────────────────────────────────────────

    // The scraped web-player bearer, its decoded expiry and the scrape throttle are per-provider state, exactly
    // the desktop's volatile fields. Nothing is seeded: a build keeps the scraped token until Apple rotates it.
    @Volatile private var appleToken: String? = null
    @Volatile private var appleTokenExpAtSec = 0L
    @Volatile private var appleTokenLastRefreshAtMs = 0L
    private val appleTokenLock = Mutex()
    private val appleCacheLock = Mutex()
    private val appleCache = LinkedHashMap<String, Pair<IosCanvasResult?, Long>>()

    private suspend fun appleMusic(
        song: String,
        artist: String,
        storefront: String,
        check: (CanvasHealthProvider) -> Unit,
    ): IosCanvasResult? {
        check(CanvasHealthProvider.APPLE_MUSIC)
        val key = "song|${song.trim().lowercase()}|${artist.trim().lowercase()}|${storefront.trim().lowercase()}"
        val now = iosNowMillis()
        appleCacheLock.withLock {
            appleCache[key]?.takeIf { it.second > now }?.let { return it.first }
        }
        val result = appleSearchAndFetchMotion(song, artist, storefront)
        val artwork = result?.takeIf {
            it.matchesSongIdentity(song, artist) && !it.preferredAnimationUrl.isNullOrBlank()
        }?.copy(provider = CanvasHealthProvider.APPLE_MUSIC)
        appleCacheLock.withLock { appleCache[key] = artwork to iosNowMillis() + APPLE_TTL_MS }
        return artwork
    }

    /**
     * A usable web-player bearer: the held token while it outlives the refresh margin, otherwise one scrape —
     * throttled so an unreachable music.apple.com is not hit per lookup. Null means the lookup is skipped, the
     * same refusal the desktop reports instead of a certain 401. iOS holds no pasted dev token, so the scraped
     * one is the only source.
     */
    private suspend fun appleToken(): String? {
        val nowSec = iosNowMillis() / 1000L
        val held = appleToken
        if (held != null && appleTokenExpAtSec - nowSec >= APPLE_TOKEN_REFRESH_MARGIN_SEC) return held
        val sinceLast = iosNowMillis() - appleTokenLastRefreshAtMs
        if (sinceLast in 1..APPLE_TOKEN_REFRESH_THROTTLE_MS) return appleValidHeldToken(nowSec)
        return appleRefreshToken() ?: appleValidHeldToken(nowSec)
    }

    private fun appleValidHeldToken(nowSec: Long): String? =
        appleToken?.takeIf { appleTokenExpAtSec > nowSec }

    private suspend fun appleRefreshToken(): String? = appleTokenLock.withLock {
        appleTokenLastRefreshAtMs = iosNowMillis()
        val fresh = appleScrapeToken()
        if (fresh != null) {
            appleToken = fresh
            appleTokenExpAtSec = AppleWebPlayJwt.expSec(fresh)
        } else {
            report("canvas apple web-player token unavailable — lookups skipped until a scrape succeeds")
        }
        fresh
    }

    /**
     * The scrape: the landing page names the JavaScript bundle and the bundle carries the token. The shared
     * [AppleWebPlayJwt] port applies the same selection rules the desktop module does — entry chunk first, at most
     * four bundles, only an `AMPWebPlay` bearer with a readable future expiry counts.
     */
    private suspend fun appleScrapeToken(): String? {
        val nowSec = iosNowMillis() / 1000L
        val html = try {
            val home = http.get(APPLE_MUSIC_WEB_HOME) {
                appleWebHeaders()
                timeout { requestTimeoutMillis = 10_000L; connectTimeoutMillis = 8_000L }
            }
            if (!home.status.isSuccess()) {
                report("canvas apple home fetch failed: ${home.status}")
                return null
            }
            home.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        AppleWebPlayJwt.select(html, nowSec)?.let { return it }
        for (bundleUrl in AppleWebPlayJwt.bundleUrls(html)) {
            val bundle = try {
                val response = http.get(bundleUrl) {
                    appleWebHeaders()
                    timeout { requestTimeoutMillis = 15_000L; connectTimeoutMillis = 8_000L }
                }
                if (!response.status.isSuccess()) continue
                response.bodyAsText()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }
            AppleWebPlayJwt.select(bundle, nowSec)?.let { return it }
        }
        return null
    }

    /**
     * The AMP catalog search for the song: the editorial-video extension on the top scored match supplies the
     * motion URL directly, or the album id resolves to a second lookup. The same scoring, blacklist and
     * playlist-id refusal the desktop applies are kept verbatim, and a 401 retries once after a token refresh.
     */
    private suspend fun appleSearchAndFetchMotion(
        term: String,
        artist: String,
        storefront: String,
    ): IosCanvasResult? = runCatching {
        var query = if (term.contains(artist, ignoreCase = true)) term else "$artist $term"
        val searchUrl = "$AMP_BASE_URL/v1/catalog/$storefront/search"
        var token = appleToken() ?: return@runCatching null
        var response = appleAmpGet(searchUrl, token) {
            parameter("term", query)
            parameter("types", "songs")
            parameter("limit", "10")
            parameter("extend", "editorialVideo")
            parameter("include", "albums")
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            token = appleRefreshToken() ?: return@runCatching null
            response = appleAmpGet(searchUrl, token) {
                parameter("term", query)
                parameter("types", "songs")
                parameter("limit", "10")
                parameter("extend", "editorialVideo")
                parameter("include", "albums")
            }
        }
        if (response.status != HttpStatusCode.OK) return@runCatching null
        val root = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            ?: return@runCatching null
        val results = root["results"]?.jsonObject?.get("songs")?.jsonObject?.get("data")?.jsonArray
            ?: return@runCatching null
        val scored = results.mapNotNull { (it as? JsonObject)?.let { obj -> appleScoreItem(obj, term, artist) } }
            .sortedByDescending { it.first }
        for ((score, obj) in scored) {
            if (score < 12) continue
            val attributes = obj["attributes"]?.jsonObject ?: continue
            val resultName = attributes["name"]?.jsonPrimitive?.contentOrNull ?: ""
            val resultArtist = attributes["artistName"]?.jsonPrimitive?.contentOrNull ?: ""
            val itemType = obj["type"]?.jsonPrimitive?.contentOrNull
            val albumId = appleResolveAlbumId(obj, attributes, itemType)
            if (albumId == null || albumId.startsWith("pl.")) continue
            val ev = attributes["editorialVideo"]?.jsonObject
            if (ev != null) {
                val videoUrls = appleEditorialVideoUrls(ev)
                if (!videoUrls.animated.isNullOrBlank() || !videoUrls.animatedVertical.isNullOrBlank()) {
                    val name = attributes["name"]?.jsonPrimitive?.contentOrNull
                    val collName = attributes["collectionName"]?.jsonPrimitive?.contentOrNull
                    return@runCatching IosCanvasResult(
                        provider = CanvasHealthProvider.APPLE_MUSIC,
                        name = name,
                        artist = resultArtist,
                        albumId = albumId,
                        albumName = if (itemType == "songs") collName else name,
                        animated = videoUrls.animated,
                        animatedVertical = videoUrls.animatedVertical,
                    )
                }
            }
            appleFetchMotionArtwork(
                albumId = albumId,
                storefront = storefront,
                fallbackArtist = resultArtist,
                titleOverride = if (itemType == "songs") attributes["name"]?.jsonPrimitive?.contentOrNull else null,
                artistOverride = if (itemType == "songs") resultArtist else null,
            )?.let { return@runCatching it }
        }
        null
    }.onFailure { if (it is CancellationException) throw it }.getOrNull()

    /** The album-level AMP lookup: the editorialVideo extension on the album itself, after the blacklist check. */
    private suspend fun appleFetchMotionArtwork(
        albumId: String,
        storefront: String,
        fallbackArtist: String?,
        titleOverride: String? = null,
        artistOverride: String? = null,
    ): IosCanvasResult? {
        if (albumId.startsWith("pl.")) return null
        return runCatching {
            val albumUrl = "$AMP_BASE_URL/v1/catalog/$storefront/albums/$albumId"
            var token = appleToken() ?: return@runCatching null
            var response = appleAmpGet(albumUrl, token) {
                parameter("extend", "editorialVideo")
                parameter("include", "tracks")
            }
            if (response.status == HttpStatusCode.Unauthorized) {
                token = appleRefreshToken() ?: return@runCatching null
                response = appleAmpGet(albumUrl, token) {
                    parameter("extend", "editorialVideo")
                    parameter("include", "tracks")
                }
            }
            if (response.status != HttpStatusCode.OK) return@runCatching null
            val root = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                ?: return@runCatching null
            val albumObj = root["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching null
            val attributes = albumObj["attributes"]?.jsonObject
            val albumName = attributes?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
            val artistName = attributes?.get("artistName")?.jsonPrimitive?.contentOrNull ?: fallbackArtist
            if (appleAlbumBlacklisted(albumName)) return@runCatching null
            val ev = attributes?.get("editorialVideo")?.jsonObject ?: return@runCatching null
            val videoUrls = appleEditorialVideoUrls(ev)
            if (videoUrls.animated.isNullOrBlank() && videoUrls.animatedVertical.isNullOrBlank()) {
                return@runCatching null
            }
            IosCanvasResult(
                provider = CanvasHealthProvider.APPLE_MUSIC,
                name = titleOverride ?: albumName,
                artist = artistOverride ?: artistName,
                albumId = albumId,
                albumName = albumName,
                animated = videoUrls.animated,
                animatedVertical = videoUrls.animatedVertical,
            )
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
    }

    private suspend fun appleAmpGet(
        url: String,
        token: String,
        params: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): HttpResponse = http.get(url) {
        header("Authorization", "Bearer $token")
        header("Origin", "https://music.apple.com")
        header("Referer", "https://music.apple.com/")
        header("User-Agent", APPLE_MUSIC_WEB_UA)
        timeout { requestTimeoutMillis = 25_000L; connectTimeoutMillis = 15_000L }
        params()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.appleWebHeaders() {
        header("User-Agent", APPLE_MUSIC_WEB_UA)
        header("Accept", "text/html,application/xhtml+xml,application/javascript,*/*;q=0.8")
        header("Accept-Language", "en-US,en;q=0.9")
    }

    /** The result ranking: artist containment, title match, edition words and the album-name signal. */
    private fun appleScoreItem(
        obj: JsonObject,
        term: String,
        artist: String,
    ): Pair<Int, JsonObject>? {
        val attributes = obj["attributes"]?.jsonObject ?: return null
        val resultArtist = attributes["artistName"]?.jsonPrimitive?.contentOrNull ?: ""
        val resultName = attributes["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val resultCollection = attributes["collectionName"]?.jsonPrimitive?.contentOrNull ?: ""
        if (appleAlbumBlacklisted(resultName) || appleAlbumBlacklisted(resultCollection)) return null
        val artistMatch = resultArtist.equals(artist, ignoreCase = true)
        val artistFuzzy = resultArtist.contains(artist, ignoreCase = true) ||
            artist.contains(resultArtist, ignoreCase = true)
        if (!artistFuzzy) return null
        var score = if (artistMatch) 10 else 5
        val nameMatch = resultName.equals(term, ignoreCase = true)
        val nameFuzzy = resultName.contains(term, ignoreCase = true) ||
            term.contains(resultName, ignoreCase = true)
        score += when {
            nameMatch -> 15
            nameFuzzy -> 7
            else -> -10
        }
        for (word in APPLE_EDITION_WORDS) {
            val inTerm = term.contains(word, ignoreCase = true)
            val inResult = resultName.contains(word, ignoreCase = true)
            score += when {
                inTerm && inResult -> 5
                inTerm != inResult && inResult -> -3
                else -> 0
            }
        }
        return score to obj
    }

    /** Album id from a song result: the relationship, the collection id, then the track URL's trailing id. */
    private fun appleResolveAlbumId(
        obj: JsonObject,
        attributes: JsonObject,
        itemType: String?,
    ): String? {
        if (itemType == "albums") return obj["id"]?.jsonPrimitive?.contentOrNull
        if (itemType != "songs") return null
        var albumId = obj["relationships"]?.jsonObject?.get("albums")?.jsonObject?.get("data")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            ?: attributes["collectionId"]?.jsonPrimitive?.contentOrNull
        if (albumId == null) {
            val url = attributes["url"]?.jsonPrimitive?.contentOrNull
            if (url != null) {
                val id = url.substringAfter("/album/", "").substringBefore("?").substringAfterLast("/", "")
                if (id.isNotBlank() && id.all { it.isDigit() }) albumId = id
            }
        }
        return albumId
    }

    private fun appleAlbumBlacklisted(name: String): Boolean {
        val lower = name.lowercase()
        return APPLE_ALBUM_BLACKLIST.any { lower.contains(it) }
    }

    private data class AppleVideoUrls(val animated: String?, val animatedVertical: String?)

    private fun appleEditorialVideoUrls(ev: JsonObject): AppleVideoUrls {
        fun JsonObject.videoUrl(): String? =
            this["video"]?.jsonPrimitive?.contentOrNull
                ?: this["videoUrl"]?.jsonPrimitive?.contentOrNull
                ?: this["hlsUrl"]?.jsonPrimitive?.contentOrNull
                ?: this["url"]?.jsonPrimitive?.contentOrNull
        val raw = ev["motionDetailRaw"]?.jsonObject?.videoUrl()
        val square = ev["motionDetailSquare"]?.jsonObject?.videoUrl()
        val tall = ev["motionDetailTall"]?.jsonObject?.videoUrl()
        val static = ev["motionDetailStatic"]?.jsonObject?.videoUrl()
        return AppleVideoUrls(animated = raw ?: square ?: static ?: tall, animatedVertical = tall)
    }

    // ── TIDAL ─────────────────────────────────────────────────────────────────

    /** The build-supplied embed token; an unconfigured build makes no TIDAL request, like the desktop module. */
    private val tidalConfigured: Boolean get() = tidalEmbedToken().isNotBlank()
    private fun tidalEmbedToken(): String = iosBuildSetting(TIDAL_EMBED_TOKEN_SETTING)

    private suspend fun tidal(
        song: String,
        artist: String,
        storefront: String,
        check: (CanvasHealthProvider) -> Unit,
    ): IosCanvasResult? {
        if (!tidalConfigured) return null
        check(CanvasHealthProvider.TIDAL)
        val response = try {
            http.get(TIDAL_SEARCH_URL) {
                header("X-Tidal-Token", tidalEmbedToken())
                parameter("query", "$artist $song")
                parameter("types", "TRACKS")
                parameter("countryCode", storefront.uppercase())
                parameter("limit", 10)
                timeout { requestTimeoutMillis = 18_000L; connectTimeoutMillis = 12_000L }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("canvas tidal search failed: ${failure::class.simpleName}")
            return null
        }
        check(CanvasHealthProvider.TIDAL)
        if (!response.status.isSuccess()) return null
        val root = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            ?: return null
        val items = root["tracks"]?.jsonObject?.get("items")?.jsonArray ?: return null
        for (item in items) {
            val track = item as? JsonObject ?: continue
            val album = track["album"]?.jsonObject ?: continue
            val videoCover = album["videoCover"]?.jsonPrimitive?.contentOrNull
            val video = tidalCoverUrl(videoCover, "videos", "1280x1280.mp4") ?: continue
            val artwork = IosCanvasResult(
                provider = CanvasHealthProvider.TIDAL,
                name = track["title"]?.jsonPrimitive?.contentOrNull,
                artist = (track["artists"]?.jsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
                    .joinToString(", "),
                albumName = album["title"]?.jsonPrimitive?.contentOrNull,
                static = tidalCoverUrl(album["cover"]?.jsonPrimitive?.contentOrNull, "images", "640x640.jpg"),
                videoUrl = video,
            )
            if (artwork.matchesSongIdentity(song, artist)) return artwork
        }
        return null
    }

    private fun tidalCoverUrl(id: String?, kind: String, file: String): String? =
        id?.takeIf { TIDAL_COVER_ID.matches(it) }?.let {
            "https://resources.tidal.com/$kind/${it.replace('-', '/')}/$file"
        }

    // ── Spotify ───────────────────────────────────────────────────────────────

    private val spotifyCacheLock = Mutex()
    private val spotifyCache = LinkedHashMap<String, Pair<IosCanvasResult?, Long>>()

    /**
     * The Spotify lookup: the official canvaz endpoint when the catalog account can mint a token and the song
     * resolves to a track URI, then the community JSON resolvers — the user's own endpoints first, then the
     * built-in one. Only a resolver that answered lets a "no canvas" result be cached, so a transient outage
     * never suppresses lookups for the next hour.
     */
    private suspend fun spotify(
        videoId: String,
        songTitle: String?,
        artistName: String?,
        check: (CanvasHealthProvider) -> Unit,
    ): IosCanvasResult? {
        check(CanvasHealthProvider.SPOTIFY)
        if (videoId.isBlank()) return null
        val now = iosNowMillis()
        spotifyCacheLock.withLock {
            spotifyCache[videoId]?.let { entry ->
                if (entry.second > now) return entry.first
                spotifyCache.remove(videoId)
            }
        }
        val official = try {
            spotifyOfficialCanvas(videoId, songTitle, artistName, check)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("canvas spotify official lookup failed: ${failure::class.simpleName}")
            null
        }
        if (official != null) {
            spotifyCacheLock.withLock { spotifyCache[videoId] = official to iosNowMillis() + SPOTIFY_TTL_MS }
            return official
        }
        val endpoints = (parseCanvasResolverEndpoints(preferences()?.get(CanvasResolverEndpointsKey)) +
            SPOTIFY_RESOLVER_URL).distinct()
        var anyResolverReachable = false
        for (endpoint in endpoints) {
            val artwork = try {
                val response = http.get(endpoint) {
                    parameter("id", videoId)
                    spotifyResolverHeaders()
                    timeout { requestTimeoutMillis = 15_000L; connectTimeoutMillis = 10_000L }
                }
                if (response.status != HttpStatusCode.OK) {
                    anyResolverReachable = true
                    null
                } else {
                    anyResolverReachable = true
                    val body = runCatching {
                        json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                    }.getOrNull()
                    body?.let { spotifyParseCanvas(it, videoId) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("canvas resolver $endpoint failed: ${failure::class.simpleName}")
                null
            }
            if (artwork != null) {
                spotifyCacheLock.withLock {
                    spotifyCache[videoId] = artwork to iosNowMillis() + SPOTIFY_TTL_MS
                }
                return artwork
            }
        }
        if (anyResolverReachable) {
            spotifyCacheLock.withLock {
                spotifyCache[videoId] = null to iosNowMillis() + SPOTIFY_TTL_MS
            }
        }
        return null
    }

    private fun io.ktor.client.request.HttpRequestBuilder.spotifyResolverHeaders() {
        // x-request-source: muzo is required by the community resolvers — without it they rate-limit.
        header("x-request-source", "muzo")
        header("User-Agent", "ArchiveTune-Android")
        header("Accept", "application/json")
    }

    /**
     * Spotify's own Canvas endpoint: the catalog access token (stored, or freshly minted from the cookie) over the
     * protobuf codec, plus the song's `spotify:track:` URI resolved through the web API. No session, no match or a
     * non-OK answer all read as "no official canvas", and nothing is cached so the resolvers still run.
     */
    private suspend fun spotifyOfficialCanvas(
        videoId: String,
        songTitle: String?,
        artistName: String?,
        check: (CanvasHealthProvider) -> Unit,
    ): IosCanvasResult? {
        check(CanvasHealthProvider.SPOTIFY)
        val token = spotifyCatalogToken(check)?.takeIf { it.isNotBlank() } ?: return null
        val trackUri = spotifyTrackUri(token, videoId, songTitle, artistName, check)
            ?.takeIf { it.isNotBlank() } ?: return null
        val response = http.post(SPOTIFY_CANVAZ_URL) {
            header("Authorization", "Bearer $token")
            header("Accept", "application/x-protobuf")
            header("Content-Type", "application/x-protobuf")
            header("User-Agent", "ArchiveTune-Android")
            setBody(IosCanvazProtocol.encodeRequest(listOf(trackUri)))
            timeout { requestTimeoutMillis = 15_000L; connectTimeoutMillis = 10_000L }
        }
        if (response.status != HttpStatusCode.OK) return null
        val entries = IosCanvazProtocol.decodeResponse(response.bodyAsChannel().toByteArray())
        val canvasUrl = entries.firstOrNull { it.entityUri == trackUri && !it.url.isNullOrBlank() }?.url
            ?: entries.firstNotNullOfOrNull { entry -> entry.url?.takeIf { it.isNotBlank() } }
            ?: return null
        return IosCanvasResult(
            provider = CanvasHealthProvider.SPOTIFY,
            name = songTitle,
            artist = artistName,
            albumId = "yt:$videoId",
            videoUrl = canvasUrl,
            videoUrlVertical = canvasUrl,
        )
    }

    /**
     * The catalog token for the canvaz call: the stored bearer while it outlives the grace window, otherwise one
     * minted from the catalog cookie through [KtorSpotifyAuth] and persisted, the same refresh the catalog account
     * owner performs. No signed-in catalog account means no official lookup.
     */
    private suspend fun spotifyCatalogToken(check: (CanvasHealthProvider) -> Unit): String? {
        check(CanvasHealthProvider.SPOTIFY)
        val prefs = preferences() ?: return null
        val spDc = prefs[SpotifySpDcKey]?.takeIf(String::isNotBlank) ?: return null
        val stored = prefs[SpotifyAccessTokenKey].orEmpty()
        val expiresAt = prefs[SpotifyAccessTokenExpiresAtKey] ?: 0L
        val nowMs = iosNowMillis()
        if (stored.isNotBlank() && expiresAt > nowMs + SPOTIFY_TOKEN_EXPIRY_GRACE_MS) return stored
        val token = try {
            KtorSpotifyAuth(http).fetchAccessToken(
                spDc = spDc,
                spKey = prefs[SpotifySpKeyKey].orEmpty(),
                nowMs = ::iosNowMillis,
            ).getOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("canvas spotify token mint failed: ${failure::class.simpleName}")
            return null
        }
        try {
            persistSpotifyToken(token.accessToken, token.accessTokenExpirationTimestampMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A minted token that cannot be stored still serves this lookup; the next one re-mints.
        }
        return token.accessToken
    }

    /**
     * The song's Spotify track URI through the web search API: the first result whose folded name and artist both
     * compare equal, the same loose match the desktop's resolver applies.
     */
    private suspend fun spotifyTrackUri(
        token: String,
        videoId: String,
        songTitle: String?,
        artistName: String?,
        check: (CanvasHealthProvider) -> Unit,
    ): String? {
        check(CanvasHealthProvider.SPOTIFY)
        if (songTitle.isNullOrBlank() || artistName.isNullOrBlank()) return null
        val body = try {
            val response = http.get("https://api.spotify.com/v1/search") {
                header("Authorization", "Bearer $token")
                header("Accept", "application/json")
                parameter("q", "$artistName $songTitle")
                parameter("type", "track")
                parameter("limit", 5)
                timeout { requestTimeoutMillis = 15_000L; connectTimeoutMillis = 10_000L }
            }
            if (!response.status.isSuccess()) return null
            runCatching { json.parseToJsonElement(response.bodyAsText()) as? JsonObject }.getOrNull()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val items = body?.get("tracks")?.jsonObject?.get("items")?.jsonArray ?: return null
        val found = items.mapNotNull { it as? JsonObject }.firstOrNull { track ->
            val name = track["name"]?.jsonPrimitive?.contentOrNull ?: return@firstOrNull false
            val artists = track["artists"]?.jsonArray ?: JsonArray(emptyList())
            comparableSongTitle(name, songTitle) && artists.any { artist ->
                val trackArtist = (artist as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                trackArtist != null && comparableSongTitle(trackArtist, artistName)
            }
        }
        return found?.get("id")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?.let { "spotify:track:$it" }
    }

    /**
     * The community resolver response: the canvas fields sit at the top level or under a `data`/`result`
     * envelope, named `url`, `canvas_url`, `video_url` or `canvas`.
     */
    private fun spotifyParseCanvas(body: JsonObject, videoId: String): IosCanvasResult? {
        val payload = body["data"]?.jsonObject ?: body["result"]?.jsonObject ?: body
        val videoUrl = payload["url"]?.jsonPrimitive?.contentOrNull
            ?: payload["canvas_url"]?.jsonPrimitive?.contentOrNull
            ?: payload["video_url"]?.jsonPrimitive?.contentOrNull
            ?: payload["canvas"]?.jsonPrimitive?.contentOrNull
            ?: return null
        if (videoUrl.isBlank()) return null
        return IosCanvasResult(
            provider = CanvasHealthProvider.SPOTIFY,
            name = payload["song"]?.jsonPrimitive?.contentOrNull
                ?: payload["name"]?.jsonPrimitive?.contentOrNull
                ?: payload["title"]?.jsonPrimitive?.contentOrNull
                ?: payload["track"]?.jsonPrimitive?.contentOrNull,
            artist = payload["artist"]?.jsonPrimitive?.contentOrNull
                ?: payload["artists"]?.jsonPrimitive?.contentOrNull
                ?: payload["author"]?.jsonPrimitive?.contentOrNull,
            albumId = "yt:$videoId",
            videoUrl = videoUrl,
            videoUrlVertical = videoUrl,
        )
    }

    private companion object {
        const val BETTER_LYRICS_URL = "https://artwork.boidu.dev/"
        const val BETTER_LYRICS_TTL_MS = 60_000L
        const val BETTER_LYRICS_CACHE_MAX = 128

        const val APPLE_MUSIC_WEB_HOME = "https://music.apple.com/"
        const val AMP_BASE_URL = "https://amp-api.music.apple.com"
        const val APPLE_TTL_MS = 24L * 60L * 60L * 1000L
        const val APPLE_TOKEN_REFRESH_MARGIN_SEC = 24L * 60L * 60L
        const val APPLE_TOKEN_REFRESH_THROTTLE_MS = 60_000L
        const val APPLE_MUSIC_WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/145.0.0.0 Safari/537.36"
        val APPLE_EDITION_WORDS = listOf("deluxe", "expanded", "remastered", "remix", "version", "edit", "mix", "bonus")
        val APPLE_ALBUM_BLACKLIST = listOf(
            "playlist", "set list", "essentials", "dj mix", "mixed", "apple music", "today's hits", "session",
        )

        const val TIDAL_SEARCH_URL = "https://api.tidal.com/v1/search"
        const val TIDAL_EMBED_TOKEN_SETTING = "ArchiveTuneTidalCanvasEmbedToken"
        val TIDAL_COVER_ID = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

        const val SPOTIFY_CANVAZ_URL = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"
        const val SPOTIFY_RESOLVER_URL = "https://mlc-ytify.kouzu.in/api/canvas"
        const val SPOTIFY_TTL_MS = 60L * 60L * 1000L
        const val SPOTIFY_TOKEN_EXPIRY_GRACE_MS = 60_000L
    }
}

/** Minimal protobuf codec for Spotify's Canvas endpoint, ported from the desktop `SpotifyCanvazProtocol`. */
internal object IosCanvazProtocol {
    private const val WIRE_VARINT = 0
    private const val WIRE_64BIT = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_START_GROUP = 3
    private const val WIRE_END_GROUP = 4
    private const val WIRE_32BIT = 5

    private const val FIELD_ENTITIES = 1
    private const val FIELD_ENTITY_URI = 1
    private const val FIELD_CANVAZ_URL = 2
    private const val FIELD_CANVAZ_ENTITY_URI = 5

    data class CanvazEntry(val entityUri: String?, val url: String?)

    fun encodeRequest(trackUris: List<String>): ByteArray {
        val out = ArrayList<Byte>()
        for (uri in trackUris) {
            if (uri.isBlank()) continue
            val entity = ArrayList<Byte>()
            writeStringField(entity, FIELD_ENTITY_URI, uri)
            writeBytesField(out, FIELD_ENTITIES, entity.toByteArray())
        }
        return out.toByteArray()
    }

    /** A truncated or unexpected body reads as "no canvas", never as an error — a missing canvas is common. */
    fun decodeResponse(bytes: ByteArray): List<CanvazEntry> {
        val reader = Reader(bytes)
        val entries = mutableListOf<CanvazEntry>()
        while (reader.hasRemaining()) {
            val tag = reader.readTag() ?: break
            if (tag.field == FIELD_ENTITIES && tag.wire == WIRE_LENGTH_DELIMITED) {
                val payload = reader.readLengthDelimited() ?: break
                entries += decodeCanvaz(payload)
            } else if (!reader.skip(tag.wire)) {
                break
            }
        }
        return entries
    }

    private fun decodeCanvaz(bytes: ByteArray): CanvazEntry {
        val reader = Reader(bytes)
        var url: String? = null
        var entityUri: String? = null
        while (reader.hasRemaining()) {
            val tag = reader.readTag() ?: break
            if (tag.wire == WIRE_LENGTH_DELIMITED) {
                val payload = reader.readLengthDelimited() ?: break
                when (tag.field) {
                    FIELD_CANVAZ_URL -> url = payload.decodeToString()
                    FIELD_CANVAZ_ENTITY_URI -> entityUri = payload.decodeToString()
                    else -> Unit
                }
            } else if (!reader.skip(tag.wire)) {
                break
            }
        }
        return CanvazEntry(entityUri = entityUri, url = url)
    }

    private fun writeStringField(out: MutableList<Byte>, field: Int, value: String) =
        writeBytesField(out, field, value.encodeToByteArray())

    private fun writeBytesField(out: MutableList<Byte>, field: Int, value: ByteArray) {
        writeVarint(out, (field.toLong() shl 3) or WIRE_LENGTH_DELIMITED.toLong())
        writeVarint(out, value.size.toLong())
        out.addAll(value.asList())
    }

    private fun writeVarint(out: MutableList<Byte>, value: Long) {
        var remaining = value
        while (true) {
            val chunk = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            if (remaining == 0L) {
                out.add(chunk.toByte())
                return
            }
            out.add((chunk or 0x80).toByte())
        }
    }

    private data class Tag(val field: Int, val wire: Int)

    private class Reader(private val bytes: ByteArray) {
        private var position = 0

        fun hasRemaining(): Boolean = position < bytes.size

        fun readTag(): Tag? {
            val raw = readVarint() ?: return null
            val field = (raw ushr 3).toInt()
            val wire = (raw and 0x07).toInt()
            if (field <= 0) return null
            return Tag(field = field, wire = wire)
        }

        fun readVarint(): Long? {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (position >= bytes.size) return null
                val byte = bytes[position++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
            return null
        }

        fun readLengthDelimited(): ByteArray? {
            val length = readVarint()?.toInt() ?: return null
            if (length < 0 || position + length > bytes.size) return null
            val slice = bytes.copyOfRange(position, position + length)
            position += length
            return slice
        }

        fun skip(wire: Int): Boolean = when (wire) {
            WIRE_VARINT -> readVarint() != null
            WIRE_64BIT -> advance(8)
            WIRE_LENGTH_DELIMITED -> readLengthDelimited() != null
            WIRE_32BIT -> advance(4)
            WIRE_START_GROUP, WIRE_END_GROUP -> false
            else -> false
        }

        private fun advance(count: Int): Boolean {
            if (position + count > bytes.size) return false
            position += count
            return true
        }
    }
}

/** The song-identity rule every catalogue answer passes before it counts: same folded title and primary artist. */
private fun IosCanvasResult.matchesSongIdentity(song: String, artist: String): Boolean {
    val requestedSong = song.toCanvasSongIdentity()
    val requestedArtist = artist.toCanvasPrimaryArtistIdentity()
    val resolvedSong = name?.toCanvasSongIdentity().orEmpty()
    val resolvedArtist = this.artist?.toCanvasPrimaryArtistIdentity().orEmpty()
    return requestedSong.isNotEmpty() && requestedArtist.isNotEmpty() &&
        resolvedSong == requestedSong && resolvedArtist == requestedArtist
}

private fun String.toCanvasSongIdentity(): String =
    replace(CanvasFeaturingQualifierRegex, "")
        .replace(CanvasPresentationQualifierRegex, "")
        .toCanvasIdentity()

private fun String.toCanvasPrimaryArtistIdentity(): String =
    split(CanvasArtistSeparatorRegex, limit = 2).firstOrNull().orEmpty().toCanvasIdentity()

/**
 * The NFKD fold the desktop models run through `java.text.Normalizer`: NSString's compatibility decomposition,
 * combining marks dropped by category, lowercased, non letter/digit runs folded to one space.
 */
private fun String.toCanvasIdentity(): String {
    val decomposed = (this as NSString).decomposedStringWithCompatibilityMapping
    val out = StringBuilder(decomposed.length)
    var pendingSpace = false
    for (char in decomposed) {
        when (char.category) {
            CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK ->
                continue
            else -> Unit
        }
        if (char.isLetterOrDigit()) {
            if (pendingSpace && out.isNotEmpty()) out.append(' ')
            pendingSpace = false
            out.append(char.lowercaseChar())
        } else {
            pendingSpace = true
        }
    }
    return out.toString().trim()
}

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private val CanvasFeaturingQualifierRegex =
    Regex("""\s*[\[(](?:feat\.?|ft\.?|featuring|with)\s+[^\])]*[\])]""", RegexOption.IGNORE_CASE)

private val CanvasPresentationQualifierRegex =
    Regex(
        """\s*(?:[-–—:]\s*)?[\[(]?(?:official\s+)?(?:music\s+)?""" +
            """(?:lyric\s+video|video|audio|lyrics?|visualizer|visualiser)[\])]?\s*$""",
        RegexOption.IGNORE_CASE,
    )

private val CanvasArtistSeparatorRegex =
    Regex("""(?:\s*,\s*|\s*&\s*|\s+x\s+|\s+(?:feat\.?|ft\.?|featuring|with)\s+)""", RegexOption.IGNORE_CASE)

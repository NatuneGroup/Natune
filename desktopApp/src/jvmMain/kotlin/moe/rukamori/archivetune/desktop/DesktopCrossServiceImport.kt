package moe.rukamori.archivetune.desktop

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import moe.rukamori.archivetune.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_UGC
import moe.rukamori.archivetune.innertube.utils.completed
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.LibraryMutation
import moe.rukamori.archivetune.shared.domain.settings.TidalAccessTokenKey
import moe.rukamori.archivetune.shared.domain.settings.TidalCountryCodeKey
import moe.rukamori.archivetune.shared.domain.settings.YtmSyncKey
import moe.rukamori.archivetune.shared.domain.settings.qobuzTokens
import moe.rukamori.archivetune.shared.domain.settings.youTubeSignedIn
import moe.rukamori.archivetune.spotify.Spotify
import moe.rukamori.archivetune.spotify.SpotifyMapper

/** Desktop port of Android's cross-service playlist importer, including its unofficial page/API fallbacks. */
internal class DesktopCrossServiceImport(
    private val graph: DesktopSessionGraph,
    private val spotify: DesktopSpotifyAccounts,
) {
    data class ForeignTrack(val title: String, val artist: String?, val album: String? = null, val durationMs: Long? = null)
    data class ResolvedImport(val source: ImportSource, val sourcePlaylistId: String, val title: String, val thumbnailUrl: String?, val tracks: List<ForeignTrack>)
    data class ImportResult(val playlistId: String, val total: Int, val imported: Int)
    data class Credentials(val tidalAccessToken: String? = null, val tidalCountryCode: String = "US", val qobuzAppId: String? = null, val qobuzAuthToken: String? = null)

    enum class ImportSource(val displayName: String) {
        YOUTUBE_MUSIC("YouTube Music"), SPOTIFY("Spotify"), APPLE_MUSIC("Apple Music"), AMAZON_MUSIC("Amazon Music"), TIDAL("Tidal"), QOBUZ("Qobuz"), DEEZER("Deezer"), UNKNOWN("Unknown")
    }

    sealed interface ImportProgress {
        data class Fetching(val url: String) : ImportProgress
        data class Resolving(val processed: Int, val total: Int) : ImportProgress
        data class Done(val result: ImportResult) : ImportProgress
        data class Failed(val message: String) : ImportProgress
    }

    fun detectSource(url: String): ImportSource = when {
        url.contains("music.youtube.com") || url.contains("youtube.com/playlist") -> ImportSource.YOUTUBE_MUSIC
        url.contains("spotify.com") || url.startsWith("spotify:") -> ImportSource.SPOTIFY
        url.contains("music.apple.com") -> ImportSource.APPLE_MUSIC
        url.contains("music.amazon.com") -> ImportSource.AMAZON_MUSIC
        url.contains("tidal.com") -> ImportSource.TIDAL
        url.contains("qobuz.com") -> ImportSource.QOBUZ
        url.contains("deezer.com") -> ImportSource.DEEZER
        else -> ImportSource.UNKNOWN
    }

    suspend fun import(url: String, onProgress: (ImportProgress) -> Unit): ImportResult = withContext(Dispatchers.IO) {
        onProgress(ImportProgress.Fetching(url))
        val credentials = loadCredentials()
        val source = detectSource(url)
        val resolved = fetchPlaylist(url, credentials).getOrThrow()
        val songs = if (source == ImportSource.YOUTUBE_MUSIC) {
            fetchYouTubePlaylistSongs(resolved.sourcePlaylistId)
        } else {
            resolveToYouTubeMusic(resolved.tracks) { done, total -> onProgress(ImportProgress.Resolving(done, total)) }
        }
        val playlistName = resolved.title.ifBlank { "${resolved.source.displayName} Import" }
        val syntheticBrowseId = "import:${resolved.source.name}:${resolved.sourcePlaylistId}"
        val playlistId = deterministicPlaylistId(syntheticBrowseId)
        val occurrences = songs.map { song ->
            ContentOccurrence("import:" + UUID.randomUUID().toString().replace("-", "").take(24), song)
        }
        val exists = runCatching { graph.library.item(ContentRef("local", ContentKind.PLAYLIST, playlistId)) }.isSuccess
        if (exists) {
            graph.library.mutate(LibraryMutation.RenamePlaylist(playlistId, playlistName))
            graph.library.appendImported(playlistId, occurrences, emptySet())
        } else {
            graph.library.createImportedPlaylist(playlistId, playlistName, occurrences, emptySet())
        }
        syncRemotePlaylist(playlistName, songs.map { it.ref.id })
        ImportResult(playlistId, if (source == ImportSource.YOUTUBE_MUSIC) songs.size else resolved.tracks.size, songs.size).also { onProgress(ImportProgress.Done(it)) }
    }

    private suspend fun loadCredentials(): Credentials {
        spotify.restoreCatalogSession()
        val prefs = graph.settings.snapshots.value
        val personalTidal = prefs?.get(TidalAccessTokenKey).orEmpty().ifBlank { null }
        val poolTidal = graph.playbackPool.tidalAccounts().firstOrNull()
        val personalQobuz = prefs?.qobuzTokens()?.firstOrNull { it.isComplete() }
        val poolQobuz = if (personalQobuz == null) graph.playbackPool.qobuzAccounts().firstOrNull() else null
        return Credentials(
            tidalAccessToken = personalTidal ?: poolTidal?.token,
            tidalCountryCode = prefs?.get(TidalCountryCodeKey).orEmpty().ifBlank { poolTidal?.countryCode } ?: "US",
            qobuzAppId = personalQobuz?.appId ?: poolQobuz?.appId,
            qobuzAuthToken = personalQobuz?.token ?: poolQobuz?.token,
        )
    }

    private suspend fun syncRemotePlaylist(title: String, songIds: List<String>) {
        val prefs = graph.settings.snapshots.value
        if (prefs?.youTubeSignedIn() != true || prefs[YtmSyncKey] != true || songIds.isEmpty()) return
        runCatching { YouTube.createPlaylist(title, songIds).getOrThrow() }
    }

    private suspend fun fetchPlaylist(url: String, credentials: Credentials): Result<ResolvedImport> = runCatching {
        when (val source = detectSource(url)) {
            ImportSource.YOUTUBE_MUSIC -> {
                val playlistId = extractQuery(url, "list") ?: error("Missing 'list' query parameter in YouTube URL")
                val page = YouTube.playlist(playlistId).getOrNull() ?: error("Could not fetch YouTube playlist (it may be private)")
                ResolvedImport(source, playlistId, page.playlist.title, page.playlist.thumbnail, page.songs.map {
                    ForeignTrack(it.title, it.artists.firstOrNull()?.name, it.album?.name, it.duration?.toLong()?.times(1000L))
                })
            }
            ImportSource.SPOTIFY -> fetchSpotifyPlaylist(url)
            ImportSource.APPLE_MUSIC -> fetchAppleMusicPlaylist(url)
            ImportSource.AMAZON_MUSIC -> fetchAmazonMusicPlaylist(url)
            ImportSource.TIDAL -> fetchTidalPlaylist(url, credentials)
            ImportSource.QOBUZ -> fetchQobuzPlaylist(url, credentials)
            ImportSource.DEEZER -> fetchDeezerPlaylist(url)
            ImportSource.UNKNOWN -> error("Unrecognized URL — supported: YouTube Music, Spotify, Apple Music, Amazon Music, Tidal, Qobuz, Deezer")
        }
    }

    private suspend fun fetchSpotifyPlaylist(url: String): ResolvedImport {
        val id = extractSpotifyPlaylistId(url) ?: error("Couldn't extract Spotify playlist id from URL")
        if (Spotify.isAuthenticated()) {
            runCatching { fetchSpotifyPlaylistViaApi(id) }.getOrNull()?.takeIf { it.tracks.isNotEmpty() }?.let { return it }
        }
        return fetchSpotifyPlaylistViaEmbed(id)
    }

    private suspend fun fetchSpotifyPlaylistViaApi(id: String): ResolvedImport {
        val meta = spotify.catalogCall { Spotify.playlist(id).getOrNull() }
        val tracks = mutableListOf<ForeignTrack>()
        var offset = 0
        while (true) {
            val page = spotify.catalogCall { Spotify.playlistTracks(id, SPOTIFY_PAGE_SIZE, offset).getOrNull() } ?: break
            tracks += page.items.mapNotNull { item ->
                val track = item.track ?: return@mapNotNull null
                ForeignTrack(track.name.ifBlank { return@mapNotNull null }, track.artists.firstOrNull()?.name, track.album?.name,
                    track.durationMs.toLong().takeIf { it > 0 })
            }
            offset += SPOTIFY_PAGE_SIZE
            if (page.items.isEmpty() || offset >= page.total || tracks.size >= SPOTIFY_MAX_TRACKS) break
        }
        return ResolvedImport(ImportSource.SPOTIFY, id, meta?.name?.ifBlank { null } ?: "Spotify Playlist", meta?.images?.firstOrNull()?.url?.ifBlank { null }, tracks)
    }

    private suspend fun fetchSpotifyPlaylistViaEmbed(id: String): ResolvedImport {
        val entity = jsonObject(extractScriptJson(fetchText("https://open.spotify.com/embed/playlist/$id"), "__NEXT_DATA__")
            ?.let(json::parseToJsonElement))
            ?.obj("props")?.obj("pageProps")?.obj("state")?.obj("data")?.obj("entity")
            ?: error("Spotify playlist is private or unavailable")
        val tracks = entity.array("trackList").orEmpty().mapNotNull {
            val obj = it as? JsonObject ?: return@mapNotNull null
            ForeignTrack(obj.string("title") ?: return@mapNotNull null, obj.string("subtitle"), durationMs = obj.long("duration").takeIf { d -> d > 0 })
        }
        val cover = entity.obj("coverArt")?.array("sources")?.firstOrNull()?.let { (it as? JsonObject)?.string("url") }
        return ResolvedImport(ImportSource.SPOTIFY, id, entity.string("name") ?: "Spotify Playlist", cover, tracks)
    }

    private suspend fun fetchAppleMusicPlaylist(url: String): ResolvedImport {
        val html = fetchText(url)
        val tracks = extractScriptJson(html, "serialized-server-data")?.let { raw ->
            mutableListOf<ForeignTrack>().also { list -> runCatching { collectAppleTracks(json.parseToJsonElement(raw), list) } }.takeIf { it.isNotEmpty() }?.distinctBy { t -> t.title to t.artist }
        } ?: parseAppleTracksFallback(html)
        val title = Regex("<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let(::unescapeJson)
            ?: Regex("\"@type\":\"MusicPlaylist\"[^}]*?\"name\":\"([^\"]+)\"")
                .find(html)?.groupValues?.get(1)?.let(::unescapeJson)
            ?: Regex("\\{\"@type\":\"MusicPlaylist\",\"name\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let(::unescapeJson)
            ?: "Apple Music Playlist"
        return ResolvedImport(ImportSource.APPLE_MUSIC, extractId(Regex("playlist/[^/]+/pl\\.([a-zA-Z0-9.\\-]+)"), url, "pl.") ?: url, title, null, tracks)
    }

    private fun parseAppleTracksFallback(html: String): List<ForeignTrack> {
        val tracks = mutableListOf<ForeignTrack>()
        Regex("\\{\"name\":\"([^\"]{2,200})\",\"artistName\":\"([^\"]{2,200})\"").findAll(html).forEach {
            tracks += ForeignTrack(unescapeJson(it.groupValues[1]), unescapeJson(it.groupValues[2]))
        }
        if (tracks.isEmpty()) {
            Regex("\\{\"@type\":\"MusicRecording\",\"name\":\"([^\"]+)\"[^}]*?(?:\"byArtist\":\\{\"@type\":\"MusicGroup\",\"name\":\"([^\"]+)\"\\})?")
                .findAll(html).forEach { tracks += ForeignTrack(unescapeJson(it.groupValues[1]), it.groupValues.getOrNull(2)?.takeIf(String::isNotBlank)?.let(::unescapeJson)) }
        }
        if (tracks.isEmpty()) Regex("\"name\":\"([^\"]{2,80})\"[^}]{0,400}?\"artistName\":\"([^\"]+)\"").findAll(html).forEach {
            tracks += ForeignTrack(unescapeJson(it.groupValues[1]), unescapeJson(it.groupValues[2]))
        }
        return tracks.distinctBy { it.title to it.artist }
    }

    private fun collectAppleTracks(node: JsonElement?, into: MutableList<ForeignTrack>) {
        when (node) {
            is JsonObject -> {
                val title = node.string("title"); val artist = node.string("artistName")
                if (title != null && artist != null) into += ForeignTrack(title, artist, durationMs = node.long("durationInMillis").takeIf { it > 0 })
                node.values.forEach { collectAppleTracks(it, into) }
            }
            is JsonArray -> node.forEach { collectAppleTracks(it, into) }
            else -> Unit
        }
    }

    private suspend fun fetchAmazonMusicPlaylist(url: String): ResolvedImport {
        val html = fetchText(url)
        val tracks = (Regex("\\{\"title\":\"([^\"]{2,120})\",\"artist\":\"([^\"]+)\"").findAll(html).map {
            ForeignTrack(unescapeJson(it.groupValues[1]), unescapeJson(it.groupValues[2]))
        }.toList().ifEmpty {
            Regex("\\{\"trackName\":\"([^\"]{2,120})\",\"artistName\":\"([^\"]+)\"").findAll(html).map {
                ForeignTrack(unescapeJson(it.groupValues[1]), unescapeJson(it.groupValues[2]))
            }.toList()
        }).distinctBy { it.title to it.artist }
        val title = Regex("<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let(::unescapeJson)
            ?: Regex("<title>([^<]+)</title>").find(html)?.groupValues?.get(1)?.trim()
                ?.let { raw -> raw.substringBefore(" |").ifBlank { raw } }
            ?: "Amazon Music Playlist"
        return ResolvedImport(ImportSource.AMAZON_MUSIC, extractId(Regex("playlists/([A-Z0-9]+)"), url) ?: url, title, null, tracks)
    }

    private suspend fun fetchTidalPlaylist(url: String, credentials: Credentials): ResolvedImport {
        val id = extractId(Regex("playlist/([a-f0-9\\-]{20,40})"), url) ?: error("Couldn't extract Tidal playlist id from URL")
        val token = credentials.tidalAccessToken?.takeIf(String::isNotBlank) ?: error("Tidal import needs a signed-in Tidal account")
        val country = credentials.tidalCountryCode.ifBlank { "US" }
        val meta = runCatching { json.parseToJsonElement(fetchText("$TIDAL_API_BASE/playlists/$id?countryCode=$country", mapOf("Authorization" to "Bearer $token"))) as? JsonObject }.getOrNull()
        val tracks = mutableListOf<ForeignTrack>(); var offset = 0
        while (tracks.size < TIDAL_MAX_TRACKS) {
            val root = jsonObject(json.parseToJsonElement(fetchText("$TIDAL_API_BASE/playlists/$id/items?countryCode=$country&limit=$TIDAL_PAGE_SIZE&offset=$offset", mapOf("Authorization" to "Bearer $token")))) ?: break
            val items = root.array("items") ?: break; if (items.isEmpty()) break
            items.forEach { element -> val wrapper = element as? JsonObject ?: return@forEach; val item = wrapper.obj("item") ?: wrapper
                if (wrapper.string("type")?.let { it.isNotBlank() && it != "track" } == true) return@forEach
                tracks += ForeignTrack(item.string("title") ?: return@forEach,
                    item.obj("artist")?.string("name") ?: item.array("artists")?.firstOrNull()?.let { (it as? JsonObject)?.string("name") },
                    item.obj("album")?.string("title"), item.long("duration").takeIf { it > 0 }?.let { it * 1000L })
            }
            offset += TIDAL_PAGE_SIZE; if (offset >= root.long("totalNumberOfItems")) break
        }
        return ResolvedImport(ImportSource.TIDAL, id, meta?.string("title") ?: "Tidal Playlist", meta?.string("squareImage")?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/640x640.jpg" }, tracks)
    }

    private suspend fun fetchQobuzPlaylist(url: String, credentials: Credentials): ResolvedImport {
        val id = extractId(Regex("playlists?/(?:[^/?#]+/)*?(\\d{3,})"), url) ?: error("Couldn't extract Qobuz playlist id from URL")
        val appId = credentials.qobuzAppId?.takeIf(String::isNotBlank); val token = credentials.qobuzAuthToken?.takeIf(String::isNotBlank)
        if (appId == null || token == null) error("Qobuz import needs a Qobuz token")
        val tracks = mutableListOf<ForeignTrack>(); var title = "Qobuz Playlist"; var thumbnail: String? = null; var offset = 0
        while (tracks.size < QOBUZ_MAX_TRACKS) {
            val root = jsonObject(json.parseToJsonElement(fetchText("$QOBUZ_API_BASE/playlist/get?playlist_id=$id&extra=tracks&limit=$QOBUZ_PAGE_SIZE&offset=$offset&app_id=$appId", mapOf("X-App-Id" to appId, "X-User-Auth-Token" to token)))) ?: break
            if (offset == 0) { title = root.string("name") ?: title; thumbnail = root.obj("image")?.string("large") }
            val trackObj = root.obj("tracks") ?: break; val items = trackObj.array("items") ?: break; if (items.isEmpty()) break
            items.forEach { element -> val item = element as? JsonObject ?: return@forEach
                tracks += ForeignTrack(item.string("title") ?: return@forEach,
                    item.obj("performer")?.string("name") ?: item.obj("album")?.obj("artist")?.string("name"),
                    item.obj("album")?.string("title"), item.long("duration").takeIf { it > 0 }?.let { it * 1000L })
            }
            offset += QOBUZ_PAGE_SIZE; if (offset >= trackObj.long("total")) break
        }
        return ResolvedImport(ImportSource.QOBUZ, id, title, thumbnail, tracks)
    }

    private suspend fun fetchDeezerPlaylist(url: String): ResolvedImport {
        val id = extractId(Regex("playlist/(\\d+)"), url) ?: error("Couldn't extract Deezer playlist id from URL")
        val root = jsonObject(json.parseToJsonElement(fetchText("https://api.deezer.com/playlist/$id"))) ?: error("Deezer playlist is unavailable")
        val tracks = root.obj("tracks")?.array("data").orEmpty().mapNotNull {
            val obj = it as? JsonObject ?: return@mapNotNull null
            ForeignTrack(obj.string("title") ?: return@mapNotNull null, obj.obj("artist")?.string("name"))
        }
        return ResolvedImport(ImportSource.DEEZER, id, root.string("title") ?: "Deezer Playlist", root.string("picture_xl"), tracks)
    }

    private suspend fun fetchYouTubePlaylistSongs(playlistId: String): List<ContentItem> =
        YouTube.playlist(playlistId).completed().getOrNull()?.songs?.map(::contentItem).orEmpty()

    private suspend fun resolveToYouTubeMusic(tracks: List<ForeignTrack>, onProgress: (Int, Int) -> Unit): List<ContentItem> = coroutineScope {
        val results = ArrayList<ContentItem>(tracks.size); var completed = 0
        tracks.chunked(MAX_PARALLEL_SEARCHES).forEach { batch ->
            batch.map { track -> async {
                val term = listOfNotNull(track.artist?.takeIf(String::isNotBlank), track.title).joinToString(" ").ifBlank { return@async null }
                val search = YouTube.search(term, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                val best = search?.items.orEmpty().filterIsInstance<SongItem>().map { candidate ->
                    candidate to SpotifyMapper.matchScorePrecomputed(
                        SpotifyMapper.precompute(track.title, track.artist.orEmpty(), track.durationMs?.toInt() ?: 0),
                        candidate.title, candidate.artists.joinToString(" ") { it.name }, candidate.duration)
                }.filter { it.second >= IMPORT_MATCH_THRESHOLD }.maxByOrNull { it.second }?.first
                best?.let(::contentItem)
            } }.awaitAll().filterNotNullTo(results)
            completed += batch.size; onProgress(completed, tracks.size)
        }
        results
    }

    private fun contentItem(song: SongItem) = ContentItem(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, song.id), song.title,
        song.artists.joinToString(", ") { it.name }, song.thumbnail, song.duration?.toLong()?.times(1000L), song.explicit, song.album?.name,
        song.artists.mapNotNull { it.name.takeIf(String::isNotBlank)?.let { name -> ContentCredit(name, it.id?.takeIf(String::isNotBlank)) } }, song.album?.id?.takeIf(String::isNotBlank),
        song.endpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType in setOf(MUSIC_VIDEO_TYPE_OMV, MUSIC_VIDEO_TYPE_UGC))

    private suspend fun fetchText(url: String, headers: Map<String, String> = emptyMap()): String {
        val response = graph.http.get(url) {
            header("User-Agent", BROWSER_USER_AGENT); header("Accept", "text/html,application/json,application/xhtml+xml"); header("Accept-Language", "en-US,en;q=0.9")
            headers.forEach { (name, value) -> header(name, value) }
        }
        when (response.status.value) {
            in 200..299 -> return response.bodyAsText()
            401, 403 -> error("Not authorised (HTTP ${response.status.value}) — the account or token may have expired")
            404 -> error("Playlist not found (HTTP 404) — it may be private or the URL is wrong")
            429 -> error("Rate limited by the service (HTTP 429) — try again in a minute")
            else -> error("HTTP ${response.status.value} fetching $url")
        }
    }

    private fun deterministicPlaylistId(syntheticBrowseId: String): String =
        "LPimp" + MessageDigest.getInstance("SHA-256").digest(syntheticBrowseId.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    private fun extractSpotifyPlaylistId(url: String): String? = Regex("spotify:playlist:([A-Za-z0-9]+)").find(url)?.groupValues?.get(1)
        ?: Regex("playlist[/:]([A-Za-z0-9]{16,})").find(url)?.groupValues?.get(1)
    private fun extractId(regex: Regex, url: String, prefix: String = "") = regex.find(url)?.groupValues?.get(1)?.let { prefix + it }
    private fun extractQuery(url: String, key: String): String? = Regex("[?&]$key=([^&]+)").find(url)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
    private fun extractScriptJson(html: String, scriptId: String): String? = Pattern.compile("<script[^>]*id=\"$scriptId\"[^>]*>(.*?)</script>", Pattern.DOTALL).matcher(html).let { if (it.find()) it.group(1)?.trim()?.takeIf(String::isNotEmpty) else null }
    private fun jsonObject(element: JsonElement?) = element as? JsonObject
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.array(key: String) = this[key] as? JsonArray
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
    private fun unescapeJson(s: String): String = s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", " ").replace("\\/", "/").replace("&amp;", "&").replace("&#x27;", "'").replace("&#39;", "'").replace("&quot;", "\"").replace("&#x2F;", "/")

    private companion object {
        const val IMPORT_MATCH_THRESHOLD = 0.6; const val MAX_PARALLEL_SEARCHES = 6
        const val BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        const val TIDAL_API_BASE = "https://api.tidal.com/v1"; const val QOBUZ_API_BASE = "https://www.qobuz.com/api.json/0.2"
        const val SPOTIFY_PAGE_SIZE = 100; const val TIDAL_PAGE_SIZE = 100; const val QOBUZ_PAGE_SIZE = 500
        const val SPOTIFY_MAX_TRACKS = 2000; const val TIDAL_MAX_TRACKS = 2000; const val QOBUZ_MAX_TRACKS = 2000
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

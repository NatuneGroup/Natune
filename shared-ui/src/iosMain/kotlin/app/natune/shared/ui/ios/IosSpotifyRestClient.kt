package app.natune.shared.ui.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.natune.shared.data.playlistimport.YouTubePlaylistImportSearch
import app.natune.shared.domain.library.DefaultLibraryFilterOrder
import app.natune.shared.domain.library.LibraryDefaults
import app.natune.shared.domain.library.LibraryFilter
import app.natune.shared.domain.library.LibrarySource
import app.natune.shared.domain.library.toLibraryFilterOrder
import app.natune.shared.domain.queue.FinitePlaylistCompletion
import app.natune.shared.domain.queue.IncompleteFinitePlaylistException
import app.natune.shared.domain.repository.ContentCredit
import app.natune.shared.domain.repository.ContentItem
import app.natune.shared.domain.repository.ContentKind
import app.natune.shared.domain.repository.ContentOccurrence
import app.natune.shared.domain.repository.ContentProvider
import app.natune.shared.domain.repository.ContentRef
import app.natune.shared.domain.repository.ContentRequest
import app.natune.shared.domain.repository.ContentSearchFilter
import app.natune.shared.domain.repository.ContentSurface
import app.natune.shared.domain.repository.HistoryOrigin
import app.natune.shared.domain.repository.QueueStart
import app.natune.shared.domain.repository.hasBlockedArtist
import app.natune.shared.domain.settings.ChipSortTypeKey
import app.natune.shared.domain.settings.HideAiMixKey
import app.natune.shared.domain.settings.HideCachedCardKey
import app.natune.shared.domain.settings.HideLikedSongsCardKey
import app.natune.shared.domain.settings.HideLocalFilesCardKey
import app.natune.shared.domain.settings.HideOfflineCardKey
import app.natune.shared.domain.settings.HideTop50CardKey
import app.natune.shared.domain.settings.LibraryChipOrderKey
import app.natune.shared.domain.settings.LibrarySourceKey
import app.natune.shared.domain.settings.PreferenceSnapshot
import app.natune.shared.domain.settings.ShowSpotifyPlaylistsKey
import app.natune.shared.domain.settings.SpotifySpDcKey
import app.natune.shared.domain.settings.TopSize
import app.natune.shared.domain.settings.toEnum
import app.natune.shared.ui.IosHost
import app.natune.shared.ui.host.hostCatalogDetailPage
import app.natune.shared.ui.host.hostLibraryPage
import app.natune.shared.ui.host.queuedEntry
import app.natune.shared.ui.navigation.ArchiveDestination
import app.natune.shared.ui.navigation.ArchiveGraphPage
import app.natune.shared.ui.navigation.ArchiveRoute
import app.natune.shared.ui.navigation.ArchiveRoutes
import app.natune.shared.ui.resources.Res
import app.natune.shared.ui.resources.ios_library_source_spotify
import app.natune.shared.ui.resources.ios_library_source_ytm
import app.natune.shared.ui.resources.ios_library_top_mixes_unavailable
import app.natune.shared.ui.resources.ios_spotify_liked_songs
import app.natune.shared.ui.resources.ios_spotify_your_artists
import app.natune.shared.ui.resources.ios_spotify_your_playlists
import app.natune.shared.ui.screens.ContentActions
import app.natune.shared.ui.screens.DetailActions
import app.natune.shared.ui.screens.DetailKind
import app.natune.shared.ui.screens.LibraryMixHeaderActions
import app.natune.shared.ui.screens.LibraryMixHeaderBinding
import app.natune.shared.ui.screens.LibraryMixHeaderUi
import app.natune.shared.ui.screens.LibraryMixRailUi
import app.natune.shared.ui.screens.LibraryShortcutsUi
import app.natune.shared.ui.screens.LibraryTopMixesUi
import app.natune.shared.ui.screens.PagedContentState
import org.jetbrains.compose.resources.stringResource

internal data class IosSpotifyAlbumDetail(val album: ContentItem, val tracks: List<ContentOccurrence>,
    val artists: List<ContentItem>, val year: Int?)

internal class IosSpotifyRestClient(private val graph: IosSessionGraph) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getJson(url: String): JsonObject = withContext(Dispatchers.Default) {
        val origin = io.ktor.http.Url(url)
        require(origin.protocol.name == "https" && origin.host == "api.spotify.com" &&
            origin.user.isNullOrEmpty() && origin.password.isNullOrEmpty())
        val firstToken = iosValidSpotifyCatalogToken(graph)
        val firstResponse = graph.http.get(url) {
            header(HttpHeaders.Authorization, "Bearer $firstToken")
        }
        val response = if (firstResponse.status == HttpStatusCode.Unauthorized) {
            val freshToken = iosValidSpotifyCatalogToken(graph, forceRefresh = true)
            graph.http.get(url) {
                header(HttpHeaders.Authorization, "Bearer $freshToken")
            }
        } else {
            firstResponse
        }
        require(response.status.isSuccess()) { "Spotify HTTP ${response.status.value}" }
        json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun fetchPlaylists(): List<ContentOccurrence> {
        val out = mutableListOf<ContentOccurrence>()
        var nextUrl: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
        var pages = 0
        while (nextUrl != null && pages < FinitePlaylistCompletion.MAX_REQUESTS) {
            pages++
            val root = getJson(nextUrl)
            val items = root["items"] as? JsonArray ?: throw IncompleteFinitePlaylistException()
            items.forEach { el ->
                val obj = el as? JsonObject ?: return@forEach
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val owner = obj["owner"]?.jsonObject?.get("display_name")?.jsonPrimitive?.contentOrNull.orEmpty()
                val img = (obj["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                val idx = out.size
                out += ContentOccurrence(
                    "spotify:library:playlists:$idx:$id",
                    ContentItem(ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.PLAYLIST, id), name, owner, img),
                )
            }
            nextUrl = root["next"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
            if (nextUrl != null && items.isEmpty()) throw IncompleteFinitePlaylistException()
        }
        if (nextUrl != null) throw IncompleteFinitePlaylistException()
        return out
    }

    suspend fun fetchLikedTracks(): List<ContentOccurrence> {
        val out = mutableListOf<ContentOccurrence>()
        var nextUrl: String? = "https://api.spotify.com/v1/me/tracks?limit=50"
        var pages = 0
        while (nextUrl != null && pages < FinitePlaylistCompletion.MAX_REQUESTS) {
            pages++
            val root = getJson(nextUrl)
            val items = root["items"] as? JsonArray ?: throw IncompleteFinitePlaylistException()
            items.forEach { el ->
                val track = (el as? JsonObject)?.get("track") as? JsonObject ?: return@forEach
                parseTrackOccurrence("spotify:library:songs:${out.size}", track)?.let { out += it }
            }
            nextUrl = root["next"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
            if (nextUrl != null && items.isEmpty()) throw IncompleteFinitePlaylistException()
        }
        if (nextUrl != null) throw IncompleteFinitePlaylistException()
        return out
    }

    suspend fun fetchArtists(): List<ContentOccurrence> {
        val out = mutableListOf<ContentOccurrence>()
        var nextUrl: String? = "https://api.spotify.com/v1/me/following?type=artist&limit=50"
        var pages = 0
        while (nextUrl != null && pages < FinitePlaylistCompletion.MAX_REQUESTS) {
            pages++
            val artistsObj = getJson(nextUrl)["artists"]?.jsonObject ?: throw IncompleteFinitePlaylistException()
            val items = artistsObj["items"] as? JsonArray ?: throw IncompleteFinitePlaylistException()
            items.forEach { el ->
                val obj = el as? JsonObject ?: return@forEach
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val img = (obj["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                val idx = out.size
                out += ContentOccurrence(
                    "spotify:library:artists:$idx:$id",
                    ContentItem(ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.ARTIST, id), name, "", img),
                )
            }
            nextUrl = artistsObj["next"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
            if (nextUrl != null && items.isEmpty()) throw IncompleteFinitePlaylistException()
        }
        if (nextUrl != null) throw IncompleteFinitePlaylistException()
        return out
    }

    suspend fun fetchAlbums(): List<ContentOccurrence> {
        val out = mutableListOf<ContentOccurrence>()
        var nextUrl: String? = "https://api.spotify.com/v1/me/albums?limit=50"
        var pages = 0
        while (nextUrl != null && pages < FinitePlaylistCompletion.MAX_REQUESTS) {
            pages++
            val root = getJson(nextUrl)
            val items = root["items"] as? JsonArray ?: throw IncompleteFinitePlaylistException()
            items.forEach { el ->
                val album = (el as? JsonObject)?.get("album") as? JsonObject ?: return@forEach
                val id = album["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val name = album["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val artists = (album["artists"] as? JsonArray)?.mapNotNull {
                    it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                }?.joinToString(", ").orEmpty()
                val img = (album["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                val idx = out.size
                out += ContentOccurrence(
                    "spotify:library:albums:$idx:$id",
                    ContentItem(ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.ALBUM, id), name, artists, img),
                )
            }
            nextUrl = root["next"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
            if (nextUrl != null && items.isEmpty()) throw IncompleteFinitePlaylistException()
        }
        if (nextUrl != null) throw IncompleteFinitePlaylistException()
        return out
    }

    suspend fun fetchPlaylistDetail(playlistId: String, defaultSubtitle: String): Pair<ContentItem, List<ContentOccurrence>> {
        val root = getJson("https://api.spotify.com/v1/playlists/$playlistId")
        val title = root["name"]?.jsonPrimitive?.contentOrNull ?: playlistId
        val owner = root["owner"]?.jsonObject?.get("display_name")?.jsonPrimitive?.contentOrNull ?: defaultSubtitle
        val img = (root["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        val tracks = mutableListOf<ContentOccurrence>()
        val tracksObj = root["tracks"]?.jsonObject ?: throw IncompleteFinitePlaylistException()
        val firstItems = tracksObj["items"] as? JsonArray ?: throw IncompleteFinitePlaylistException()
        firstItems.forEach { el ->
            val track = (el as? JsonObject)?.get("track") as? JsonObject ?: return@forEach
            parseTrackOccurrence("spotify:playlist:$playlistId:${tracks.size}", track)?.let { tracks += it }
        }
        var nextUrl = tracksObj["next"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        if (nextUrl != null && firstItems.isEmpty()) throw IncompleteFinitePlaylistException()
        var pages = 1
        while (nextUrl != null && pages < FinitePlaylistCompletion.MAX_REQUESTS) {
            pages++
            val pageObj = getJson(nextUrl)
            val pageItems = pageObj["items"] as? JsonArray ?: throw IncompleteFinitePlaylistException()
            if (pageItems.isEmpty()) throw IncompleteFinitePlaylistException()
            pageItems.forEach { el ->
                val track = (el as? JsonObject)?.get("track") as? JsonObject ?: return@forEach
                parseTrackOccurrence("spotify:playlist:$playlistId:${tracks.size}", track)?.let { tracks += it }
            }
            nextUrl = pageObj["next"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        }
        if (nextUrl != null) throw IncompleteFinitePlaylistException()
        return ContentItem(
            ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.PLAYLIST, playlistId),
            title = title,
            subtitle = owner,
            artworkUri = img,
        ) to tracks
    }

    suspend fun fetchAlbumDetail(albumId: String, defaultSubtitle: String): IosSpotifyAlbumDetail = withTimeout(FinitePlaylistCompletion.TIMEOUT_MS) {
        require(albumId.matches(Regex("[A-Za-z0-9]{1,128}")))
        val root = getJson("https://api.spotify.com/v1/albums/$albumId")
        val title = root["name"]?.jsonPrimitive?.contentOrNull ?: albumId
        val artists = (root["artists"] as? JsonArray)?.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
        }?.joinToString(", ") ?: defaultSubtitle
        val img = (root["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        val tracks = mutableListOf<ContentOccurrence>()
        val tracksObj = root["tracks"]?.jsonObject ?: throw IncompleteFinitePlaylistException()
        val firstItems = tracksObj["items"] as? JsonArray ?: throw IncompleteFinitePlaylistException()
        firstItems.forEach { el ->
            val track = el as? JsonObject ?: return@forEach
            parseTrackOccurrence("spotify:album:$albumId:${tracks.size}", track, img, root)?.let { tracks += it }
        }
        var nextUrl = tracksObj["next"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        if (nextUrl != null && firstItems.isEmpty()) throw IncompleteFinitePlaylistException()
        val consumed = mutableSetOf<String>()
        var pages = 1
        while (nextUrl != null && pages < FinitePlaylistCompletion.MAX_REQUESTS) {
            pages++
            if (!consumed.add(nextUrl)) throw IncompleteFinitePlaylistException()
            val pageObj = getJson(nextUrl)
            val pageItems = pageObj["items"] as? JsonArray ?: throw IncompleteFinitePlaylistException()
            if (pageItems.isEmpty()) throw IncompleteFinitePlaylistException()
            pageItems.forEach { el ->
                val track = el as? JsonObject ?: return@forEach
                parseTrackOccurrence("spotify:album:$albumId:${tracks.size}", track, img, root)?.let { tracks += it }
            }
            nextUrl = pageObj["next"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        }
        val expected = tracksObj["total"]?.jsonPrimitive?.intOrNull ?: root["total_tracks"]?.jsonPrimitive?.intOrNull
            ?: throw IncompleteFinitePlaylistException()
        if (nextUrl != null || tracks.size != expected) throw IncompleteFinitePlaylistException()
        val artistItems = (root["artists"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val artist = entry as? JsonObject ?: return@mapNotNull null
            val id = artist["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ContentItem(ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.ARTIST, id),
                artist["name"]?.jsonPrimitive?.contentOrNull ?: id)
        }
        IosSpotifyAlbumDetail(ContentItem(
            ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.ALBUM, albumId),
            title = title, subtitle = artists, artworkUri = img,
        ), tracks, artistItems, root["release_date"]?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull())
    }

    suspend fun fetchEntityLookup(ref: ContentRef): SpotifyEntityLookup? { return try {
        val path = if (ref.kind == ContentKind.ARTIST) "artists/${ref.id}" else "albums/${ref.id}"
        val root = getJson("https://api.spotify.com/v1/$path")
        val title = root["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val artists = (root["artists"] as? JsonArray)?.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        }.orEmpty()
        SpotifyEntityLookup(title = title, artists = artists)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    }

    fun parseTrackOccurrence(prefix: String, track: JsonObject, fallbackArtwork: String? = null, fallbackAlbum: JsonObject? = null): ContentOccurrence? {
        val id = track["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = track["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val credits = (track["artists"] as? JsonArray).orEmpty().mapNotNull {
            val artist = it as? JsonObject ?: return@mapNotNull null
            val title = artist["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ContentCredit(title, artist["id"]?.jsonPrimitive?.contentOrNull)
        }
        val album = track["album"] as? JsonObject ?: fallbackAlbum
        val img = (album?.get("images") as? JsonArray)
            ?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull ?: fallbackArtwork
        val durationMs = track["duration_ms"]?.jsonPrimitive?.intOrNull?.toLong()
        return ContentOccurrence(
            occurrenceId = "$prefix:$id",
            item = ContentItem(
                ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.SONG, id),
                title = name,
                subtitle = credits.joinToString(", ") { it.name },
                artworkUri = img, durationMs = durationMs, artists = credits,
                album = album?.get("name")?.jsonPrimitive?.contentOrNull,
                albumId = album?.get("id")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
                explicit = track["explicit"]?.jsonPrimitive?.contentOrNull == "true",
                isrc = (track["external_ids"] as? JsonObject)?.get("isrc")?.jsonPrimitive?.contentOrNull,
            ),
        )
    }
}

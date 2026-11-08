package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.ContentCountryKey

/** Native catalog metadata never changes the separate Spotify streaming account. */
internal class IosSpotifyArtistRepository(private val graph: IosSessionGraph) : TypedContentRepository {
    override val provider = ContentProvider.SPOTIFY_CATALOG
    private val client = IosSpotifyRestClient(graph)
    override suspend fun item(ref: ContentRef): ContentItem {
        require(ref.provider == provider.id && ref.kind == ContentKind.ARTIST && ref.id.matches(Regex("[A-Za-z0-9]{1,128}")))
        val row = client.getJson("https://api.spotify.com/v1/artists/${ref.id}")
        return ContentItem(ref, row["name"]?.jsonPrimitive?.content ?: ref.id,
            artworkUri = image(row))
    }

    override suspend fun page(request: ContentRequest, continuation: String?): ContentPage {
        require(request.provider == provider && request.surface == ContentSurface.ARTIST)
        val artist = requireNotNull(request.parent)
        require(artist.kind == ContentKind.ARTIST && artist.id.matches(Regex("[A-Za-z0-9]{1,128}")))
        val id = artist.id
        if (request.section?.id == "top-tracks") {
            require(continuation == null)
            val market = graph.settings.snapshots.value?.get(ContentCountryKey)?.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US"
            val root = client.getJson("https://api.spotify.com/v1/artists/$id/top-tracks?market=$market")
            val tracks = root["tracks"] as? JsonArray ?: throw UnsupportedContentOperation()
            require(tracks.size <= 50)
            return ContentPage(tracks.mapIndexedNotNull { index, raw ->
                (raw as? JsonObject)?.let { client.parseTrackOccurrence("spotify-artist:$id:top:$index", it) }
            })
        }
        require(request.section?.id == "albums")
        val prefix = "spotify-artist:$id:albums:"
        val offset = continuation?.removePrefix(prefix)?.toIntOrNull() ?: if (continuation == null) 0 else error("Foreign continuation")
        require(offset in 0..1000 && offset % 50 == 0 && (continuation == null || continuation == "$prefix$offset"))
        val root = client.getJson("https://api.spotify.com/v1/artists/$id/albums?include_groups=album,single&limit=50&offset=$offset")
        val entries = root["items"] as? JsonArray ?: throw UnsupportedContentOperation()
        require(entries.size <= 50)
        val next = (root["next"] as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" || it.isBlank() }
        require(next == null || entries.isNotEmpty())
        val rows = entries.mapIndexedNotNull { index, raw ->
            val album = raw as? JsonObject ?: return@mapIndexedNotNull null
            val albumId = album["id"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
            ContentOccurrence("$prefix${offset + index}:$albumId", ContentItem(
                ContentRef(provider.id, ContentKind.ALBUM, albumId), album["name"]?.jsonPrimitive?.content ?: albumId,
                subtitle = album["release_date"]?.jsonPrimitive?.contentOrNull.orEmpty(), artworkUri = image(album)))
        }
        if (next != null && offset >= 1000) throw UnsupportedContentOperation()
        return ContentPage(rows, if (next != null) "$prefix${offset + 50}" else null)
    }

    private fun image(row: JsonObject) = (row["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
}

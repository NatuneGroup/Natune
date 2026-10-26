package moe.rukamori.archivetune.shared.data.playlistexport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import moe.rukamori.archivetune.shared.data.persistence.ContentJson
import moe.rukamori.archivetune.shared.data.persistence.PortableLibraryDao
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportCandidate
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportSource
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportTrack
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/**
 * The desktop export read side over the portable state. Tracks come out in playlist order with duplicates kept, and each
 * keeps its library ref, so an M3U location can be looked up. A duration is written only when the library knows a
 * positive one, so an unknown length stays null and is never recorded as zero.
 */
class RoomPlaylistExportSource(private val dao: PortableLibraryDao) : PlaylistExportSource {
    override fun observeCandidates(): Flow<List<PlaylistExportCandidate>> =
        dao.observeTaggablePlaylists().map { rows ->
            rows.map { PlaylistExportCandidate(ContentRef(ContentProvider.LOCAL.id, ContentKind.PLAYLIST, it.id), it.title, it.songCount) }
        }

    override suspend fun loadTracks(ref: ContentRef): List<PlaylistExportTrack> =
        dao.entries(playlistId(ref), Int.MAX_VALUE, 0).map { entry ->
            ContentJson.decode(Json.parseToJsonElement(entry.json).jsonObject).item.toExportTrack()
        }

    override suspend fun loadTags(ref: ContentRef): List<String> = dao.playlistTagNames(playlistId(ref))

    private fun playlistId(ref: ContentRef): String {
        require(ref.provider == ContentProvider.LOCAL.id && ref.kind == ContentKind.PLAYLIST) { "Not a local playlist" }
        return ref.id
    }

    /** The artist text is the item subtitle, which is how the library stores it. */
    private fun ContentItem.toExportTrack(): PlaylistExportTrack = PlaylistExportTrack(
        title = title,
        artist = subtitle,
        album = album.orEmpty(),
        mediaId = ref.id,
        durationSeconds = durationMs?.takeIf { it > 0 }?.let { (it / 1000).toInt() },
        ref = ref,
    )
}

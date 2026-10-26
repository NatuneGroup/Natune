/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.persistence

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.shared.domain.playlisttags.PlaylistTag
import moe.rukamori.archivetune.shared.domain.playlisttags.PlaylistTagNames
import moe.rukamori.archivetune.shared.domain.playlisttags.PlaylistTagsRepository
import moe.rukamori.archivetune.shared.domain.playlisttags.TaggablePlaylist

/**
 * The desktop tag store over the portable state file. It is the only writer of the tag catalog and of the playlist
 * assignments, so the retired JSON tags of v1 cannot diverge from it. Each multi-row write is one DAO transaction, so a
 * failure leaves the previous state in place. A rename or recolour of a missing tag matches no row and does nothing.
 */
class RoomPlaylistTagsRepository(
    private val dao: PortableLibraryDao,
    private val newTagId: () -> String = ::randomPortableTagId,
) : PlaylistTagsRepository {
    override fun observeTags(): Flow<List<PlaylistTag>> =
        dao.observeTags().map { rows -> rows.map { it.toPlaylistTag() } }

    override fun observePlaylistTags(playlistId: String): Flow<List<PlaylistTag>> =
        dao.observePlaylistTags(playlistId).map { rows -> rows.map { it.toPlaylistTag() } }

    /** The picker shows the newest playlist first, which the DAO orders by creation sequence. */
    override fun observeEditablePlaylists(): Flow<List<TaggablePlaylist>> =
        dao.observeTaggablePlaylists().map { rows -> rows.map { TaggablePlaylist(it.id, it.title, it.songCount) } }

    override suspend fun createTag(name: String, color: String) {
        dao.insertTag(PortableTagRow(newTagId(), requireTagName(name), color))
    }

    override suspend fun updateTag(tagId: String, name: String, color: String) {
        dao.renameTag(tagId, requireTagName(name), color)
    }

    override suspend fun updateTagColor(tagId: String, color: String) {
        dao.recolorTag(tagId, color)
    }

    override suspend fun deleteTag(tagId: String) {
        dao.deleteTagAndAssignments(tagId)
    }

    override suspend fun replacePlaylistTags(playlistId: String, tagIds: List<String>) {
        dao.replacePlaylistTags(playlistId, tagIds)
    }

    override suspend fun addTagsToPlaylists(playlistIds: List<String>, tagIds: List<String>) {
        dao.addTagsToPlaylists(playlistIds, tagIds)
    }

    private fun requireTagName(name: String): String =
        requireNotNull(PlaylistTagNames.normalize(name)) { "Tag name must not be blank" }

    private fun PortableTagRow.toPlaylistTag(): PlaylistTag = PlaylistTag(id = id, name = name, color = color)
}

package moe.rukamori.archivetune.shared.data.playlisttags

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.playlisttags.PlaylistTagNames
import moe.rukamori.archivetune.shared.domain.playlisttags.PlaylistTagsRepository
import moe.rukamori.archivetune.shared.domain.playlisttags.PlaylistTagsSnapshot

/**
 * The owner behind the tag screen and the tag picker. Reads combine the catalog, the playlist's own tags and the picker
 * list into one snapshot. Writes run one at a time, so a replace cannot interleave with a delete of the same tag.
 *
 * A blank name is a silent no-op, as in the shipped app. An empty tag set is not a no-op: it clears the playlist's tags,
 * because the picker's "none selected" state must be able to remove every assignment.
 */
class PlaylistTagsOwner(private val repository: PlaylistTagsRepository) {
    private val writes = Mutex()

    fun observe(playlistId: String?): Flow<PlaylistTagsSnapshot> {
        val assigned = playlistId?.let(repository::observePlaylistTags) ?: flowOf(emptyList())
        return combine(repository.observeTags(), assigned, repository.observeEditablePlaylists()) { tags, selected, playlists ->
            PlaylistTagsSnapshot(
                tags = tags,
                selectedTagIds = selected.mapTo(LinkedHashSet()) { tag -> tag.id },
                playlists = playlists,
            )
        }
    }

    suspend fun createTag(name: String, color: String) {
        val normalized = PlaylistTagNames.normalize(name) ?: return
        writes.withLock { repository.createTag(normalized, color) }
    }

    suspend fun updateTag(tagId: String, name: String, color: String) {
        val normalized = PlaylistTagNames.normalize(name) ?: return
        writes.withLock { repository.updateTag(tagId, normalized, color) }
    }

    suspend fun updateTagColor(tagId: String, color: String) {
        writes.withLock { repository.updateTagColor(tagId, color) }
    }

    suspend fun deleteTag(tagId: String) {
        writes.withLock { repository.deleteTag(tagId) }
    }

    suspend fun savePlaylistTags(playlistId: String, tagIds: Set<String>) {
        writes.withLock { repository.replacePlaylistTags(playlistId, tagIds.toList()) }
    }

    suspend fun addTagsToPlaylists(playlistIds: Set<String>, tagIds: Set<String>) {
        writes.withLock { repository.addTagsToPlaylists(playlistIds.toList(), tagIds.toList()) }
    }
}

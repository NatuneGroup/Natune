/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.kmp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.TagEntity
import moe.rukamori.archivetune.shared.domain.playlisttags.PlaylistTag
import moe.rukamori.archivetune.shared.domain.playlisttags.PlaylistTagsRepository
import moe.rukamori.archivetune.shared.domain.playlisttags.TaggablePlaylist

/**
 * The Android tag store. It issues the same DAO calls the shipped repository does, over the retained `tag` and
 * `playlist_tag_map` tables. Schema 36 therefore keeps its meaning and needs no migration. Write serialisation is the
 * shared owner's job, so this adapter only maps each call onto one transaction.
 */
class AndroidPlaylistTagsRepository(private val database: MusicDatabase) : PlaylistTagsRepository {
    override fun observeTags(): Flow<List<PlaylistTag>> =
        database.allTags()
            .map { tags -> tags.map { tag -> tag.toPlaylistTag() } }
            .flowOn(Dispatchers.IO)

    override fun observePlaylistTags(playlistId: String): Flow<List<PlaylistTag>> =
        database.playlistTags(playlistId)
            .map { tags -> tags.map { tag -> tag.toPlaylistTag() } }
            .flowOn(Dispatchers.IO)

    override fun observeEditablePlaylists(): Flow<List<TaggablePlaylist>> =
        database.editablePlaylistsByCreateDateAsc()
            .map { playlists ->
                // The DAO lists the oldest first. The picker shows the newest first, as the shipped screen does.
                playlists.asReversed().map { playlist ->
                    TaggablePlaylist(playlist.id, playlist.playlist.name, playlist.songCount)
                }
            }
            .flowOn(Dispatchers.IO)

    override suspend fun createTag(name: String, color: String) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                insert(TagEntity(name = name, color = color))
            }
        }

    override suspend fun updateTag(tagId: String, name: String, color: String) =
        withContext(Dispatchers.IO) {
            val currentTag = database.tag(tagId).first() ?: return@withContext
            database.withTransaction {
                update(currentTag.copy(name = name, color = color))
            }
        }

    override suspend fun updateTagColor(tagId: String, color: String) =
        withContext(Dispatchers.IO) {
            val currentTag = database.tag(tagId).first() ?: return@withContext
            database.withTransaction {
                update(currentTag.copy(color = color))
            }
        }

    override suspend fun deleteTag(tagId: String) =
        withContext(Dispatchers.IO) {
            val currentTag = database.tag(tagId).first() ?: return@withContext
            database.withTransaction {
                // The DAO removes the tag's playlist assignments in the same transaction as the tag row itself.
                deleteTag(currentTag)
            }
        }

    override suspend fun replacePlaylistTags(playlistId: String, tagIds: List<String>) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                removeAllPlaylistTags(playlistId)
                tagIds.forEach { tagId ->
                    addTagToPlaylist(playlistId, tagId)
                }
            }
        }

    override suspend fun addTagsToPlaylists(playlistIds: List<String>, tagIds: List<String>) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                addTagsToPlaylists(
                    playlistIds = playlistIds,
                    tagIds = tagIds,
                )
            }
        }

    private fun TagEntity.toPlaylistTag(): PlaylistTag = PlaylistTag(id = id, name = name, color = color)
}

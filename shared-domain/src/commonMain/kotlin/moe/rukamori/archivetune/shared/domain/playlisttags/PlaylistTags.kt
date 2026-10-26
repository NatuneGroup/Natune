/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.playlisttags

import kotlinx.coroutines.flow.Flow

/**
 * A user tag that groups playlists. [id] is the storage key that assignment and deletion use, so a rename keeps every
 * assignment. [color] is the stored `#RRGGBB` text. The picker presents it but never keeps a second color schema.
 */
data class PlaylistTag(val id: String, val name: String, val color: String)

/** A playlist offered in the tag picker. [songCount] lets the picker show the size next to the name. */
data class TaggablePlaylist(val id: String, val name: String, val songCount: Int)

/** Everything the tag screen shows for one playlist: the catalog, the playlist's own tags and the picker's playlists. */
data class PlaylistTagsSnapshot(
    val tags: List<PlaylistTag>,
    val selectedTagIds: Set<String>,
    val playlists: List<TaggablePlaylist>,
)

/**
 * Storage for tags and their playlist assignments. Read flows list tags by name and playlists in presentation order,
 * newest first, so an implementation must reproduce that order. A write returns only after its change is committed.
 * A failure throws and leaves the previous state in place. Renaming or recolouring a tag that no longer exists does
 * nothing, as it did in the shipped app.
 */
interface PlaylistTagsRepository {
    fun observeTags(): Flow<List<PlaylistTag>>
    fun observePlaylistTags(playlistId: String): Flow<List<PlaylistTag>>
    fun observeEditablePlaylists(): Flow<List<TaggablePlaylist>>
    suspend fun createTag(name: String, color: String)
    suspend fun updateTag(tagId: String, name: String, color: String)
    suspend fun updateTagColor(tagId: String, color: String)
    suspend fun deleteTag(tagId: String)

    /** Replaces the playlist's tags in one transaction. An empty list clears the assignment. */
    suspend fun replacePlaylistTags(playlistId: String, tagIds: List<String>)

    /** Adds every tag to every playlist in one transaction. Existing assignments are kept, and empty input changes nothing. */
    suspend fun addTagsToPlaylists(playlistIds: List<String>, tagIds: List<String>)
}

/** The one name rule that create and rename share: surrounding whitespace is dropped, and a blank name is refused. */
object PlaylistTagNames {
    fun normalize(name: String): String? = name.trim().ifEmpty { null }
}

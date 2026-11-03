package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.library.PlaylistSongSortType
import moe.rukamori.archivetune.shared.domain.playlist.PlaylistSongOrder
import moe.rukamori.archivetune.shared.domain.playlisttags.PlaylistTagNames
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.songmetadata.canonicalSong
import kotlin.random.Random

/**
 * [addedSeq] is when the song entered the library, which CREATE_DATE sorts by. [likedSeq] is when it was last liked,
 * which the liked songs sort by. Both are set when that change happens, and every later rewrite of the row keeps them,
 * so a rewrite never moves a song in its order. Rows stored before schema 5 take their rowid as the sequence.
 */
@Entity(tableName = "library_item", primaryKeys = ["provider", "kind", "mediaId"])
data class LibraryItemRow(val provider: String, val kind: String, val mediaId: String,
    val title: String, val json: String, val liked: Boolean = false,
    @ColumnInfo(defaultValue = "0") val addedSeq: Long = 0,
    @ColumnInfo(defaultValue = "0") val likedSeq: Long = 0)

/** The stored row for [item]. Its JSON is the occurrence form the library has always written for a saved item. */
internal fun libraryItemRow(item: ContentItem, liked: Boolean, addedSeq: Long, likedSeq: Long): LibraryItemRow =
    LibraryItemRow(item.ref.provider, item.ref.kind.name, item.ref.id, item.canonicalSong().title,
        ContentJson.encode(ContentOccurrence(item.ref.id, item)).toString(), liked, addedSeq, likedSeq)

/**
 * [tags] is the retired JSON array. Room keeps the column because it validates the declared table shape, and nothing
 * reads or writes it since schema v2. [createdSeq] orders the tag picker newest first and is set when the playlist is
 * created. [songSortType] and [songSortDescending] are this playlist's own song order, so sorting one playlist never
 * changes another. Their defaults are the baseline's: a custom order, descending.
 */
@Entity(tableName = "portable_playlist")
data class PortablePlaylistRow(@PrimaryKey val id: String, val title: String, val tags: String = "[]",
    val createdSeq: Long? = null,
    @ColumnInfo(defaultValue = "'CUSTOM'") val songSortType: String = "CUSTOM",
    @ColumnInfo(defaultValue = "1") val songSortDescending: Boolean = true,
    @ColumnInfo(defaultValue = "0") val isHidden: Boolean = false,
    /** The user's collection order, written as a complete list so a move cannot collide; null keeps title order. */
    val customOrder: Int? = null,
    /** The linked YouTube playlist id once the playlist was pushed or synced, as the baseline's browseId. */
    val browseId: String? = null,
    /** The remote song count the last push or pull stored, as the baseline's remoteSongCount. */
    val remoteSongCount: Int? = null)

@Entity(tableName = "portable_tag")
data class PortableTagRow(@PrimaryKey val id: String, val name: String, val color: String)

@Entity(tableName = "portable_playlist_tag", primaryKeys = ["playlistId", "tagId"],
    foreignKeys = [ForeignKey(entity = PortablePlaylistRow::class, parentColumns = ["id"],
        childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PortableTagRow::class, parentColumns = ["id"],
            childColumns = ["tagId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["tagId"])])
data class PortablePlaylistTagRow(val playlistId: String, val tagId: String)

/** A playlist as the tag picker lists it, with its song count. */
data class TaggablePlaylistRow(val id: String, val title: String, val songCount: Int)

/** The colour a tag gets when none is chosen. It matches the shipped `TagEntity` default. */
internal const val PORTABLE_TAG_DEFAULT_COLOR = "#FF6B6B"

/** A random storage key for a new tag. Ids never change on rename, so they are not derived from the name. */
internal fun randomPortableTagId(): String =
    Random.nextBytes(12).joinToString(separator = "") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/**
 * [position] is the custom order and changes when the user moves a row. [addedSeq] is the order the occurrence was
 * added in, which CREATE_DATE sorts by. No custom move touches it.
 */
@Entity(tableName = "portable_playlist_entry", primaryKeys = ["playlistId", "occurrenceId"],
    foreignKeys = [ForeignKey(entity = PortablePlaylistRow::class, parentColumns = ["id"],
        childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["playlistId", "position"], unique = true)])
data class PortablePlaylistEntry(val playlistId: String, val occurrenceId: String, val position: Int, val json: String,
    @ColumnInfo(defaultValue = "0") val addedSeq: Long = 0)

@Dao
abstract class PortableLibraryDao {
    @Query("SELECT * FROM library_item WHERE kind = 'SONG' AND (:likedOnly = 0 OR liked = 1) AND instr(lower(title), lower(:text)) > 0 ORDER BY title, provider, kind, mediaId LIMIT :limit OFFSET :offset")
    abstract suspend fun items(text: String, likedOnly: Boolean, limit: Int, offset: Int): List<LibraryItemRow>
    @Query("SELECT * FROM library_item WHERE kind = 'SONG' AND (:likedOnly = 0 OR liked = 1) AND instr(lower(title), lower(:text)) > 0 ORDER BY title, provider, kind, mediaId")
    abstract fun observeItems(text: String, likedOnly: Boolean): Flow<List<LibraryItemRow>>

    /** Every saved song, or only the liked ones, in a fixed key order. The library sorts that complete list itself. */
    @Query(
        "SELECT * FROM library_item WHERE kind = 'SONG' AND (:likedOnly = 0 OR liked = 1) " +
            "ORDER BY provider, kind, mediaId",
    )
    abstract fun observeSongRows(likedOnly: Boolean): Flow<List<LibraryItemRow>>

    @Query("SELECT * FROM library_item WHERE kind = 'SONG' AND (:likedOnly = 0 OR liked = 1) ORDER BY provider, kind, mediaId")
    abstract suspend fun songRows(likedOnly: Boolean): List<LibraryItemRow>

    /**
     * The liked albums or artists of [kind]. Each is kept only while it is liked, as its own row, so no saved song is
     * needed to list it. [LibraryItemRow.likedSeq] is when it was liked, which the liked lists order by.
     */
    @Query("SELECT * FROM library_item WHERE kind = :kind AND liked = 1 ORDER BY likedSeq, provider, mediaId")
    abstract fun observeBookmarks(kind: String): Flow<List<LibraryItemRow>>

    /** Removes an album or artist that is no longer liked. Only the row of that provider, kind and id goes. */
    @Query("DELETE FROM library_item WHERE provider = :provider AND kind = :kind AND mediaId = :id")
    abstract suspend fun deleteBookmark(provider: String, kind: String, id: String): Int

    /** Removes one saved row. Only the row of that provider, kind and id goes, and nothing else is touched. */
    @Query("DELETE FROM library_item WHERE provider = :provider AND kind = :kind AND mediaId = :id")
    abstract suspend fun deleteItem(provider: String, kind: String, id: String): Int

    /** The next add sequence. It is read inside the write's transaction, so each new song takes a higher one. */
    @Query("SELECT COALESCE(MAX(addedSeq), 0) + 1 FROM library_item")
    abstract suspend fun nextAddedSeq(): Long

    /** The next like sequence, read the same way as [nextAddedSeq]. */
    @Query("SELECT COALESCE(MAX(likedSeq), 0) + 1 FROM library_item")
    abstract suspend fun nextLikedSeq(): Long
    @Query("SELECT * FROM library_item WHERE provider = :provider AND kind = :kind AND mediaId = :id")
    abstract suspend fun item(provider: String, kind: String, id: String): LibraryItemRow?
    /** The rows of every provider and kind whose media id is in [ids]; callers match provider and kind themselves. */
    @Query("SELECT * FROM library_item WHERE mediaId IN (:ids)")
    abstract suspend fun itemsByMediaIds(ids: List<String>): List<LibraryItemRow>
    /**
     * A saved song by media id, or the one of [provider] when it is given. The file formats carry no provider, so an
     * unnamed lookup takes the first match in a fixed order, which keeps the answer stable.
     */
    @Query("SELECT * FROM library_item WHERE kind = 'SONG' AND mediaId = :mediaId AND (:provider IS NULL OR provider = :provider) ORDER BY provider, kind, mediaId LIMIT 1")
    abstract suspend fun songByMediaId(mediaId: String, provider: String?): LibraryItemRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putItem(row: LibraryItemRow)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPlaylist(row: PortablePlaylistRow)
    @Query("SELECT COALESCE(MAX(createdSeq), 0) + 1 FROM portable_playlist")
    abstract suspend fun nextPlaylistSequence(): Long
    @Transaction
    open suspend fun createPlaylist(row: PortablePlaylistRow) = insertPlaylist(row.copy(createdSeq = nextPlaylistSequence()))
    @Query("SELECT * FROM portable_playlist WHERE id = :id")
    abstract suspend fun playlist(id: String): PortablePlaylistRow?
    @Query("SELECT * FROM portable_playlist WHERE instr(lower(title), lower(:text)) > 0 ORDER BY title, id LIMIT :limit OFFSET :offset")
    abstract suspend fun playlists(text: String, limit: Int, offset: Int): List<PortablePlaylistRow>
    @Query("SELECT * FROM portable_playlist WHERE instr(lower(title), lower(:text)) > 0 ORDER BY CASE WHEN customOrder IS NULL THEN 1 ELSE 0 END, customOrder, title, id")
    abstract fun observePlaylists(text: String): Flow<List<PortablePlaylistRow>>

    /** The collection in the order it is listed: the custom order where one was written, title order elsewhere. */
    @Query("SELECT * FROM portable_playlist ORDER BY CASE WHEN customOrder IS NULL THEN 1 ELSE 0 END, customOrder, title, id")
    abstract suspend fun playlistsInStoredOrder(): List<PortablePlaylistRow>

    @Query("UPDATE portable_playlist SET customOrder = :customOrder WHERE id = :id")
    abstract suspend fun setPlaylistCustomOrder(id: String, customOrder: Int): Int

    @Query("UPDATE portable_playlist SET browseId = :browseId, remoteSongCount = :remoteSongCount WHERE id = :id")
    abstract suspend fun updatePlaylistRemote(id: String, browseId: String?, remoteSongCount: Int?): Int
    @Query("UPDATE portable_playlist SET title = :title WHERE id = :id")
    abstract suspend fun rename(id: String, title: String): Int
    @Query("SELECT * FROM portable_playlist WHERE isHidden = 1 ORDER BY title, id")
    abstract fun observeHiddenPlaylists(): Flow<List<PortablePlaylistRow>>
    @Query("UPDATE portable_playlist SET isHidden = :hidden WHERE id = :id")
    abstract suspend fun setPlaylistHidden(id: String, hidden: Boolean): Int
    @Query("DELETE FROM portable_playlist WHERE id = :id")
    abstract suspend fun deletePlaylistRow(id: String): Int
    /**
     * Removes one local playlist with its entries and tag assignments. The child rows go first and explicitly, so the
     * result does not depend on whether the connection enforces the foreign-key cascade.
     */
    @Transaction
    open suspend fun deletePlaylist(id: String) {
        clearPlaylistTags(id)
        clearEntries(id)
        require(deletePlaylistRow(id) == 1) { "Unknown local playlist" }
    }
    @Query("SELECT p.id AS id, p.title AS title, (SELECT COUNT(*) FROM portable_playlist_entry e WHERE e.playlistId = p.id) AS songCount FROM portable_playlist p ORDER BY p.createdSeq DESC, p.id DESC")
    abstract fun observeTaggablePlaylists(): Flow<List<TaggablePlaylistRow>>
    @Query("SELECT * FROM portable_tag ORDER BY name, id")
    abstract fun observeTags(): Flow<List<PortableTagRow>>
    @Query("SELECT t.* FROM portable_tag t INNER JOIN portable_playlist_tag m ON m.tagId = t.id WHERE m.playlistId = :playlistId ORDER BY t.name, t.id")
    abstract fun observePlaylistTags(playlistId: String): Flow<List<PortableTagRow>>
    @Query("SELECT t.name FROM portable_tag t INNER JOIN portable_playlist_tag m ON m.tagId = t.id WHERE m.playlistId = :playlistId ORDER BY t.name, t.id")
    abstract suspend fun playlistTagNames(playlistId: String): List<String>
    @Query("SELECT * FROM portable_tag WHERE id = :id")
    abstract suspend fun tag(id: String): PortableTagRow?
    @Query("SELECT * FROM portable_tag WHERE name = :name ORDER BY id LIMIT 1")
    abstract suspend fun tagNamed(name: String): PortableTagRow?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertTag(row: PortableTagRow)
    @Query("UPDATE portable_tag SET name = :name, color = :color WHERE id = :id")
    abstract suspend fun renameTag(id: String, name: String, color: String): Int
    @Query("UPDATE portable_tag SET color = :color WHERE id = :id")
    abstract suspend fun recolorTag(id: String, color: String): Int
    @Query("DELETE FROM portable_playlist_tag WHERE tagId = :tagId")
    abstract suspend fun removeTagAssignments(tagId: String)
    @Query("DELETE FROM portable_tag WHERE id = :tagId")
    abstract suspend fun deleteTagRow(tagId: String): Int
    @Query("DELETE FROM portable_playlist_tag WHERE playlistId = :playlistId")
    abstract suspend fun clearPlaylistTags(playlistId: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun putTagAssignments(rows: List<PortablePlaylistTagRow>)
    @Query("SELECT * FROM portable_playlist_entry WHERE playlistId = :id ORDER BY position LIMIT :limit OFFSET :offset")
    abstract suspend fun entries(id: String, limit: Int, offset: Int): List<PortablePlaylistEntry>
    @Query("SELECT * FROM portable_playlist_entry WHERE playlistId = :id ORDER BY position")
    abstract fun observeEntries(id: String): Flow<List<PortablePlaylistEntry>>
    @Query("DELETE FROM portable_playlist_entry WHERE playlistId = :id")
    abstract suspend fun clearEntries(id: String)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun putEntries(entries: List<PortablePlaylistEntry>)
    @Query("SELECT * FROM portable_playlist WHERE id = :id")
    abstract fun observePlaylistRow(id: String): Flow<List<PortablePlaylistRow>>
    @Query("UPDATE portable_playlist SET songSortType = :type, songSortDescending = :descending WHERE id = :id")
    abstract suspend fun updateSongSort(id: String, type: String, descending: Boolean): Int
    /** Both halves of one playlist's sort go in one statement, so one edit never leaves one of them behind. */
    @Transaction
    open suspend fun setSongSort(playlistId: String, type: String, descending: Boolean) {
        require(updateSongSort(playlistId, type, descending) == 1) { "Unknown local playlist" }
    }

    /**
     * Removes saved items from the library, the same cascade the Android database performs for a saved
     * song leaving the library: the row that carries membership and the like goes, while playlists,
     * downloads, listens and metadata edits keep their own tables, so a re-added item meets them again.
     * Every ref is checked first, so a missing item refuses the whole batch and changes nothing.
     */
    @Transaction
    open suspend fun removeItems(refs: List<ContentRef>) {
        for (ref in refs) {
            requireNotNull(item(ref.provider, ref.kind.name, ref.id)) { "Item is not in the library" }
        }
        for (ref in refs) {
            require(deleteItem(ref.provider, ref.kind.name, ref.id) == 1) { "Item is not in the library" }
        }
    }

    @Transaction
    open suspend fun saveItems(items: List<ContentItem>) {
        for (item in items) {
            val current = this.item(item.ref.provider, item.ref.kind.name, item.ref.id)
            putItem(
                if (current == null) libraryItemRow(item, liked = false, addedSeq = nextAddedSeq(), likedSeq = 0)
                else libraryItemRow(item, current.liked, current.addedSeq, current.likedSeq),
            )
        }
    }

    /**
     * Saves only the songs the library does not hold yet. A saved row keeps its stored metadata and like state, so an
     * import never rewrites a song the user already has. Used inside the import transactions, never on its own.
     */
    @Transaction
    open suspend fun saveMissingItems(items: List<ContentItem>) {
        for (item in items) {
            if (this.item(item.ref.provider, item.ref.kind.name, item.ref.id) == null) {
                putItem(libraryItemRow(item, liked = false, addedSeq = nextAddedSeq(), likedSeq = 0))
            }
        }
    }

    @Transaction
    open suspend fun mutate(mutation: LibraryMutation) {
        when (mutation) {
            is LibraryMutation.Like -> {
                val ref = mutation.ref
                val row = requireNotNull(item(ref.provider, ref.kind.name, ref.id)) { "Content has not been saved" }
                // Only a new like takes a new sequence. A repeated like keeps the place it had.
                val likedSeq = if (mutation.liked && !row.liked) nextLikedSeq() else row.likedSeq
                putItem(row.copy(liked = mutation.liked, likedSeq = likedSeq))
            }
            is LibraryMutation.LikeAlbum -> setBookmark(mutation.album, mutation.liked)
            is LibraryMutation.SubscribeArtist -> setBookmark(mutation.artist, mutation.subscribed)
            is LibraryMutation.AddToPlaylist -> {
                requireNotNull(playlist(mutation.playlistId)) { "Unknown local playlist" }
                val existing = entries(mutation.playlistId, Int.MAX_VALUE, 0)
                val ids = existing.mapTo(mutableSetOf()) { it.occurrenceId }
                require(mutation.occurrences.all { it.occurrenceId.isNotBlank() && ids.add(it.occurrenceId) }) {
                    "Duplicate occurrence identity"
                }
                // A new row is newer than every stored row, so the sequence only has to rise above the maximum.
                val firstSeq = (existing.maxOfOrNull { it.addedSeq } ?: 0L) + 1
                putEntries(mutation.occurrences.mapIndexed { index, occurrence ->
                    PortablePlaylistEntry(mutation.playlistId, occurrence.occurrenceId, existing.size + index,
                        ContentJson.encode(occurrence).toString(), firstSeq + index)
                })
            }
            is LibraryMutation.RemoveFromPlaylist -> {
                requireNotNull(playlist(mutation.playlistId)) { "Unknown local playlist" }
                val retained = entries(mutation.playlistId, Int.MAX_VALUE, 0).filter { it.occurrenceId !in mutation.occurrenceIds }
                clearEntries(mutation.playlistId)
                putEntries(retained.mapIndexed { index, row -> row.copy(position = index) })
            }
            is LibraryMutation.RenamePlaylist -> {
                require(mutation.title.isNotBlank())
                require(rename(mutation.playlistId, mutation.title) == 1) { "Unknown local playlist" }
            }
            is LibraryMutation.SetPlaylistTags -> replaceTagsByName(mutation.playlistId, mutation.tags)
            is LibraryMutation.MoveInPlaylist -> moveEntry(mutation)
            is LibraryMutation.MovePlaylist -> movePlaylist(mutation)
            is LibraryMutation.ReplacePlaylistEntries -> replaceEntries(mutation)
        }
    }

    /**
     * Moves one row beside another in the custom order. The whole order is rewritten in one transaction, because the
     * positions are unique per playlist and a single-row update would collide with its neighbour. A sorted playlist
     * refuses the move, since the stored custom order is the one a move changes and the sorted list is not that order.
     */
    @Transaction
    open suspend fun moveEntry(mutation: LibraryMutation.MoveInPlaylist) {
        val row = requireNotNull(playlist(mutation.playlistId)) { "Unknown local playlist" }
        check(row.songSortType == PlaylistSongSortType.CUSTOM.name) { "Only a custom order can be moved" }
        val rows = entries(mutation.playlistId, Int.MAX_VALUE, 0)
        val order = requireNotNull(PlaylistSongOrder.moveBeside(rows.map { it.occurrenceId },
            mutation.occurrenceId, mutation.neighbourOccurrenceId, mutation.after)) { "Unknown playlist entry" }
        val byId = rows.associateBy { it.occurrenceId }
        clearEntries(mutation.playlistId)
        putEntries(order.mapIndexed { index, id -> requireNotNull(byId[id]).copy(position = index) })
    }

    /** Library callers name tags. A known name reuses its catalog row, and an unknown name creates one. */
    @Transaction
    open suspend fun replaceTagsByName(playlistId: String, names: Set<String>) {
        requireNotNull(playlist(playlistId)) { "Unknown local playlist" }
        val tagIds = names.mapNotNull(PlaylistTagNames::normalize).distinct().map { name ->
            tagNamed(name)?.id ?: randomPortableTagId().also { insertTag(PortableTagRow(it, name, PORTABLE_TAG_DEFAULT_COLOR)) }
        }
        clearPlaylistTags(playlistId)
        putTagAssignments(tagIds.map { PortablePlaylistTagRow(playlistId, it) })
    }

    /**
     * Moves one playlist beside another in the collection's custom order. Every row is assigned its new position in one
     * transaction, so the order becomes explicit over the whole list on the first move; after that a move only
     * rewrites the positions it changed. An id outside the stored order refuses the whole write.
     */
    @Transaction
    open suspend fun movePlaylist(mutation: LibraryMutation.MovePlaylist) {
        val rows = playlistsInStoredOrder()
        val order = requireNotNull(PlaylistSongOrder.moveBeside(rows.map { it.id },
            mutation.playlistId, mutation.neighbourPlaylistId, mutation.after)) { "Unknown playlist" }
        val byId = rows.associateBy { it.id }
        for ((index, id) in order.withIndex()) {
            val row = requireNotNull(byId[id])
            if (row.customOrder != index) setPlaylistCustomOrder(id, index)
        }
    }

    /**
     * Rewrites a playlist's whole stored order, as the remote-pull sync does: entries the pull did not return leave the
     * list, and the new rows take consecutive positions and sequences after the rows they replace.
     */
    @Transaction
    open suspend fun replaceEntries(mutation: LibraryMutation.ReplacePlaylistEntries) {
        requireNotNull(playlist(mutation.playlistId)) { "Unknown local playlist" }
        val existing = entries(mutation.playlistId, Int.MAX_VALUE, 0)
        val ids = mutation.occurrences.mapTo(mutableSetOf()) { it.occurrenceId }
        require(mutation.occurrences.all { it.occurrenceId.isNotBlank() } && ids.size == mutation.occurrences.size) {
            "Duplicate occurrence identity"
        }
        val firstSeq = (existing.maxOfOrNull { it.addedSeq } ?: 0L) + 1
        clearEntries(mutation.playlistId)
        putEntries(mutation.occurrences.mapIndexed { index, occurrence ->
            PortablePlaylistEntry(mutation.playlistId, occurrence.occurrenceId, index,
                ContentJson.encode(occurrence).toString(), firstSeq + index)
        })
    }

    /** Removes the tag's assignments and the tag in one transaction, so no assignment outlives its tag. */
    @Transaction
    open suspend fun deleteTagAndAssignments(tagId: String) {
        removeTagAssignments(tagId)
        deleteTagRow(tagId)
    }

    @Transaction
    open suspend fun replacePlaylistTags(playlistId: String, tagIds: List<String>) {
        requireNotNull(playlist(playlistId)) { "Unknown local playlist" }
        for (tagId in tagIds) requireNotNull(tag(tagId)) { "Unknown tag" }
        clearPlaylistTags(playlistId)
        putTagAssignments(tagIds.distinct().map { PortablePlaylistTagRow(playlistId, it) })
    }

    @Transaction
    open suspend fun addTagsToPlaylists(playlistIds: List<String>, tagIds: List<String>) {
        if (playlistIds.isEmpty() || tagIds.isEmpty()) return
        for (playlistId in playlistIds) requireNotNull(playlist(playlistId)) { "Unknown local playlist" }
        for (tagId in tagIds) requireNotNull(tag(tagId)) { "Unknown tag" }
        putTagAssignments(playlistIds.distinct().flatMap { playlistId ->
            tagIds.distinct().map { PortablePlaylistTagRow(playlistId, it) }
        })
    }

    /**
     * A new playlist with its entries and tags, committed as one unit. A failed import therefore leaves no empty
     * playlist behind. Each part goes through [mutate], so an import uses the same writes as the playlist screens. A
     * song the library does not hold yet is saved first, in the same unit, because the entries alone do not store it.
     */
    @Transaction
    open suspend fun createImportedPlaylist(row: PortablePlaylistRow, occurrences: List<ContentOccurrence>, tags: Set<String>) {
        createPlaylist(row)
        saveMissingItems(occurrences.map { it.item })
        if (occurrences.isNotEmpty()) mutate(LibraryMutation.AddToPlaylist(row.id, occurrences))
        if (tags.isNotEmpty()) mutate(LibraryMutation.SetPlaylistTags(row.id, tags))
    }

    /**
     * Appends entries to an existing playlist. The tags join the ones it already has. They are read inside the same
     * transaction, so a tag edit made meanwhile cannot be lost. A song the library does not hold yet is saved in the
     * same transaction, as [createImportedPlaylist] does.
     */
    @Transaction
    open suspend fun appendImported(playlistId: String, occurrences: List<ContentOccurrence>, tags: Set<String>) {
        requireNotNull(playlist(playlistId)) { "Unknown local playlist" }
        saveMissingItems(occurrences.map { it.item })
        if (occurrences.isNotEmpty()) mutate(LibraryMutation.AddToPlaylist(playlistId, occurrences))
        if (tags.isNotEmpty()) {
            mutate(LibraryMutation.SetPlaylistTags(playlistId, playlistTagNames(playlistId).toSet() + tags))
        }
    }

    /**
     * Likes every song in one transaction, so a refused like leaves the liked list as it was. A song the library does not
     * hold yet is saved already liked, so a song found online is liked exactly as a saved one is.
     */
    @Transaction
    open suspend fun likeAll(items: List<ContentItem>) {
        for (item in items) {
            val current = this.item(item.ref.provider, item.ref.kind.name, item.ref.id)
            if (current == null) {
                putItem(libraryItemRow(item, liked = true, addedSeq = nextAddedSeq(), likedSeq = nextLikedSeq()))
            } else if (!current.liked) {
                putItem(current.copy(liked = true, likedSeq = nextLikedSeq()))
            }
        }
    }

    /**
     * Likes or unlikes one album or artist. A liked one is stored as the item its provider gave, so its list shows it
     * without a saved song. A new like takes a new sequence, and a repeated like keeps the place it had. Unliking
     * removes the row, because an album or artist is kept only while it is liked.
     */
    @Transaction
    open suspend fun setBookmark(content: ContentItem, liked: Boolean) {
        val ref = content.ref
        require(ref.kind == ContentKind.ALBUM || ref.kind == ContentKind.ARTIST) {
            "Only albums and artists are bookmarked"
        }
        val current = this.item(ref.provider, ref.kind.name, ref.id)
        when {
            !liked -> deleteBookmark(ref.provider, ref.kind.name, ref.id)
            current?.liked == true -> Unit
            else -> putItem(
                libraryItemRow(content, liked = true, addedSeq = current?.addedSeq ?: nextAddedSeq(),
                    likedSeq = nextLikedSeq()),
            )
        }
    }
}

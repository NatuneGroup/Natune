package moe.rukamori.archivetune.shared.data.persistence

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentQuery
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.LibraryMutation

/**
 * The remove-from-library cascade over an in-memory [PortableLibraryDao], so the transaction methods the
 * Room subclass inherits run unmodified. Removing a song drops its membership row, and with it the like
 * the row carried; playlists, downloads and listens keep their own tables, as the Android store keeps
 * them when a saved song leaves the library.
 */
class RoomLibraryRemoveTest {
    @Test fun removeItemsDropsMembershipAndLikeWhilePlaylistEntriesStay() = runTest {
        val dao = FakeLibraryDao()
        val repo = RoomLibraryRepository(dao)
        val song = ContentItem(ContentRef("youtube", ContentKind.SONG, "song-1"), "Song One")
        repo.saveItems(listOf(song))
        repo.mutate(LibraryMutation.Like(song.ref, liked = true))
        repo.createPlaylist("p", "Mix")
        repo.mutate(LibraryMutation.AddToPlaylist("p", listOf(ContentOccurrence("occ-1", song))))

        repo.removeItems(listOf(song.ref))

        assertTrue(dao.songRows(false).isEmpty())
        assertTrue(dao.songRows(true).isEmpty())
        assertNull(repo.findSong("song-1", "youtube"))
        assertFailsWith<IllegalArgumentException> { repo.item(song.ref) }
        // The playlist row is its own record of the occurrence, so removal leaves the entry behind,
        // exactly as Android's inLibrary=null leaves the song inside its playlists.
        val playlistItems =
            repo.page(
                ContentQuery(
                    RoomLibraryRepository.PLAYLIST_ITEMS,
                    parent = ContentRef("local", ContentKind.PLAYLIST, "p"),
                ),
            )
        assertEquals(listOf("occ-1"), playlistItems.items.map { it.occurrenceId })
    }

    @Test fun removeItemsRefusesMissingRowsAndLeavesTheBatchUntouched() = runTest {
        val dao = FakeLibraryDao()
        val repo = RoomLibraryRepository(dao)
        val kept = ContentItem(ContentRef("youtube", ContentKind.SONG, "kept"), "Kept")
        repo.saveItems(listOf(kept))

        assertFailsWith<IllegalArgumentException> {
            repo.removeItems(
                listOf(kept.ref, ContentRef("youtube", ContentKind.SONG, "missing")),
            )
        }

        // Validation precedes any delete, so the batch refuses before the existing row goes.
        assertEquals(listOf("kept"), dao.songRows(false).map { it.mediaId })
        assertNotNull(repo.findSong("kept", "youtube"))
    }

    @Test fun removeItemsRemovesABookmarkRowOnly() = runTest {
        val dao = FakeLibraryDao()
        val repo = RoomLibraryRepository(dao)
        val album = ContentItem(ContentRef("youtube", ContentKind.ALBUM, "album-1"), "Album")
        repo.mutate(LibraryMutation.LikeAlbum(album, liked = true))
        val song = ContentItem(ContentRef("youtube", ContentKind.SONG, "song-1"), "Song")
        repo.saveItems(listOf(song))

        repo.removeItems(listOf(album.ref))

        assertNull(dao.item("youtube", "ALBUM", "album-1"))
        assertNotNull(dao.item("youtube", "SONG", "song-1"))
    }

    /**
     * Backed by maps, not SQLite: the abstract reads and writes the repository's open transactions
     * exercise are served here; anything the test does not reach fails loudly instead of answering.
     */
    private class FakeLibraryDao : PortableLibraryDao() {
        private val items = LinkedHashMap<Triple<String, String, String>, LibraryItemRow>()
        private val playlists = LinkedHashMap<String, PortablePlaylistRow>()
        private val entriesByPlaylist = LinkedHashMap<String, MutableList<PortablePlaylistEntry>>()
        private var addedSeq = 0L
        private var likedSeq = 0L
        private var playlistSeq = 0L

        override suspend fun items(text: String, likedOnly: Boolean, limit: Int, offset: Int) = unused<List<LibraryItemRow>>()
        override fun observeItems(text: String, likedOnly: Boolean) = unused<Flow<List<LibraryItemRow>>>()

        override fun observeSongRows(likedOnly: Boolean): Flow<List<LibraryItemRow>> =
            flow { emit(songRows(likedOnly)) }

        override suspend fun songRows(likedOnly: Boolean): List<LibraryItemRow> =
            items.values
                .filter { it.kind == "SONG" && (!likedOnly || it.liked) }
                .sortedWith(compareBy({ it.provider }, { it.kind }, { it.mediaId }))

        override fun observeBookmarks(kind: String) = unused<Flow<List<LibraryItemRow>>>()
        override suspend fun deleteBookmark(provider: String, kind: String, id: String) = unused<Int>()
        override suspend fun nextAddedSeq() = ++addedSeq
        override suspend fun nextLikedSeq() = ++likedSeq

        override suspend fun item(provider: String, kind: String, id: String) =
            items[Triple(provider, kind, id)]

        override suspend fun songByMediaId(mediaId: String, provider: String?) =
            items.values
                .filter { it.kind == "SONG" && it.mediaId == mediaId && (provider == null || it.provider == provider) }
                .sortedWith(compareBy({ it.provider }, { it.kind }, { it.mediaId }))
                .firstOrNull()

        override suspend fun putItem(row: LibraryItemRow) {
            items[Triple(row.provider, row.kind, row.mediaId)] = row
        }

        override suspend fun deleteItem(provider: String, kind: String, id: String): Int =
            if (items.remove(Triple(provider, kind, id)) != null) 1 else 0

        override suspend fun insertPlaylist(row: PortablePlaylistRow) {
            require(row.id !in playlists)
            playlists[row.id] = row
        }

        override suspend fun nextPlaylistSequence() = ++playlistSeq
        override suspend fun playlist(id: String) = playlists[id]
        override suspend fun playlists(text: String, limit: Int, offset: Int) = unused<List<PortablePlaylistRow>>()
        override fun observePlaylists(text: String) = unused<Flow<List<PortablePlaylistRow>>>()
        override suspend fun rename(id: String, title: String) = unused<Int>()
        override fun observeHiddenPlaylists() = unused<Flow<List<PortablePlaylistRow>>>()
        override suspend fun setPlaylistHidden(id: String, hidden: Boolean) = unused<Int>()
        override suspend fun deletePlaylistRow(id: String) = unused<Int>()
        override fun observeTaggablePlaylists() = unused<Flow<List<TaggablePlaylistRow>>>()
        override fun observeTags() = unused<Flow<List<PortableTagRow>>>()
        override fun observePlaylistTags(playlistId: String) = unused<Flow<List<PortableTagRow>>>()
        override suspend fun playlistTagNames(playlistId: String) = unused<List<String>>()
        override suspend fun tag(id: String) = unused<PortableTagRow?>()
        override suspend fun tagNamed(name: String) = unused<PortableTagRow?>()
        override suspend fun insertTag(row: PortableTagRow) = unused<Unit>()
        override suspend fun renameTag(id: String, name: String, color: String) = unused<Int>()
        override suspend fun recolorTag(id: String, color: String) = unused<Int>()
        override suspend fun removeTagAssignments(tagId: String) = unused<Unit>()
        override suspend fun deleteTagRow(tagId: String) = unused<Int>()
        override suspend fun clearPlaylistTags(playlistId: String) = unused<Unit>()
        override suspend fun putTagAssignments(rows: List<PortablePlaylistTagRow>) = unused<Unit>()

        override suspend fun entries(id: String, limit: Int, offset: Int) =
            entriesByPlaylist[id].orEmpty().drop(offset).take(limit)

        override fun observeEntries(id: String): Flow<List<PortablePlaylistEntry>> =
            flowOf(entriesByPlaylist[id].orEmpty())

        override suspend fun clearEntries(id: String) {
            entriesByPlaylist.remove(id)
        }

        override suspend fun putEntries(entries: List<PortablePlaylistEntry>) {
            for (entry in entries) {
                val list = entriesByPlaylist.getOrPut(entry.playlistId) { mutableListOf() }
                require(list.none { it.occurrenceId == entry.occurrenceId })
                list.add(entry)
            }
        }

        override fun observePlaylistRow(id: String) = unused<Flow<List<PortablePlaylistRow>>>()
        override suspend fun updateSongSort(id: String, type: String, descending: Boolean) = unused<Int>()

        private fun <T> unused(): T = error("not reached by this test")
    }
}

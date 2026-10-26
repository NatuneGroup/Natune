/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.persistence

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.repository.*

class RoomLibraryRepositoryTest {
    @Test fun committedPlaylistMutationsPreserveDuplicatesOrderAndTags() = runTest {
        val db = openPortableStateDatabase(":memory:")
        try {
            val repo = RoomLibraryRepository(db.library())
            val song = ContentItem(ContentRef("youtube", ContentKind.SONG, "same"), "Same")
            repo.createPlaylist("p", "My playlist")
            repo.saveItems(listOf(song))
            repo.mutate(LibraryMutation.AddToPlaylist("p", listOf(ContentOccurrence("a", song), ContentOccurrence("b", song))))
            val query = ContentQuery(RoomLibraryRepository.PLAYLIST_ITEMS, parent = ContentRef("local", ContentKind.PLAYLIST, "p"))
            assertEquals(listOf("a", "b"), repo.page(query).items.map { it.occurrenceId })
            repo.mutate(LibraryMutation.RemoveFromPlaylist("p", setOf("a")))
            assertEquals(listOf("b"), repo.observe(query).first().items.map { it.occurrenceId })
            repo.mutate(LibraryMutation.SetPlaylistTags("p", setOf("日本語", "favorite")))
            assertEquals(setOf("日本語", "favorite"), repo.playlistTags("p"))
            repo.mutate(LibraryMutation.Like(song.ref, true))
            assertEquals(listOf(song.ref), repo.page(ContentQuery(RoomLibraryRepository.LIKED)).items.map { it.item.ref })
        } finally { db.close() }
    }
    @Test fun pageTokensAreValidatedAndSearchQueriesTheWholeStore() = runTest {
        val db = openPortableStateDatabase(":memory:")
        try {
            val repo = RoomLibraryRepository(db.library(), pageSize = 2)
            repo.saveItems((0..4).map { ContentItem(ContentRef("youtube", ContentKind.SONG, "$it"), "Track $it") })
            repo.saveItems(listOf(ContentItem(ContentRef("youtube", ContentKind.ARTIST, "artist"), "Track artist")))
            val first = repo.page(ContentQuery(RoomLibraryRepository.SONGS))
            val second = repo.page(ContentQuery(RoomLibraryRepository.SONGS), first.continuation)
            assertEquals(2, second.items.size)
            assertEquals(5, repo.observe(ContentQuery(RoomLibraryRepository.SONGS)).first().items.size)
            assertEquals("Track 4", repo.page(ContentQuery(RoomLibraryRepository.SONGS, text = "Track 4")).items.single().item.title)
            assertFailsWith<IllegalArgumentException> { repo.page(ContentQuery("unsupported")) }
            assertFailsWith<IllegalArgumentException> { repo.page(ContentQuery(RoomLibraryRepository.SONGS), "remote-token") }
        } finally { db.close() }
    }
}

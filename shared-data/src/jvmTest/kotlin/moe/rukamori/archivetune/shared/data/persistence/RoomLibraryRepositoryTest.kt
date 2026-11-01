package moe.rukamori.archivetune.shared.data.persistence

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.songmetadata.SongMetadataOverride

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

    @Test fun editedNamesDriveDisplayAndLocalSearchWhileStoredRowsStayCanonical() = runTest {
        val db = openPortableStateDatabase(":memory:")
        try {
            val metadata = RoomSongMetadataRepository(db.songMetadataOverrides())
            val repo = RoomLibraryRepository(db.library(), songMetadata = metadata)
            val song = ContentItem(ContentRef("youtube", ContentKind.SONG, "same"), "Provider title",
                artists = listOf(ContentCredit("Provider artist", "UC1")))
            repo.saveItems(listOf(song))
            metadata.setOverride(song.ref, SongMetadataOverride("Edited title", listOf("Edited artist"), 7L))

            val page = repo.page(ContentQuery(RoomLibraryRepository.SONGS))
            assertEquals("Edited title", page.items.single().item.title)
            assertEquals(listOf("Edited artist"), page.items.single().item.artists.map { it.name })
            assertEquals("Provider title", page.items.single().item.canonicalSongMetadata?.title)
            assertEquals("Edited title", repo.observe(ContentQuery(RoomLibraryRepository.SONGS)).first().items.single().item.title)
            assertEquals("Edited title", repo.findSong("same", "youtube")?.title)

            // Local search answers from the edited fields, like the Android host; the provider names the
            // user no longer sees do not match.
            assertEquals("same", repo.page(ContentQuery(RoomLibraryRepository.SONGS, text = "edited title")).items.single().item.ref.id)
            assertEquals("same", repo.page(ContentQuery(RoomLibraryRepository.SONGS, text = "EDITED ARTIST")).items.single().item.ref.id)
            assertTrue(repo.page(ContentQuery(RoomLibraryRepository.SONGS, text = "Provider title")).items.isEmpty())
            assertTrue(repo.page(ContentQuery(RoomLibraryRepository.SONGS, text = "Provider artist")).items.isEmpty())

            // The stored row itself is untouched, so a reset brings the provider names back.
            metadata.setOverride(song.ref, null)
            assertEquals("Provider title", repo.page(ContentQuery(RoomLibraryRepository.SONGS)).items.single().item.title)
            assertEquals("same", repo.page(ContentQuery(RoomLibraryRepository.SONGS, text = "Provider artist")).items.single().item.ref.id)
        } finally { db.close() }
    }

    @Test fun playlistPickerReportsMembershipMosaicAndCoverIdentity() = runTest {
        val db = openPortableStateDatabase(":memory:")
        try {
            val repo = RoomLibraryRepository(db.library())
            val songA = ContentItem(ContentRef("youtube", ContentKind.SONG, "a"), "A", artworkUri = "https://i/a.jpg")
            val songB = ContentItem(ContentRef("youtube", ContentKind.SONG, "b"), "B", artworkUri = "https://i/b.jpg")
            val songC = ContentItem(ContentRef("youtube", ContentKind.SONG, "c"), "C")
            repo.saveItems(listOf(songA, songB, songC))
            repo.createPlaylist("p1", "One")
            repo.createPlaylist("p2", "Two")
            val batch = listOf(ContentOccurrence("batch-a", songA), ContentOccurrence("batch-c", songC))
            repo.mutate(LibraryMutation.AddToPlaylist("p1", listOf(ContentOccurrence("stored-a", songA), ContentOccurrence("stored-b", songB))))

            val candidates = repo.observePlaylistPicker(batch).first().associateBy { it.item.ref.id }

            // p1 holds song A by ref but stores it under a different occurrence id, so the write filters nothing and
            // the membership hint still fires. The playlist item carries its cover pseudo-URL.
            val one = candidates.getValue("p1")
            assertEquals(setOf(songA.ref), one.memberRefs)
            assertEquals(emptySet(), one.memberOccurrenceIds)
            assertEquals("local-playlist-cover://p1", one.item.artworkUri)
            val two = candidates.getValue("p2")
            assertEquals(emptySet(), two.memberRefs)
            assertEquals(emptySet(), two.memberOccurrenceIds)

            // The summary feeds the row mosaic with the first stored items and the same cover identity.
            val summary = repo.observeLocalPlaylists().first().single { it.occurrence.item.ref.id == "p1" }
            assertEquals(listOf(songA.ref, songB.ref), summary.previewItems.map { it.ref })
            assertEquals("local-playlist-cover://p1", summary.occurrence.item.artworkUri)
        } finally { db.close() }
    }

    @Test fun removingALibraryItemKeepsItsPlaylistEntryDownloadAndEdits() = runTest {
        val db = openPortableStateDatabase(":memory:")
        try {
            val metadata = RoomSongMetadataRepository(db.songMetadataOverrides())
            val repo = RoomLibraryRepository(db.library(), songMetadata = metadata)
            val song = ContentItem(ContentRef("youtube", ContentKind.SONG, "gone"), "Gone")
            repo.saveItems(listOf(song))
            repo.mutate(LibraryMutation.Like(song.ref, true))
            repo.createPlaylist("p", "Mix")
            repo.mutate(LibraryMutation.AddToPlaylist("p", listOf(ContentOccurrence("occ-1", song))))
            metadata.setOverride(song.ref, SongMetadataOverride("Edited", listOf("Edited artist"), 3L))

            repo.removeItems(listOf(song.ref))

            // Membership and the like the row carried are gone, the Android cascade's effect.
            assertTrue(repo.page(ContentQuery(RoomLibraryRepository.SONGS)).items.isEmpty())
            assertTrue(repo.page(ContentQuery(RoomLibraryRepository.LIKED)).items.isEmpty())
            assertNull(repo.findSong("gone", "youtube"))
            // Playlists and metadata edits are their own tables, so both keep the song, as Android's
            // inLibrary=null keeps them. A later re-add meets them again.
            val playlist = ContentQuery(RoomLibraryRepository.PLAYLIST_ITEMS, parent = ContentRef("local", ContentKind.PLAYLIST, "p"))
            assertEquals(listOf("occ-1"), repo.page(playlist).items.map { it.occurrenceId })
            repo.saveItems(listOf(song))
            assertEquals("Edited", repo.page(ContentQuery(RoomLibraryRepository.SONGS)).items.single().item.title)

            // An unknown ref refuses the whole batch and changes nothing, as deletePlaylist does.
            val kept = ContentItem(ContentRef("youtube", ContentKind.SONG, "kept"), "Kept")
            repo.saveItems(listOf(kept))
            assertFailsWith<IllegalArgumentException> {
                repo.removeItems(listOf(kept.ref, ContentRef("youtube", ContentKind.SONG, "missing")))
            }
            assertNotNull(repo.findSong("kept", "youtube"))
        } finally { db.close() }
    }
}

package moe.rukamori.archivetune.shared.data.persistence

import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.songmetadata.SongMetadataOverride

class RoomSongMetadataRepositoryTest {
    private val song = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, "song")

    @Test fun overrideRoundTripsAndResetDeletes() = runBlocking<Unit> {
        val database = openPortableStateDatabase(":memory:")
        try {
            val repository = RoomSongMetadataRepository(database.songMetadataOverrides())
            assertTrue(repository.overrides().isEmpty())

            repository.setOverride(song, SongMetadataOverride("Edited title", listOf("Edited artist", "Second, Artist"), 7L))
            val expected = mapOf(song to SongMetadataOverride("Edited title", listOf("Edited artist", "Second, Artist"), 7L))
            assertEquals(expected, repository.overrides())
            assertEquals(expected, repository.observeOverrides().first())

            // The same song key is replaced, not duplicated.
            repository.setOverride(song, SongMetadataOverride(title = null, artistNames = listOf("Only artists"), updatedAtMs = 9L))
            assertEquals(mapOf(song to SongMetadataOverride(null, listOf("Only artists"), 9L)), repository.overrides())

            repository.setOverride(song, null)
            assertTrue(repository.overrides().isEmpty())
            assertTrue(repository.observeOverrides().first().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test fun invalidEditsAreRejectedAndEmptyEditClears() = runBlocking<Unit> {
        val database = openPortableStateDatabase(":memory:")
        try {
            val repository = RoomSongMetadataRepository(database.songMetadataOverrides())
            assertFailsWith<IllegalArgumentException> {
                repository.setOverride(song.copy(kind = ContentKind.ARTIST), SongMetadataOverride("Title"))
            }
            assertFailsWith<IllegalArgumentException> { repository.setOverride(song, SongMetadataOverride(" ")) }
            assertFailsWith<IllegalArgumentException> { repository.setOverride(song, SongMetadataOverride(null, emptyList())) }
            assertFailsWith<IllegalArgumentException> { repository.setOverride(song, SongMetadataOverride(null, listOf(" "))) }
            assertTrue(repository.overrides().isEmpty())

            // An edit with neither field stores nothing: it is a reset.
            repository.setOverride(song, SongMetadataOverride(null, null, 5L))
            assertTrue(repository.overrides().isEmpty())
        } finally {
            database.close()
        }
    }
}

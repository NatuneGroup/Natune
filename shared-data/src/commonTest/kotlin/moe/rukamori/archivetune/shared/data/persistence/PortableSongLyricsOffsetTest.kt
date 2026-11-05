package moe.rukamori.archivetune.shared.data.persistence

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.domain.lyrics.MAX_LYRICS_SYNC_OFFSET_MS
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/**
 * The store every surface reads its per-song lyrics correction from, over an in-memory
 * [PortableSongLyricsOffsetDao]. A write replaces the row so the newest offset is the only one, a null write
 * removes it, the shared ±30 s bound is refused rather than silently clamped at this layer, and non-song items
 * (episodes, videos) never reach the table.
 */
class PortableSongLyricsOffsetTest {
    private fun song(id: String) = ContentRef("youtube", ContentKind.SONG, id)

    @Test
    fun theNewestOffsetIsTheOnlyOneAndNullRemovesTheRow() = runTest {
        val dao = FakeOffsetDao()
        val store = RoomSongLyricsOffsetRepository(dao)
        val ref = song("a")

        store.setOffset(ref, 1_250)
        assertEquals(1_250L, store.offset(ref))
        assertEquals(1_250L, store.observeOffset(ref).first())

        store.setOffset(ref, -750)
        assertEquals(-750L, store.offset(ref))
        assertEquals(-750L, store.observeOffset(ref).first())

        store.setOffset(ref, null)
        assertNull(store.offset(ref))
        assertNull(store.observeOffset(ref).first())
        assertNull(dao.offset("youtube", "a"))
    }

    @Test
    fun theBoundIsRefusedRatherThanClampedHere() = runTest {
        val store = RoomSongLyricsOffsetRepository(FakeOffsetDao())
        val ref = song("a")

        store.setOffset(ref, MAX_LYRICS_SYNC_OFFSET_MS)
        store.setOffset(ref, -MAX_LYRICS_SYNC_OFFSET_MS)
        assertEquals(-MAX_LYRICS_SYNC_OFFSET_MS, store.offset(ref))
        assertFailsWith<IllegalArgumentException> { store.setOffset(ref, MAX_LYRICS_SYNC_OFFSET_MS + 1) }
        assertFailsWith<IllegalArgumentException> { store.setOffset(ref, -MAX_LYRICS_SYNC_OFFSET_MS - 1) }
        assertEquals(-MAX_LYRICS_SYNC_OFFSET_MS, store.offset(ref))
    }

    @Test
    fun nonSongItemsCarryNoOffsetAndNeverReachTheTable() = runTest {
        val dao = FakeOffsetDao()
        val store = RoomSongLyricsOffsetRepository(dao)
        val episode = ContentRef("youtube", ContentKind.EPISODE, "ep")

        assertNull(store.offset(episode))
        assertNull(store.observeOffset(episode).first())
        assertFailsWith<IllegalArgumentException> { store.setOffset(episode, 500) }
        assertNull(dao.offset("youtube", "ep"))
    }

    /** Backed by a map, not SQLite: the row reads and writes the repository reaches are served here. */
    private class FakeOffsetDao : PortableSongLyricsOffsetDao() {
        private val rows = LinkedHashMap<Pair<String, String>, Long>()

        override fun observe(provider: String, songId: String): Flow<Long?> = flowOf(rows[provider to songId])

        override suspend fun offset(provider: String, songId: String): Long? = rows[provider to songId]

        override suspend fun put(row: PortableSongLyricsOffsetRow) {
            rows[row.provider to row.songId] = row.offsetMs
        }

        override suspend fun delete(provider: String, songId: String) {
            rows.remove(provider to songId)
        }
    }
}

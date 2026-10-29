package moe.rukamori.archivetune.shared.ui.queue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.domain.repository.*

class QueuePanelModelsTest {
    @Test fun remainingTimeUsesCurrentPlayOrderAndClampsThePositionToItsSong() {
        val rows = listOf(song("b", 20_000L), song("a", 10_000L), song("c", 30_000L))
        assertEquals(QueueTimes(60_000L, 35_000L), queueTimes(rows, "a", 5_000L))
        assertEquals(30_000L, queueTimes(rows, "a", 90_000L).remainingMs)
        assertEquals(40_000L, queueTimes(rows, "a", -1L).remainingMs)
    }

    @Test fun unknownDurationsRemainUnknownRatherThanInventingAZeroLengthSong() {
        val rows = listOf(song("a", null), song("b", 20_000L))
        assertNull(queueTimes(rows, "a", 0L).totalMs)
        assertNull(queueTimes(rows, "a", 0L).remainingMs)
        assertEquals(19_000L, queueTimes(rows, "b", 1_000L).remainingMs)
        assertNull(queueTimes(rows, "missing", 0L).remainingMs)
    }

    @Test fun totalsSaturateAndFormattingKeepsHours() {
        assertEquals(Long.MAX_VALUE, queueTimes(listOf(song("a", Long.MAX_VALUE), song("b", 1L)), "a", 0L).totalMs)
        assertEquals("0:00", queueTimeString(-1L))
        assertEquals("1:05", queueTimeString(65_000L))
        assertEquals("2:03:04", queueTimeString(7_384_000L))
    }

    private fun song(id: String, durationMs: Long?) = ContentOccurrence(
        id, ContentItem(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, id), id, durationMs = durationMs),
    )
}

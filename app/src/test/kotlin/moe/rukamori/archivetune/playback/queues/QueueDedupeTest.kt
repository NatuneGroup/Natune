package moe.rukamori.archivetune.playback.queues

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDedupeTest {
    @Test
    fun anInsertKeepsOneOccurrencePerMediaId() {
        assertEquals(
            listOf("a", "b", "c"),
            QueueDedupe.incoming(currentMediaId = null, mediaIds = listOf("a", "b", "a", "c", "b")),
        )
        assertEquals(
            listOf("a"),
            QueueDedupe.incoming(currentMediaId = null, mediaIds = listOf("a", "a", "a")),
        )
    }

    @Test
    fun theSongNowPlayingIsNeverInsertedAgain() {
        assertEquals(
            listOf("b"),
            QueueDedupe.incoming(currentMediaId = "a", mediaIds = listOf("a", "b", "a")),
        )
        assertEquals(
            emptyList<String>(),
            QueueDedupe.incoming(currentMediaId = "a", mediaIds = listOf("a")),
        )
        // A null current (nothing playing) drops nothing.
        assertEquals(
            listOf("a"),
            QueueDedupe.incoming(currentMediaId = null, mediaIds = listOf("a")),
        )
    }

    @Test
    fun queuedCopiesOfAnIncomingSongEvictButTheCurrentIndexSurvives() {
        val queue = listOf("a", "b", "a", "c", "a")
        assertEquals(
            setOf(0, 2, 4),
            QueueDedupe.evictableIndices(queue, currentIndex = 1, incomingMediaIds = setOf("a")),
        )
        // Every queued copy of an incoming id goes — including "c" at index 3 — never index 1.
        assertEquals(
            setOf(0, 2, 3, 4),
            QueueDedupe.evictableIndices(queue, currentIndex = 1, incomingMediaIds = setOf("a", "c")),
        )
        assertEquals(
            emptySet<Int>(),
            QueueDedupe.evictableIndices(queue, currentIndex = 1, incomingMediaIds = emptySet()),
        )
        // An unset current index still evicts every queued copy.
        assertEquals(
            setOf(0, 2, 4),
            QueueDedupe.evictableIndices(queue, currentIndex = -1, incomingMediaIds = setOf("a")),
        )
    }

    @Test
    fun theInsertAdapterDeduplicatesTheBatchItself() {
        // The service filters the incoming items by the kept ids and then dedupes them: an
        // [a, a] insert lands exactly once, and the kept set alone would pass both copies.
        val kept = QueueDedupe.incoming(currentMediaId = null, mediaIds = listOf("a", "a", "b")).toSet()
        assertEquals(
            listOf("a", "b"),
            QueueDedupe.survivingItems(listOf("a", "a", "b"), kept) { it },
        )
        // A copy of the song now playing does not come back through the filter either.
        val keptWithoutCurrent =
            QueueDedupe.incoming(currentMediaId = "a", mediaIds = listOf("a", "a", "b")).toSet()
        assertEquals(
            listOf("b"),
            QueueDedupe.survivingItems(listOf("a", "a", "b"), keptWithoutCurrent) { it },
        )
    }

    @Test
    fun unrelatedQueuedSongsAreNotEvictable() {
        assertTrue(
            QueueDedupe
                .evictableIndices(
                    queueMediaIds = listOf("x", "y", "z"),
                    currentIndex = 0,
                    incomingMediaIds = setOf("a", "b"),
                ).isEmpty(),
        )
    }
}

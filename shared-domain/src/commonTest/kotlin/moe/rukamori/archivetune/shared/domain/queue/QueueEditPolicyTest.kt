package moe.rukamori.archivetune.shared.domain.queue

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.domain.repository.*

class QueueEditPolicyTest {
    @Test fun playNextFromHistoryUsesTheCurrentPositionAfterRemovingTheSource() {
        val queue = queue("history", "current", "tail").copy(currentOccurrenceId = "current")
        val moved = QueueEditPolicy.moveNext(queue, "history")
        assertEquals(listOf("current", "history", "tail"), moved.playOrder)
        assertEquals("current", moved.currentOccurrenceId)
        assertEquals(moved.playOrder, moved.entries.map { it.occurrenceId })
    }

    @Test fun shuffledMovePreservesTimelineAndManualKinds() {
        val queue = queue("a", "b", "c").copy(
            shuffleEnabled = true, playOrder = listOf("b", "c", "a"), currentOccurrenceId = "c",
            manualKinds = mapOf("b" to UserQueuedKind.ADD_TO_QUEUE),
        )
        val moved = QueueEditPolicy.moveInPlayOrder(queue, "a", "c")
        assertEquals(listOf("b", "a", "c"), moved.playOrder)
        assertEquals(queue.entries, moved.entries)
        assertEquals(queue.manualKinds, moved.manualKinds)
    }

    @Test fun shuffleAcceptsRadioButNeverAnIncompleteFiniteTail() {
        val collection = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.PLAYLIST,
            parent = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PLAYLIST, "PL-fixture"))
        val queue = queue("a", "b", "c").copy(tail = QueueTail(collection, "next"))
        assertNull(QueueEditPolicy.shuffle(queue, true, Random(2)))
        val radio = queue.copy(tail = queue.tail?.copy(kind = QueueCollectionKind.RADIO))
        val shuffled = assertNotNull(QueueEditPolicy.shuffle(radio, true, Random(2)))
        assertEquals("a", shuffled.playOrder.first())
        assertEquals(radio.entries, shuffled.entries)
    }

    @Test fun theLegacyTimelineMoveDoesNotRewriteAShuffledPlayOrder() {
        val queue = queue("a", "b", "c").copy(shuffleEnabled = true, playOrder = listOf("c", "a", "b"))
        val moved = QueueEditPolicy.moveInTimeline(queue, "c", "a")
        assertEquals(listOf("c", "a", "b"), moved.entries.map { it.occurrenceId })
        assertEquals(queue.playOrder, moved.playOrder)
        assertEquals(queue.currentOccurrenceId, moved.currentOccurrenceId)
    }

    private fun queue(vararg ids: String) = QueueState(
        1L, ids.map { ContentOccurrence(it, ContentItem(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, it), it)) },
        ids.firstOrNull(), ids.toList(),
    )
}

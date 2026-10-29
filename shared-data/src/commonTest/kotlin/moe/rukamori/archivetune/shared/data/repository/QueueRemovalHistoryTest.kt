package moe.rukamori.archivetune.shared.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.*

class QueueRemovalHistoryTest {
    @Test fun duplicateMediaAreRestoredByOccurrenceBesideSurvivingNeighbours() {
        val a = song("a", "same")
        val b = song("b", "same")
        val c = song("c")
        val original = queue(a, b, c)
        val history = QueueRemovalHistory()
        val ticket = assertNotNull(history.remember(original, setOf("b")))
        val live = queue(song("new"), a, c).copy(currentOccurrenceId = "c", revision = 9L)
        val restored = assertNotNull(history.restore(ticket, live))
        assertEquals(listOf("new", "a", "b", "c"), restored.playOrder)
        assertEquals("c", restored.currentOccurrenceId)
        assertEquals(10L, restored.revision)
        assertEquals(2, restored.entries.count { it.item.ref.id == "same" })
        assertNull(history.restore(ticket, restored))
    }

    @Test fun shuffledUndoPreservesLiveReorderAndManualKinds() {
        val original = queue(song("a"), song("b"), song("c")).copy(
            shuffleEnabled = true, playOrder = listOf("c", "b", "a"),
            manualKinds = mapOf("b" to UserQueuedKind.PLAY_NEXT),
        )
        val history = QueueRemovalHistory()
        val ticket = assertNotNull(history.remember(original, setOf("b")))
        val live = queue(song("a"), song("c"), song("new")).copy(
            shuffleEnabled = true, playOrder = listOf("a", "new", "c"), currentOccurrenceId = "c",
            manualKinds = mapOf("new" to UserQueuedKind.ADD_TO_QUEUE),
        )
        val restored = assertNotNull(history.restore(ticket, live))
        assertEquals(listOf("b", "a", "new", "c"), restored.playOrder)
        assertEquals(listOf("a", "b", "c", "new"), restored.entries.map { it.occurrenceId })
        assertEquals("c", restored.currentOccurrenceId)
        assertEquals(mapOf("b" to UserQueuedKind.PLAY_NEXT, "new" to UserQueuedKind.ADD_TO_QUEUE), restored.manualKinds)
    }

    @Test fun unrelatedRemovalIsNotUndoneAndExistingOccurrenceIsNotInsertedTwice() {
        val original = queue(song("a"), song("b"), song("c"))
        val history = QueueRemovalHistory()
        val ticket = assertNotNull(history.remember(original, setOf("b")))
        val live = queue(song("b"), song("c"))
        val restored = assertNotNull(history.restore(ticket, live))
        assertEquals(listOf("b", "c"), restored.playOrder)
    }

    @Test fun replacementAndANewerSnackbarInvalidateOldTickets() {
        val original = queue(song("a"), song("b"))
        val history = QueueRemovalHistory()
        val first = assertNotNull(history.remember(original, setOf("b")))
        val second = assertNotNull(history.remember(original, setOf("a")))
        assertNull(history.restore(first, original))
        history.reset()
        assertNull(history.restore(second, original))
        assertNull(history.remember(original, setOf("missing")))
    }

    private fun song(occurrence: String, media: String = occurrence) = ContentOccurrence(
        occurrence, ContentItem(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, media), media),
    )
    private fun queue(vararg songs: ContentOccurrence) = QueueState(
        1L, songs.toList(), songs.firstOrNull()?.occurrenceId, songs.map { it.occurrenceId },
    )
}

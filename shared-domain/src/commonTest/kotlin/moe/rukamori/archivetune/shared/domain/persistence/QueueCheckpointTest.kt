/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import moe.rukamori.archivetune.shared.domain.repository.*

class QueueCheckpointTest {
    private val song = ContentItem(ContentRef("youtube", ContentKind.SONG, "same"), "Same")
    private val queue = QueueState(3, listOf(ContentOccurrence("a", song), ContentOccurrence("b", song)),
        "b", listOf("b", "a"))

    @Test fun duplicatesKeepOccurrenceAndPlayOrder() {
        val checkpoint = QueueCheckpoint(queue, 500)
        assertEquals(listOf("b", "a"), checkpoint.queue.playOrder)
    }
    @Test fun rejectsDuplicateOccurrences() {
        assertFailsWith<IllegalArgumentException> {
            QueueCheckpoint(queue.copy(entries = listOf(queue.entries[0], queue.entries[0])), 0)
        }
    }
    @Test fun rejectsInvalidPermutationAndCurrentOccurrence() {
        assertFailsWith<IllegalArgumentException> { QueueCheckpoint(queue.copy(playOrder = listOf("a", "a")), 0) }
        assertFailsWith<IllegalArgumentException> { QueueCheckpoint(queue.copy(currentOccurrenceId = "missing"), 0) }
    }
}

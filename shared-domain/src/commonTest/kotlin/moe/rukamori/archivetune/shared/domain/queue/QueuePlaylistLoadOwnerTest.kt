/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.queue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Characterizes single ownership of the active playlist load. A result from a replaced, cancelled or
 * finished load must never commit into a newer queue.
 */
class QueuePlaylistLoadOwnerTest {
    private val queueA = Any()
    private val queueB = Any()

    @Test
    fun aLoadAlreadyRunningForTheSameGenerationAndQueueIsNotStartedTwice() {
        val owner = PlaylistLoadOwner<Any>()

        assertNotNull(owner.begin(1L, queueA))
        assertNull(owner.begin(1L, queueA))
        assertNotNull(owner.begin(1L, queueB), "a different queue is a different load")
    }

    @Test
    fun aNewerLoadReplacesTheOlderTicketAndTheOlderResultIsRejected() {
        val owner = PlaylistLoadOwner<Any>()
        val older = assertNotNull(owner.begin(1L, queueA))
        val newer = assertNotNull(owner.begin(2L, queueA))
        var committed = false

        assertFalse(older.isCurrent(1L, queueA))
        assertFalse(older.acceptIfCurrent(2L, queueA) { fail("a stale result must not commit") })
        assertTrue(newer.acceptIfCurrent(2L, queueA) { committed = true })
        assertTrue(committed)
    }

    @Test
    fun aTicketIsCurrentOnlyForItsOwnGenerationAndQueue() {
        val owner = PlaylistLoadOwner<Any>()
        val ticket = assertNotNull(owner.begin(1L, queueA))

        assertTrue(ticket.isCurrent(1L, queueA))
        assertFalse(ticket.isCurrent(2L, queueA))
        assertFalse(ticket.isCurrent(1L, queueB))
    }

    @Test
    fun cancelMakesEveryOutstandingTicketStale() {
        val owner = PlaylistLoadOwner<Any>()
        val ticket = assertNotNull(owner.begin(1L, queueA))

        owner.cancel()

        assertFalse(ticket.isCurrent(1L, queueA))
        assertNotNull(owner.begin(1L, queueA), "the same load may start again after a cancel")
    }

    @Test
    fun finishingAnOlderTicketDoesNotReleaseTheNewerLoad() {
        val owner = PlaylistLoadOwner<Any>()
        val older = assertNotNull(owner.begin(1L, queueA))
        val newer = assertNotNull(owner.begin(2L, queueA))

        older.finish()

        assertTrue(newer.isCurrent(2L, queueA))
        assertNull(owner.begin(2L, queueA))
        newer.finish()
        assertNotNull(owner.begin(2L, queueA))
    }

    @Test
    fun aPlaylistActionTicketIsCurrentOnlyWhenBothFieldsMatch() {
        val ticket = PlaylistActionTicket(operation = 3L, queueGeneration = 9L)

        assertTrue(ticket.isCurrent(3L, 9L))
        assertFalse(ticket.isCurrent(4L, 9L))
        assertFalse(ticket.isCurrent(3L, 8L))
        assertEquals(PlaylistActionTicket(3L, 9L), ticket)
    }
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.queue

/** Identifies one queue operation. A result is accepted only while both fields are still current. */
data class PlaylistActionTicket(val operation: Long, val queueGeneration: Long) {
    fun isCurrent(currentOperation: Long, currentQueueGeneration: Long): Boolean =
        operation == currentOperation && queueGeneration == currentQueueGeneration
}

/**
 * Owns the one active playlist load. A load that is already running for the same generation and
 * queue is not started twice. A newer load replaces it, and the replaced load's ticket goes stale.
 *
 * Callers confine this to the player thread. It records ownership, not transport cancellation, so a
 * load that already left the network can still finish and be rejected here.
 */
class PlaylistLoadOwner<Q : Any> {
    private var active: Ticket? = null

    /** A new ticket, or null when this same load is already active. */
    fun begin(generation: Long, queue: Q): Ticket? {
        if (active?.isCurrent(generation, queue) == true) return null
        return Ticket(generation, queue).also { active = it }
    }

    fun cancel() {
        active = null
    }

    inner class Ticket internal constructor(
        private val generation: Long,
        private val queue: Q,
    ) {
        fun isCurrent(currentGeneration: Long, currentQueue: Q): Boolean =
            active === this && generation == currentGeneration && queue === currentQueue

        /** Runs [effects] only while this ticket is still the current load. */
        fun acceptIfCurrent(currentGeneration: Long, currentQueue: Q, effects: () -> Unit): Boolean {
            if (!isCurrent(currentGeneration, currentQueue)) return false
            effects()
            return true
        }

        fun finish() {
            if (active === this) active = null
        }
    }
}

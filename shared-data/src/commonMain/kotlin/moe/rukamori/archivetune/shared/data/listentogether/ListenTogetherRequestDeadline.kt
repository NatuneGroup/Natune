/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.listentogether

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One bounded wait, such as a connection attempt or a room request. Every call must run on the owner's serial scope.
 * The generation counter and the timer are plain fields, which is why no lock or atomic is needed: a timer that fires
 * after its wait was completed or superseded finds a different generation and does nothing.
 */
internal class ListenTogetherRequestDeadline(private val scope: CoroutineScope) {
    private var generation = 0L
    private var timer: Job? = null

    /** Starts a wait that runs [onTimeout] unless it is completed, cancelled or superseded. Returns the attempt id. */
    fun start(timeoutMs: Long, onTimeout: () -> Unit): Long {
        require(timeoutMs > 0) { "deadline must be positive" }
        timer?.cancel()
        val attempt = ++generation
        timer = scope.launch {
            delay(timeoutMs)
            if (claimTimeout(attempt)) onTimeout()
        }
        return attempt
    }

    fun isCurrent(attempt: Long): Boolean = attempt == generation

    fun currentAttempt(): Long = generation

    /**
     * Runs [block] only while [attempt] is still current. With [requirePending] the wait must also still be unfinished,
     * so a retry scheduled for a request that already got its answer does nothing.
     */
    fun runIfCurrent(attempt: Long, requirePending: Boolean = false, block: () -> Unit) {
        if (!isCurrent(attempt)) return
        if (requirePending && timer == null) return
        block()
    }

    /**
     * Starts a wait only when none is pending. Returns null and changes nothing when one is already running, which is
     * how a second room request is refused while the first is still in flight.
     */
    fun startIfIdle(timeoutMs: Long, onTimeout: () -> Unit): Long? =
        if (timer != null) null else start(timeoutMs, onTimeout)

    /** Completes the pending wait when [payload] arrived, and returns the payload so callers can chain with an elvis. */
    fun <T : Any> completeWithPayload(payload: T?): T? {
        if (payload != null) complete()
        return payload
    }

    /** Ends the pending wait without invalidating earlier attempt ids. */
    fun complete() {
        timer?.cancel()
        timer = null
    }

    /** Ends the pending wait and makes every earlier attempt id stale. */
    fun cancel() {
        generation++
        complete()
    }

    private fun claimTimeout(attempt: Long): Boolean {
        if (!isCurrent(attempt) || timer == null) return false
        timer = null
        return true
    }
}

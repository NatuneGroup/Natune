package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Shares one in-flight computation per [K] among concurrent callers.
 *
 * The first caller for a key starts the work as a child of the owning scope. Later callers that
 * arrive while it runs join the same [Deferred] and await its value. Only overlapping calls share:
 * once the work finishes, its entry is removed, so the next call starts fresh. Results that are worth
 * keeping belong to the result cache, not here.
 *
 * Cancellation rules:
 * - A caller's cancellation cancels only that caller's `await`. The shared work keeps running for the
 *   remaining waiters.
 * - When the last waiter leaves before the work finishes, the work is cancelled and its entry is
 *   removed synchronously, so a caller arriving next starts a new computation instead of joining a
 *   cancelled one.
 * - A failed computation removes its entry from its own body, so the next call retries.
 *
 * The scope gets a [SupervisorJob] of its own so one failed computation cannot cancel the owner.
 */
internal class LyricsSingleFlight<K : Any, V>(
    scope: CoroutineScope,
) {
    private val flightScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val lock = Mutex()
    private val flights = HashMap<K, Flight<V>>()

    private class Flight<T>(
        val deferred: Deferred<T>,
    ) {
        /** Only read or written under the flight lock. */
        var waiters: Int = 1
    }

    suspend fun execute(
        key: K,
        work: suspend () -> V,
    ): V {
        val flight = lock.withLock { joinOrStart(key, work) }
        try {
            return flight.deferred.await()
        } finally {
            withContext(NonCancellable) { leave(key, flight) }
        }
    }

    /** Must be called with [lock] held. */
    private fun joinOrStart(
        key: K,
        work: suspend () -> V,
    ): Flight<V> {
        val existing = flights[key]
        if (existing != null) {
            existing.waiters++
            return existing
        }
        // LAZY so the body cannot finish before the entry is registered; otherwise its removal could
        // run first and leave a stale entry behind.
        val deferred =
            flightScope.async(start = CoroutineStart.LAZY) {
                val self = coroutineContext[Job]
                try {
                    work()
                } finally {
                    withContext(NonCancellable) { forgetIfCurrent(key, self) }
                }
            }
        val flight = Flight(deferred)
        flights[key] = flight
        deferred.start()
        return flight
    }

    private suspend fun leave(
        key: K,
        flight: Flight<V>,
    ) {
        lock.withLock {
            flight.waiters--
            if (flight.waiters == 0 && !flight.deferred.isCompleted) {
                flight.deferred.cancel()
                if (flights[key] === flight) flights.remove(key)
            }
        }
    }

    private suspend fun forgetIfCurrent(
        key: K,
        self: Job?,
    ) {
        lock.withLock {
            if (flights[key]?.deferred === self) flights.remove(key)
        }
    }
}

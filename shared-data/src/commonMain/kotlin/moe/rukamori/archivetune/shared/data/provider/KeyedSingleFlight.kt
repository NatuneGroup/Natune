package moe.rukamori.archivetune.shared.data.provider

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One in-flight operation per key at a time, with a compare-inside-the-lock re-check: every caller
 * answers [satisfied] while holding the key's gate, so a caller that queued behind an in-flight
 * run returns that run's result instead of running [block] a second time. Concurrent failures for
 * the same account therefore cause exactly one refresh or re-lease, the way librespot's
 * synchronized getToken and python-tidal's refresh lock behave.
 *
 * Gates live for the lifetime of the instance; keys are bounded (a pool names a handful of
 * "service:id" pairs per host), so the map cannot grow without limit.
 */
class KeyedSingleFlight {
    private val gatesLock = Mutex()
    private val gates = HashMap<String, Mutex>()

    /**
     * Serializes work per [key]. [satisfied] is re-answered inside the gate by every caller — the
     * first one through answers null and runs [block]; waiters that held the gate after the winner
     * released it answer the fresh result and skip the work entirely.
     */
    suspend fun <T> run(key: String, satisfied: suspend () -> T?, block: suspend () -> T): T {
        val gate = gatesLock.withLock { gates.getOrPut(key) { Mutex() } }
        return gate.withLock { satisfied() ?: block() }
    }
}

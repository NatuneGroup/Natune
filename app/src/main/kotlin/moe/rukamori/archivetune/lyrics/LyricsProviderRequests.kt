package moe.rukamori.archivetune.lyrics

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Extensions cannot occupy the built-in budget while waiting for the serial module runtime. */
internal class LyricsProviderRequests {
    private val builtInPermits = Semaphore(6)
    private val extensionPermit = Semaphore(1)

    suspend fun <T> run(isExtension: Boolean, timeoutMillis: Long, request: suspend () -> T): T? =
        if (isExtension) {
            // Bound queueing as well as execution: many installed extensions get one timeout
            // window together, rather than delaying built-in results by one window per batch.
            withTimeoutOrNull(timeoutMillis) { extensionPermit.withPermit { request() } }
        } else {
            builtInPermits.withPermit { withTimeoutOrNull(timeoutMillis) { request() } }
        }
}

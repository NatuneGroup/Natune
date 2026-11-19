
package moe.rukamori.archivetune.spotify

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Covers the whole refresh or account mutation, including suspended persistence and cache reset. */
class StreamingAccountMutationLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

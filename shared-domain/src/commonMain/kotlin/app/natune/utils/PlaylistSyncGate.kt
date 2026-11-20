package app.natune.utils

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The whole fetch/reconcile cycle shares one gate across saved, manual and automatic playlist sync. */
class PlaylistSyncGate {
    private val mutex = Mutex()

    suspend fun <T> withLock(operation: suspend () -> T): T = mutex.withLock { operation() }
}

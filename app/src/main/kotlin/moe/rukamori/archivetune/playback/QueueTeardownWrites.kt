package moe.rukamori.archivetune.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Register before dispatch so a replacement service also sees writes still waiting for IO. */
internal class QueueTeardownWrites {
    private val lock = Any()
    private val pending = mutableSetOf<Job>()

    fun launch(scope: CoroutineScope, write: suspend () -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { write() }
        synchronized(lock) { pending += job }
        job.invokeOnCompletion { synchronized(lock) { pending -= job } }
        job.start()
        return job
    }

    /** Called before reading either file and before restoration advances the save generation. */
    suspend fun awaitPendingWrites() {
        val writes = synchronized(lock) { pending.toList() }
        writes.forEach { it.join() }
    }
}

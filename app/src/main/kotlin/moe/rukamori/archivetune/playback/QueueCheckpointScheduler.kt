package moe.rukamori.archivetune.playback

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-thread requests coalesce before reading the player, including requests made during IO. */
internal class QueueCheckpointScheduler<Snapshot>(
    private val scope: () -> CoroutineScope,
    private val generation: () -> Long,
    private val enabled: suspend () -> Boolean,
    private val blocked: () -> Boolean,
    private val snapshot: () -> Snapshot?,
    private val write: suspend (Snapshot) -> Unit,
    private val debounceMs: Long = 100L,
) {
    private var pending = false
    private var revision = 0L
    private var requestedGeneration = 0L
    private var writer: Job? = null

    fun request() {
        pending = true
        revision++
        requestedGeneration = generation()
        if (writer?.isActive == true) return
        val nextWriter = scope().launch(start = CoroutineStart.LAZY) {
            try {
                while (pending) {
                    val observedRevision = revision
                    delay(debounceMs)
                    if (observedRevision != revision) continue
                    if (requestedGeneration != generation()) {
                        pending = false
                        return@launch
                    }
                    val shouldSave = enabled()
                    if (observedRevision != revision) continue
                    if (!shouldSave || requestedGeneration != generation()) {
                        pending = false
                        return@launch
                    }
                    // Phase completion must request again; never snapshot a partial restored queue.
                    if (blocked()) return@launch
                    pending = false
                    snapshot()?.let { write(it) }
                }
            } finally {
                if (writer === coroutineContext[Job]) writer = null
            }
        }
        writer = nextWriter
        nextWriter.start()
    }
}

internal data class QueueCheckpointToken(val saveGeneration: Long, val sequence: Long)

/** Snapshot tokens also order teardown's independent writer. Writes require the persistence lock. */
internal class QueueCheckpointOrder(private val generation: () -> Long) {
    private val sequence = AtomicLong(0L)
    private var lastWrittenSequence = 0L
    private var lastWrittenPlayerStateSequence = 0L

    fun nextSnapshot(): QueueCheckpointToken = QueueCheckpointToken(generation(), sequence.incrementAndGet())

    fun writeIfCurrent(token: QueueCheckpointToken, write: () -> Unit): Boolean {
        if (token.saveGeneration != generation() || token.sequence <= lastWrittenSequence) return false
        // Even a failed newer write must not let an older snapshot resurrect obsolete contents.
        lastWrittenSequence = token.sequence
        write()
        return true
    }

    // A position-only save can supersede resume state without discarding a pending queue checkpoint.
    fun writePlayerStateIfCurrent(token: QueueCheckpointToken, write: () -> Unit): Boolean {
        if (token.saveGeneration != generation() || token.sequence <= lastWrittenPlayerStateSequence) return false
        lastWrittenPlayerStateSequence = token.sequence
        write()
        return true
    }
}

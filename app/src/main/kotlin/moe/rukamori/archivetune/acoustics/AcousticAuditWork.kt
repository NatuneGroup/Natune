package moe.rukamori.archivetune.acoustics

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticAuditLimits
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticUnavailable

/** The Android probe's read budgets: bytes, seconds and stall windows one audit may spend. */
internal object AcousticReadLimits {
    const val STALL_MS = 5_000L
    const val NETWORK_CALL_MS = 2_000L
    const val MAX_READ_BYTES = 32L * 1024 * 1024
    const val MAX_READ_CHUNK = 64 * 1024
}

/**
 * One audit run's owned resources and its hard deadline. The watchdog closes the owned source at
 * [AcousticAuditLimits.DEADLINE_MS] from its own thread, so a read parked in a provider or a socket call
 * that never returns still releases its worker permit bounded; [check] additionally honours the shared
 * run's cancellation and deadline at every step of the loop.
 */
internal class AcousticWork(
    private val clock: () -> Long = System::nanoTime,
    private val run: (() -> Unit)? = null,
) : Closeable {
    companion object {
        private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "AcousticAuditDeadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
    private val cancelled = AtomicBoolean(false)
    private val source = AtomicReference<Closeable?>(null)
    private val deadline = clock() + AcousticAuditLimits.DEADLINE_MS * 1_000_000
    private val watchdog = deadlines.schedule({
        runCatching { source.getAndSet(null)?.close() }
    }, AcousticAuditLimits.DEADLINE_MS, TimeUnit.MILLISECONDS)

    fun check() {
        if (cancelled.get()) throw CancellationException()
        run?.invoke()
        if (clock() >= deadline) throw AcousticUnavailable("deadline_exceeded")
    }

    fun own(value: Closeable) {
        source.set(value)
        if (cancelled.get()) {
            source.getAndSet(null)?.close()
            throw CancellationException()
        }
        check()
    }

    override fun close() {
        cancelled.set(true)
        watchdog.cancel(false)
        runCatching { source.getAndSet(null)?.close() }
    }
}

internal object AcousticWorkers {
    // A slow OS provider/DNS call may outlive cancellation. Keep its lease until it really
    // unwinds, rather than spawning another blocking worker on every retry or target change.
    val permits = Semaphore(2)
    val dispatcher = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2),
        { task -> Thread(task, "AcousticAuditWorker").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    ).asCoroutineDispatcher()
}

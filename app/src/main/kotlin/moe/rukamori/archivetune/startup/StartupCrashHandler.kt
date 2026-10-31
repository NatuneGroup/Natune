package moe.rukamori.archivetune.startup

import java.util.concurrent.atomic.AtomicBoolean

internal class StartupCrashHandler(
    private val capture: (Thread, Throwable) -> Unit,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val terminate: () -> Unit,
) : Thread.UncaughtExceptionHandler {
    @Volatile var applicationHandler: Thread.UncaughtExceptionHandler? = null
    private val handling = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (!handling.compareAndSet(false, true)) return
        try {
            try {
                capture(thread, throwable)
            } catch (_: Throwable) {
                // Disk-full and capture failures must never prevent the runtime/legacy handler.
            }
            (applicationHandler ?: previous)?.uncaughtException(thread, throwable)
        } finally {
            terminate()
        }
    }
}

package moe.rukamori.archivetune.desktop.playback

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Opens one closeable on the IO dispatcher and hands its ownership to the caller.
 *
 * `withContext` owns the value only until it resumes the caller. If the caller is cancelled while the
 * native open is returning, the value is discarded before the caller can assign it. Recording the
 * resource inside the IO block lets this function close it before rethrowing that cancellation.
 *
 * On success the caller owns the resource and must close it exactly once; this function never closes
 * a resource it returned.
 */
internal suspend fun <T : AutoCloseable> openWithOwnership(open: suspend () -> T?): T? {
    val created = AtomicReference<T?>(null)
    try {
        val resource = withContext(Dispatchers.IO) {
            val opened = open() ?: return@withContext null
            created.set(opened)
            currentCoroutineContext().ensureActive()
            opened
        }
        created.set(null)
        return resource
    } catch (cancelled: CancellationException) {
        created.getAndSet(null)?.closeAfterHandoffFailure()
        throw cancelled
    } catch (failure: Throwable) {
        created.getAndSet(null)?.closeAfterHandoffFailure()
        throw failure
    }
}

/** A failed handoff is already reporting the original failure; its cleanup must not replace it. */
private fun AutoCloseable.closeAfterHandoffFailure() {
    try {
        close()
    } catch (_: Throwable) {
    }
}

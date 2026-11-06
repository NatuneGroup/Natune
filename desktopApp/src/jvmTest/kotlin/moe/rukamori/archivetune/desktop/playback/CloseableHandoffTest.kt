package moe.rukamori.archivetune.desktop.playback

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class CloseableHandoffTest {
    private class Resource : AutoCloseable {
        val closes = AtomicInteger()
        var closeFailure: Throwable? = null

        override fun close() {
            closes.incrementAndGet()
            closeFailure?.let { throw it }
        }
    }

    @Test
    fun normalDeliveryLeavesTheResourceToTheCaller() = runTest {
        val resource = Resource()
        val opened = openWithOwnership { resource }
        assertSame(resource, opened)
        assertEquals(0, resource.closes.get(), "the helper never closes a delivered resource")
        opened?.close()
        assertEquals(1, resource.closes.get())
    }

    @Test
    fun cancellationAfterCreationBeforeDeliveryClosesOnce() = runTest {
        val resource = Resource()
        val job = launch {
            openWithOwnership {
                currentCoroutineContext().job.cancel()
                resource
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, resource.closes.get())
    }

    @Test
    fun cancellationBeforeCreationLeavesNothingToClose() = runTest {
        val job = launch {
            openWithOwnership<Resource> {
                currentCoroutineContext().job.cancel()
                throw CancellationException("cancelled before open returns")
            }
        }
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun deliveredReaderIsClosedOnceWhenTheConsumerIsCancelled() = runTest {
        val resource = Resource()
        val delivered = CompletableDeferred<Unit>()
        val job = launch {
            val reader = openWithOwnership { resource } ?: return@launch
            try {
                delivered.complete(Unit)
                awaitCancellation()
            } finally {
                reader.close()
            }
        }
        delivered.await()
        assertEquals(0, resource.closes.get())
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, resource.closes.get())
    }

    @Test
    fun nullOpenProducesNoResource() = runTest {
        assertNull(openWithOwnership<Resource> { null })
    }

    @Test
    fun openFailureLeavesNothingToClose() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            openWithOwnership<Resource> { error("open failed") }
        }
        assertEquals("open failed", failure.message)
    }

    @Test
    fun closeFailureDoesNotReplaceCancellation() = runTest {
        val resource = Resource().apply { closeFailure = IllegalStateException("close failed") }
        val job = launch {
            openWithOwnership {
                currentCoroutineContext().job.cancel()
                resource
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, resource.closes.get())
    }

    @Test
    fun theOldDirectWithContextPatternLosesTheResourceWhenDeliveryIsCancelled() = runTest {
        val resource = Resource()
        val job = launch {
            oldDirectWithContext {
                currentCoroutineContext().job.cancel()
                resource
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        // Negative control: withContext discards the returned handle without a handoff guard.
        assertEquals(0, resource.closes.get())
    }

    private suspend fun <T : AutoCloseable> oldDirectWithContext(open: suspend () -> T?): T? =
        withContext(Dispatchers.IO) { open() }
}

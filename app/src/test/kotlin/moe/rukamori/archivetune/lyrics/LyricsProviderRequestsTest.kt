package moe.rukamori.archivetune.lyrics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsProviderRequestsTest {
    @Test
    fun slowExtensionsDoNotQueueBuiltInsAndShareOneTimeoutWindow() = runTest {
        val requests = LyricsProviderRequests()
        val blocked = CompletableDeferred<String>()
        var startedExtensions = 0
        val extensions = List(12) {
            async { requests.run(true, 8_000) { startedExtensions++; blocked.await() } }
        }
        runCurrent()
        val builtIn = async { requests.run(false, 8_000) { "built-in lyrics" } }
        runCurrent()

        assertTrue(builtIn.isCompleted)
        assertEquals("built-in lyrics", builtIn.await())
        assertEquals(1, startedExtensions)
        advanceUntilIdle()
        assertEquals(List<String?>(12) { null }, extensions.awaitAll())
        assertEquals(8_000L, currentTime)
    }

    @Test
    fun cancellingAnExtensionReleasesItsSlotAndPreservesCallerCancellation() = runTest {
        val requests = LyricsProviderRequests()
        val blocked = CompletableDeferred<String>()
        val owner = async { requests.run(true, 8_000) { blocked.await() } }
        runCurrent()
        val waiter = async { requests.run(true, 8_000) { "next lyrics" } }
        runCurrent()
        assertFalse(waiter.isCompleted)

        owner.cancel()
        runCurrent()
        assertTrue(owner.isCancelled)
        assertEquals("next lyrics", waiter.await())
    }
}

package moe.rukamori.archivetune.playback

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadConnectionPrewarmerTest {
    private fun newPrewarmer(
        hosts: List<String>,
        dispatcher: CoroutineDispatcher,
        probe: suspend (String) -> Unit,
    ) = DownloadConnectionPrewarmer(hosts = hosts, probe = probe, dispatcher = dispatcher)

    @Test
    fun repeatedCallsShareOneSerialPassInsteadOfStartingAnother() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val probed = mutableListOf<String>()
        val prewarmer =
            newPrewarmer(listOf("a", "b"), StandardTestDispatcher(testScheduler)) { host ->
                probed += host
                started.complete(Unit)
                gate.await()
            }

        val first = prewarmer.prewarm()
        val overlapping = prewarmer.prewarm()
        started.await()

        assertNotNull(first)
        assertNull(overlapping)
        assertEquals(listOf("a"), probed)

        gate.complete(Unit)
        first!!.join()
        assertEquals(listOf("a", "b"), probed)

        val later = prewarmer.prewarm()
        assertNotNull(later)
        later!!.join()
        assertEquals(listOf("a", "b", "a", "b"), probed)
        prewarmer.close()
    }

    @Test
    fun closeCancelsTheRunningPassWithinItsTimeoutAndSkipsTheRemainingHosts() = runTest {
        val started = CompletableDeferred<Unit>()
        val probed = mutableListOf<String>()
        val prewarmer =
            newPrewarmer(listOf("a", "b"), StandardTestDispatcher(testScheduler)) { host ->
                probed += host
                started.complete(Unit)
                awaitCancellation()
            }

        val pass = prewarmer.prewarm()!!
        started.await()
        prewarmer.close()
        withTimeout(1_000) { pass.join() }

        assertTrue(pass.isCancelled)
        assertEquals(listOf("a"), probed)
        assertNull(prewarmer.prewarm())
        assertEquals(listOf("a"), probed)
    }

    @Test
    fun aFailedProbeDoesNotStopThePass() = runTest {
        val probed = mutableListOf<String>()
        val prewarmer =
            newPrewarmer(listOf("a", "b", "c"), StandardTestDispatcher(testScheduler)) { host ->
                probed += host
                if (host == "b") throw IOException("fixture failure")
            }

        val pass = prewarmer.prewarm()!!
        pass.join()

        assertEquals(listOf("a", "b", "c"), probed)
        assertFalse(pass.isCancelled)
        prewarmer.close()
    }

    @Test
    fun closeWithoutARunningPassIsPermanentAndSafeToRepeat() = runTest {
        var probes = 0
        val prewarmer =
            newPrewarmer(listOf("a"), StandardTestDispatcher(testScheduler)) {
                probes++
            }

        prewarmer.close()
        prewarmer.close()
        assertNull(prewarmer.prewarm())

        assertEquals(0, probes)
    }
}

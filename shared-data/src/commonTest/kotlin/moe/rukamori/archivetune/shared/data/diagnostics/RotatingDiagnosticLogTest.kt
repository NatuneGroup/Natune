package moe.rukamori.archivetune.shared.data.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.domain.diagnostics.DiagnosticLevel

/**
 * Runs a log test with a writer scope on the test scheduler, not `backgroundScope`: `advanceUntilIdle`
 * does not drive background coroutines, so the writer loop would stall until the test body suspended.
 * The scope is cancelled and joined at the end, so no writer survives its test.
 */
private fun runLogTest(block: suspend TestScope.(scope: CoroutineScope) -> Unit): TestResult =
    runTest {
        val job = SupervisorJob()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
        try {
            block(scope)
        } finally {
            job.cancelAndJoin()
        }
    }

class RotatingDiagnosticLogTest {
    private class MemoryFiles(initial: Map<String, String> = emptyMap()) : DiagnosticLogFiles {
        val contents = LinkedHashMap(initial.mapValues { StringBuilder(it.value) })

        override suspend fun list() = contents.map { (name, text) -> DiagnosticLogFile(name, text.toString().encodeToByteArray().size.toLong()) }

        override suspend fun append(name: String, text: String): Boolean {
            contents.getOrPut(name) { StringBuilder() }.append(text)
            return true
        }

        override suspend fun readTail(name: String, maxBytes: Long): ByteArray {
            val bytes = contents[name]?.toString()?.encodeToByteArray() ?: return ByteArray(0)
            return bytes.copyOfRange((bytes.size - maxBytes.toInt()).coerceAtLeast(0), bytes.size)
        }

        override suspend fun delete(name: String) {
            contents.remove(name)
        }
    }

    @Test
    fun appendedLinesReachTheViewerAndClearHidesThem() = runLogTest { scope ->
        var now = 1_000L
        val files = MemoryFiles()
        val log = RotatingDiagnosticLog(files, scope, sessionStartMillis = 500L, nowMillis = { now }, formatTime = { "t$it" }, sessionHeader = "start")

        log.append(DiagnosticLevel.INFO, "Queue", "restored")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("restored"), log.readForViewer().map { it.message })
        assertTrue(files.contents.keys.single() == "s500-p0.log")
        now = 2_000L
        log.clearViewer()
        assertTrue(log.readForViewer().isEmpty())
        assertTrue("restored" in log.readForExport())
    }

    @Test
    fun earlierSessionsArePrunedToTheRetentionKeepingTheNewestWhole() = runLogTest { scope ->
        val large = "x".repeat(1_500 * 1024) + "\n"
        val files = MemoryFiles(mapOf("s1-p0.log" to large, "s2-p0.log" to large, "s3-p0.log" to "1 3 I/A: small\n"))

        RotatingDiagnosticLog(files, scope, sessionStartMillis = 4L, nowMillis = { 5L }, formatTime = { "t$it" }, sessionHeader = "start")
        testScheduler.advanceUntilIdle()

        assertEquals(setOf("s2-p0.log", "s3-p0.log", "s4-p0.log"), files.contents.keys)
    }

    @Test
    fun aFullPartRotatesAndOnlyTheNewestPartsStay() = runLogTest { scope ->
        val files = MemoryFiles()
        val log = RotatingDiagnosticLog(files, scope, sessionStartMillis = 9L, nowMillis = { 10L }, formatTime = { "t$it" }, sessionHeader = "start")
        val chunk = "y".repeat(60 * 1024)

        repeat(60) {
            log.append(DiagnosticLevel.DEBUG, "Bulk", chunk)
            testScheduler.advanceUntilIdle()
        }

        // 17 lines fit in a part, so 60 lines end in the fourth part and the first two are gone.
        assertEquals(setOf("s9-p2.log", "s9-p3.log"), files.contents.keys)
        assertTrue(files.contents.values.all { it.length <= RotatingDiagnosticLog.PART_BYTES })
    }
}

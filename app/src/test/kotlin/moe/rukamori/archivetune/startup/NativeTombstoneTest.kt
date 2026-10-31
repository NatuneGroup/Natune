package moe.rukamori.archivetune.startup

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTombstoneTest {
    @Test fun androidTombstoneProtobufBecomesReadableSignalAbortAndBacktraceFields() {
        val signal = number(1, 11) + string(2, "SIGSEGV") + number(3, 1) + string(4, "SEGV_MAPERR")
        val frame = number(1, 4096) + string(4, "fixture_process") + number(5, 4) +
            string(6, "/system/lib64/libfixture.so") + string(8, "fixture-build")
        val thread = number(1, 42) + string(2, "main") + blob(4, frame)
        val bytes = string(2, "vendor/build") + string(4, "fixture-time") + number(5, 40) + number(6, 42) +
            blob(10, signal) + string(14, "fixture abort") + blob(15, string(1, "fixture cause")) + entry(42, thread)

        val result = NativeTombstoneDecoder.decode(bytes)
        assertEquals("vendor/build", result.fingerprint)
        assertEquals("fixture-time", result.timestamp)
        assertEquals(40L, result.pid)
        assertEquals(42L, result.tid)
        assertEquals("SIGSEGV", result.signal?.name)
        assertEquals("SEGV_MAPERR", result.signal?.codeName)
        assertEquals("fixture abort", result.abortMessage)
        assertEquals(listOf("fixture cause"), result.causes)
        assertEquals("main", result.threads.single().name)
        assertEquals(NativeFrame(4096, "libfixture.so", "fixture_process", 4, "fixture-build"),
            result.threads.single().frames.single())
        assertFalse(result.incomplete)
    }

    @Test fun unknownOemFieldsAreSkippedWithoutDumpingMemoryOrLogs() {
        val bytes = number(5, 40) + number(1000, 8) + blob(17, "private memory".toByteArray()) +
            blob(18, "private log".toByteArray()) + blob(1001, byteArrayOf(1, 2, 3)) + string(14, "abort")
        val result = NativeTombstoneDecoder.decode(bytes)
        assertEquals(40L, result.pid)
        assertEquals("abort", result.abortMessage)
        assertTrue(result.threads.isEmpty())
        assertFalse(result.incomplete)
    }

    @Test fun crashingThreadIsKeptEvenIfOtherThreadsFillThePreviewBudgetFirst() {
        val bytes = number(6, 99) + (1L..8L).fold(ByteArray(0)) { body, id -> body + entry(id, string(2, "other")) } +
            entry(99, string(2, "crashing"))
        val result = NativeTombstoneDecoder.decode(bytes)
        assertEquals(5, result.threads.size)
        assertEquals(99L, result.threads.first().id)
        assertEquals("crashing", result.threads.first().name)
    }

    @Test fun malformedAndSizeCappedTracesKeepAvailableEvidenceInsteadOfThrowing() {
        assertTrue(NativeTombstoneDecoder.decode(ByteArray(11) { 0x80.toByte() }).incomplete)
        val truncated = number(5, 40) + blob(16, ByteArray(100)).copyOf(8)
        val result = NativeTombstoneDecoder.decode(truncated)
        assertEquals(40L, result.pid)
        assertTrue(result.incomplete)
    }

    @Test fun crashingTidCanFollowTheThreadMap() {
        val bytes = (1L..8L).fold(ByteArray(0)) { body, id -> body + entry(id, string(2, "other")) } +
            entry(99, string(2, "crashing")) + number(6, 99)
        val result = NativeTombstoneDecoder.decode(bytes)
        assertEquals(5, result.threads.size)
        assertEquals(99L, result.threads.first().id)
        assertEquals("crashing", result.threads.first().name)
    }

    @Test fun malformedNestedMessagesAreMarkedIncomplete() {
        val result = NativeTombstoneDecoder.decode(number(5, 40) + blob(10, byteArrayOf(0x80.toByte())))
        assertEquals(40L, result.pid)
        assertTrue(result.incomplete)
    }

    @Test fun backtracePreviewIsBounded() {
        val frames = (0..100).fold(ByteArray(0)) { bytes, index -> bytes + blob(4, number(1, index.toLong())) }
        val result = NativeTombstoneDecoder.decode(number(6, 99) + entry(99, frames))
        assertEquals(64, result.threads.single().frames.size)
    }

    private fun entry(id: Long, thread: ByteArray): ByteArray = blob(16, number(1, id) + blob(2, thread))
    private fun string(field: Int, value: String): ByteArray = blob(field, value.toByteArray())
    private fun blob(field: Int, bytes: ByteArray): ByteArray = varint((field.toLong() shl 3) or 2) + varint(bytes.size.toLong()) + bytes
    private fun number(field: Int, value: Long): ByteArray = varint(field.toLong() shl 3) + varint(value)
    private fun varint(value: Long): ByteArray {
        val output = ByteArrayOutputStream()
        var remaining = value
        while (remaining and -128L != 0L) {
            output.write(((remaining and 127) or 128).toInt())
            remaining = remaining ushr 7
        }
        output.write(remaining.toInt())
        return output.toByteArray()
    }
}

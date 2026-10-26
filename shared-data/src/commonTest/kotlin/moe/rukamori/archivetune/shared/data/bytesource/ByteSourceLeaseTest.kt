package moe.rukamori.archivetune.shared.data.bytesource

import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ByteSourceLeaseTest {
    private val origin = StreamOrigin("https", "example.com", 443)

    private fun lease(
        length: Long? = null,
        primary: StreamOrigin = origin,
        permitted: Set<StreamOrigin> = setOf(origin),
    ): ByteSourceLease =
        ByteSourceLease(
            account = ByteSourceAccount("tidal", "acct-a"),
            primaryOrigin = primary,
            permittedOrigins = permitted,
            cacheKey = "track",
            expiresAtEpochMs = null,
            lengthBytes = length,
        )

    @Test
    fun patternSourceServesOffsetsAboveFourGiB() = runTest {
        val total = 5L * 1024 * 1024 * 1024
        val source = PatternByteSource(lease(total), total)
        val buffer = ByteArray(16)

        val high = 4_294_967_296L + 3
        assertEquals(16, source.readAt(high, buffer, 0, 16))
        for (i in 0 until 16) assertEquals(patternFormula(high + i), buffer[i])

        val near = 5_368_709_000L
        val tail = ByteArray(32)
        assertEquals(32, source.readAt(near, tail, 0, 32))
        for (i in 0 until 32) assertEquals(patternFormula(near + i), tail[i])

        assertEquals(RandomAccessByteSource.END_OF_STREAM, source.readAt(total, buffer, 0, 16))
    }

    @Test
    fun guardedReadFailsAfterCancelAllWithCancellationException() = runTest {
        val cancellation = LeaseCancellation()
        val inner = ByteArrayByteSource(lease(64), ByteArray(64) { it.toByte() })
        val guarded = GuardedByteSource(inner, cancellation.issueTicket())
        val buffer = ByteArray(8)
        assertEquals(8, guarded.readAt(0, buffer, 0, 8))
        assertContentEquals(ByteArray(8) { it.toByte() }, buffer)

        cancellation.cancelAll()
        val error = assertFailsWith<ByteSourceCancelledException> { guarded.readAt(0, buffer, 0, 8) }
        assertIs<CancellationException>(error)
    }

    @Test
    fun inFlightReadCancelledBeforeReturnIsDiscarded() = runTest {
        val cancellation = LeaseCancellation()
        val inFlight = CancellingSource(lease(), cancellation)
        val guarded = GuardedByteSource(inFlight, cancellation.issueTicket())
        val buffer = ByteArray(16)
        assertFailsWith<ByteSourceCancelledException> { guarded.readAt(0, buffer, 0, 16) }
        assertContentEquals(ByteArray(16), buffer)
    }

    @Test
    fun leaseRejectsOriginOutsidePermittedSetAndNegativeLength() {
        val other = StreamOrigin("https", "other.example", 443)
        assertFailsWith<IllegalArgumentException> { lease(primary = origin, permitted = setOf(other)) }
        assertFailsWith<IllegalArgumentException> { lease(length = -1) }
    }

    @Test
    fun streamOriginParsing() {
        assertEquals(StreamOrigin("https", "example.com", 443), StreamOrigin.parse("HTTPS://Example.COM/a"))
        assertEquals(StreamOrigin("http", "example.com", 80), StreamOrigin.parse("http://example.com"))
        assertEquals(StreamOrigin("https", "example.com", 8443), StreamOrigin.parse("https://example.com:8443/x?y=1"))
        assertEquals(null, StreamOrigin.parse("ftp://x"))
        assertEquals(null, StreamOrigin.parse("https://user@example.com/"))
        assertEquals(null, StreamOrigin.parse("https://example.com:notaport/"))
    }

    private class CancellingSource(
        override val lease: ByteSourceLease,
        private val cancellation: LeaseCancellation,
    ) : RandomAccessByteSource {
        override suspend fun readAt(
            position: Long,
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            cancellation.cancelAll()
            for (i in 0 until 10) buffer[offset + i] = 7
            return 10
        }
    }

    private fun patternFormula(p: Long): Byte = ((p * 31 + 7) xor (p ushr 13)).toByte()
}

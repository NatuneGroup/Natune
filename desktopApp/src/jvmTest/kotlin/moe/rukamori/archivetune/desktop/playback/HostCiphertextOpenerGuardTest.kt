package moe.rukamori.archivetune.desktop.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceCancelledException
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin

class HostCiphertextOpenerGuardTest {
    @Test
    fun aReadAfterAnInterruptStillReachesTheSource() = runTest {
        val opener = opener()
        val guarded = opener.guard(source())
        assertEquals(4, guarded.readAt(0L, ByteArray(4), 0, 4))
        // The engine's interrupt is a transient wake-up: the seek interrupts, then the producer reads the new position.
        opener.interrupt()
        assertEquals(4, guarded.readAt(4L, ByteArray(4), 0, 4))
        opener.close()
    }

    @Test
    fun aReadInFlightDuringAnInterruptIsDiscarded() = runTest {
        val opener = opener()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val guarded = opener.guard(
            source(
                beforeRead = {
                    entered.complete(Unit)
                    release.await()
                },
            ),
        )
        val read = async { guarded.readAt(0L, ByteArray(4), 0, 4) }
        entered.await()
        opener.interrupt()
        release.complete(Unit)
        assertFailsWith<ByteSourceCancelledException> { read.await() }
        opener.close()
    }

    @Test
    fun aClosedOpenerRefusesALaterRead() = runTest {
        val opener = opener()
        val guarded = opener.guard(source())
        opener.close()
        // A closed opener must not let its source reconnect, even though the guard ticket is taken per read.
        assertFailsWith<ByteSourceCancelledException> { guarded.readAt(0L, ByteArray(4), 0, 4) }
    }

    @Test
    fun aRestartableLifetimeArmsAFreshTokenAfterAnInterrupt() {
        val lifetimes = ReadLifetimes()
        val inFlight = lifetimes.start()
        lifetimes.interrupt()
        assertTrue(inFlight.isCancelled)
        // The next read must run, or a lease that was interrupted once could never play again.
        assertFalse(lifetimes.start().isCancelled)
        lifetimes.close()
        assertTrue(lifetimes.start().isCancelled)
    }

    private fun opener() = HostCiphertextOpener(
        boundScope = SCOPE,
        account = ByteSourceAccount(provider = "playback", accountId = null),
        cookies = null,
    )

    private fun source(beforeRead: suspend () -> Unit = {}): RandomAccessByteSource = object : RandomAccessByteSource {
        override val lease = ByteSourceLease(
            account = ByteSourceAccount(provider = "playback", accountId = null),
            primaryOrigin = ORIGIN,
            permittedOrigins = setOf(ORIGIN),
            cacheKey = "https://audio.invalid/song",
            expiresAtEpochMs = null,
            lengthBytes = 8L,
        )

        override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            beforeRead()
            val count = minOf(length.toLong(), 8L - position).toInt()
            if (count <= 0) return RandomAccessByteSource.END_OF_STREAM
            buffer.fill(THUMBNAIL_BYTE, offset, offset + count)
            return count
        }
    }

    private companion object {
        private const val SCOPE = "personal"
        private const val THUMBNAIL_BYTE: Byte = 0x07
        private val ORIGIN = StreamOrigin("https", "audio.invalid", 443)
    }
}

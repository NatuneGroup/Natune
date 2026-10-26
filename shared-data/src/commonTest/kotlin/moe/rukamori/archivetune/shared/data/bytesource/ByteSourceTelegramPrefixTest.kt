/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.bytesource

import kotlinx.coroutines.test.runTest
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ByteSourceTelegramPrefixTest {
    private val content = ByteArray(FILE_SIZE) { (it % 251).toByte() }
    private val origin = StreamOrigin("https", "telegram.invalid", 443)

    private fun lease(provider: String = "telegram"): ByteSourceLease =
        ByteSourceLease(
            account = ByteSourceAccount(provider, "chat-1"),
            primaryOrigin = origin,
            permittedOrigins = setOf(origin),
            cacheKey = "file-1",
            expiresAtEpochMs = null,
            lengthBytes = FILE_SIZE.toLong(),
        )

    private fun state(
        expected: Long? = FILE_SIZE.toLong(),
        offset: Long = 100,
        prefix: Long = 200,
        active: Boolean = true,
        completed: Boolean = false,
    ) = PartialFileState(expected, offset, prefix, active, completed)

    @Test
    fun readsInsideActiveRunAndDoesNotSeekOrCrossRunEnd() = runTest {
        val port = FakePort(content, state())
        val source = TelegramPartialFileByteSource(lease(), port)

        val inside = ByteArray(64)
        assertEquals(64, source.readAt(150, inside, 0, 64))
        assertContentEquals(content.copyOfRange(150, 214), inside)

        val clamped = ByteArray(64)
        assertEquals(10, source.readAt(290, clamped, 0, 64))
        assertContentEquals(content.copyOfRange(290, 300), clamped.copyOf(10))

        assertEquals(0, source.readAt(300, ByteArray(64), 0, 64))
        assertTrue(port.startCalls.isEmpty())
    }

    @Test
    fun positionBeforeRunStartsDownloadAndReturnsZero() = runTest {
        val port = FakePort(content, state())
        val source = TelegramPartialFileByteSource(lease(), port)
        assertEquals(0, source.readAt(50, ByteArray(16), 0, 16))
        assertEquals(listOf(50L), port.startCalls)
    }

    @Test
    fun inactiveDownloadOutsideRunRequestsSeek() = runTest {
        val port = FakePort(content, state(active = false))
        val source = TelegramPartialFileByteSource(lease(), port)
        assertEquals(0, source.readAt(350, ByteArray(16), 0, 16))
        assertEquals(listOf(350L), port.startCalls)
    }

    @Test
    fun completedDownloadIgnoresPrefixAndReportsEndOfStream() = runTest {
        val port = FakePort(content, state(prefix = 0, active = false, completed = true))
        val source = TelegramPartialFileByteSource(lease(), port)
        val buffer = ByteArray(64)
        assertEquals(10, source.readAt(9_990, buffer, 0, 64))
        assertContentEquals(content.copyOfRange(9_990, FILE_SIZE), buffer.copyOf(10))
        assertEquals(RandomAccessByteSource.END_OF_STREAM, source.readAt(FILE_SIZE.toLong(), buffer, 0, 64))
        assertTrue(port.startCalls.isEmpty())
    }

    @Test
    fun unknownSizeReadsRunButNeverReportsEndOfStream() = runTest {
        val port = FakePort(content, state(expected = null))
        val source = TelegramPartialFileByteSource(lease(), port)
        assertEquals(64, source.readAt(150, ByteArray(64), 0, 64))
        assertEquals(0, source.readAt(9_999, ByteArray(64), 0, 64))
        assertEquals(0, source.readAt(FILE_SIZE.toLong() + 5, ByteArray(8), 0, 8))
    }

    @Test
    fun constructionRequiresTelegramProvider() {
        assertFailsWith<ByteSourceException> {
            TelegramPartialFileByteSource(lease(provider = "tidal"), FakePort(content, state()))
        }
    }

    private class FakePort(
        private val content: ByteArray,
        private var current: PartialFileState,
    ) : TelegramPartialFilePort {
        val startCalls = mutableListOf<Long>()

        override suspend fun state(): PartialFileState = current

        override suspend fun startDownload(offset: Long) {
            startCalls += offset
        }

        override suspend fun readPart(
            offset: Long,
            count: Int,
        ): ByteArray {
            val end = min(content.size.toLong(), offset + count).toInt()
            return content.copyOfRange(offset.toInt(), end)
        }
    }

    private companion object {
        const val FILE_SIZE = 10_000
    }
}

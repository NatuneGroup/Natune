package moe.rukamori.archivetune.shared.data.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * The iOS raw-DEFLATE codec against zlib fixtures produced by the reference zlib, so a stream another host's
 * java.util.zip wrote inflates here byte for byte.
 */
class PortableDeflateIosTest {
    @Test
    fun deflateInflateRoundTrip() {
        val samples = listOf(
            "a".encodeToByteArray(),
            "the quick brown fox jumps over the lazy dog".encodeToByteArray(),
            ByteArray(512 * 1024) { (it * 97).toByte() },
        )
        samples.forEach { sample ->
            assertContentEquals(sample, portableInflate(portableDeflate(sample), sample.size))
        }
    }

    @Test
    fun inflatesReferenceZlibStreams() {
        // Raw-DEFLATE fixtures produced by zlib with wbits = -15, level 6, no wrapper: the stream form ZIP carries.
        assertContentEquals(
            byteArrayOf(),
            portableInflate("0300".hex(), 0),
        )
        assertContentEquals(
            "hello portable archive deflate".encodeToByteArray(),
            portableInflate(
                "cb48cdc9c95728c82f2a494cca4955482c4acec82c4b5548494dcb492c490500".hex(), 30,
            ),
        )
        val binary = ByteArray(1024) { (it % 256).toByte() }
        assertContentEquals(
            binary,
            portableInflate(
                ("6360646266616563e7e0e4e2e6e1e5e3171014121611151397909492969195935750545256515553d7d0d4d2d6d1d5d3" +
                    "3730343236313533b7b0b4b2b6b1b5b37770747276717573f7f0f4f2f6f1f5f30f080c0a0e090d0b8f888c8a8e898d8b4f" +
                    "484c4a4e494d4bcfc8cccacec9cdcb2f282c2a2e292d2bafa8acaaaea9adab6f686c6a6e696d6befe8eceaeee9edeb9f30" +
                    "71d2e42953a74d9f3173d6ec3973e7cd5fb070d1e2254b972d5fb172d5ea356bd7addfb071d3e62d5bb76ddfb173d7ee" +
                    "3d7bf7ed3f70f0d0e123478f1d3f71f2d4e93367cf9dbf70f1d2e52b57af5dbf71f3d6ed3b77efdd7ff0f0d1e3274f9f3" +
                    "d7ff1f2d5eb376fdfbdfff0f1d3e72f5fbf7dfff1f3d7ef3f7ffffd6718f5ffa8ff47b0ff01").hex(), 1024,
            ),
        )
    }

    @Test
    fun declaredSizeMismatchThrows() {
        val stream = portableDeflate("payload".encodeToByteArray())
        assertFailsWith<Throwable> { portableInflate(stream, 1) }
        assertFailsWith<Throwable> { portableInflate(stream, 1024) }
        assertFailsWith<Throwable> { portableInflate("00ff".hex(), 16) }
        // Trailing bytes after the stream end are rejected, matching the java.util.zip host's strict check.
        assertFailsWith<Throwable> { portableInflate(stream + byteArrayOf(0x00, 0x00), 7) }
    }

    private fun String.hex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

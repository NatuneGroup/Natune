package moe.rukamori.archivetune.shared.data.stream.deezer

import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.data.bytesource.ByteArrayByteSource
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.Md5
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.data.stream.blowfish.Blowfish
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamDecryptDeezerTest {
    @Test
    fun md5KnownAnswers() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Md5.hexDigest(ByteArray(0)))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.hexDigest("abc".encodeToByteArray()))
        assertEquals(
            "9e107d9d372bb6826bd81d3542a419d6",
            Md5.hexDigest("The quick brown fox jumps over the lazy dog".encodeToByteArray()),
        )
    }

    @Test
    fun deriveKeyMatchesIndependentComputation() {
        // Computed with hashlib: bytes(ord(h[i]) ^ ord(h[i+16]) ^ salt[i]) over the MD5 hex text.
        assertContentEquals(hex("6c6c666b39662c37652575603c643439"), DeezerCrypto.deriveKey("3135556"))
    }

    @Test
    fun encryptedChunkStride() {
        for (index in listOf(0L, 3L, 6L)) assertTrue(DeezerCrypto.isEncryptedChunk(index), "index=$index")
        for (index in listOf(1L, 2L, 4L)) assertFalse(DeezerCrypto.isEncryptedChunk(index), "index=$index")
    }

    @Test
    fun decryptChunkRestoresPlaintextAndHonoursPartialBlocks() {
        val key = DeezerCrypto.deriveKey("42")
        val plain = ByteArray(DeezerCrypto.CHUNK_SIZE) { (it * 13 + 5).toByte() }
        val cipher = encryptChunkForTest(key, plain)

        val restored = cipher.copyOf()
        DeezerCrypto.decryptChunk(restored, DeezerCrypto.CHUNK_SIZE, key)
        assertContentEquals(plain, restored)

        val partial = cipher.copyOf()
        DeezerCrypto.decryptChunk(partial, 13, key)
        assertContentEquals(plain.copyOf(8), partial.copyOf(8))
        assertContentEquals(cipher.copyOfRange(8, 13), partial.copyOfRange(8, 13))
    }

    @Test
    fun chunkSourceServesPlaintextAcrossEncryptedAndPlainChunks() = runTest {
        val key = DeezerCrypto.deriveKey("3135556")
        val total = 8192 + 100
        val plain = ByteArray(total) { (it * 17 + it / 256).toByte() }
        val cipher = plain.copyOf()
        // Chunks 0 and 3 are full and encrypted; chunk 1, 2 and the short trailing chunk 4 are plaintext.
        for (index in listOf(0, 3)) {
            val chunk = encryptChunkForTest(key, plain.copyOfRange(index * 2048, index * 2048 + 2048))
            chunk.copyInto(cipher, index * 2048)
        }
        val source = DeezerChunkByteSource(ByteArrayByteSource(lease(total.toLong()), cipher), key)

        for (position in listOf(0, 1, 2, 2047, 2048, 3000, 6143, 6144, 6200, 8191, 8192, 8250)) {
            for (length in listOf(1, 7, 300)) {
                val expected = plain.copyOfRange(position, minOf(total, position + length))
                assertContentEquals(expected, readFully(source, position.toLong(), length), "pos=$position len=$length")
            }
        }
        assertEquals(RandomAccessByteSource.END_OF_STREAM, source.readAt(total.toLong(), ByteArray(4), 0, 4))
    }

    @Test
    fun partialEncryptedChunkIsWithheldUntilWhole() = runTest {
        val key = DeezerCrypto.deriveKey("3135556")
        val plain = ByteArray(2048 * 2) { (it * 7 + 3).toByte() }
        val cipher = plain.copyOf()
        encryptChunkForTest(key, plain.copyOf(2048)).copyInto(cipher, 0)
        val backing = cipher.copyOf()

        // Chunk 0 arrives in short reads with a temporary stall: partial ciphertext is withheld (read 0),
        // and once the whole chunk arrived it is decrypted. The upstream array is never mutated.
        val source = DeezerChunkByteSource(TricklingSource(lease(4096), cipher, firstServe = 500), key)
        assertEquals(0, source.readAt(0, ByteArray(4), 0, 4))
        assertContentEquals(plain.copyOf(16), readFully(source, 0, 16))
        assertContentEquals(backing, cipher)
    }

    /** An upstream that first serves only [firstServe] bytes of chunk 0, then reports nothing available, then serves all. */
    private class TricklingSource(
        override val lease: ByteSourceLease,
        private val content: ByteArray,
        private val firstServe: Long,
    ) : RandomAccessByteSource {
        private var calls = 0

        override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            calls++
            val available = when (calls) {
                1 -> firstServe
                2 -> 0L
                else -> content.size.toLong()
            }
            if (position >= available) return if (position >= content.size) RandomAccessByteSource.END_OF_STREAM else 0
            val count = minOf(length.toLong(), available - position).toInt()
            content.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }
    }

    /** Blowfish-CBC encrypts a whole chunk with the Deezer IV, as the CDN does for encrypted chunks. */
    private fun encryptChunkForTest(
        key: ByteArray,
        plain: ByteArray,
    ): ByteArray {
        val cipher = Blowfish(key)
        val out = ByteArray(plain.size)
        var chain = ByteArray(8) { it.toByte() }
        val x = ByteArray(8)
        for (offset in plain.indices step 8) {
            for (i in 0 until 8) x[i] = (plain[offset + i].toInt() xor chain[i].toInt()).toByte()
            cipher.encryptBlock(x, 0, out, offset)
            chain = out.copyOfRange(offset, offset + 8)
        }
        return out
    }

    private suspend fun readFully(
        source: RandomAccessByteSource,
        position: Long,
        length: Int,
    ): ByteArray {
        val out = ByteArray(length)
        var got = 0
        while (got < length) {
            val read = source.readAt(position + got, out, got, length - got)
            if (read == RandomAccessByteSource.END_OF_STREAM || read == 0) break
            got += read
        }
        return out.copyOf(got)
    }

    private fun lease(length: Long): ByteSourceLease =
        ByteSourceLease(
            account = ByteSourceAccount("deezer", "acct"),
            primaryOrigin = StreamOrigin.parse("https://cdn.example/track")!!,
            permittedOrigins = setOf(StreamOrigin.parse("https://cdn.example/track")!!),
            cacheKey = "track",
            expiresAtEpochMs = null,
            lengthBytes = length,
        )

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

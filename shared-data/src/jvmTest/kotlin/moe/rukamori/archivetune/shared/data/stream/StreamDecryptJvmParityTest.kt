package moe.rukamori.archivetune.shared.data.stream

import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.data.bytesource.ByteArrayByteSource
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.Md5
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.data.stream.aes.Aes128
import moe.rukamori.archivetune.shared.data.stream.aes.AesCbc
import moe.rukamori.archivetune.shared.data.stream.blowfish.Blowfish
import moe.rukamori.archivetune.shared.data.stream.deezer.DeezerCrypto
import moe.rukamori.archivetune.shared.data.stream.hls.HlsAes128SegmentSource
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class StreamDecryptJvmParityTest {
    @Test
    fun aesCbcMatchesJceNoPadding() {
        val random = Random(1001)
        repeat(50) {
            val blocks = random.nextInt(1, 65)
            val length = blocks * 16
            val key = random.nextBytes(16)
            val iv = random.nextBytes(16)
            val input = random.nextBytes(length)
            val aes = Aes128(key)

            assertContentEquals(jce("AES/CBC/NoPadding", Cipher.DECRYPT_MODE, key, "AES", iv, input), AesCbc.decryptNoPadding(aes, iv, input, length))
            assertContentEquals(jce("AES/CBC/NoPadding", Cipher.ENCRYPT_MODE, key, "AES", iv, input), AesCbc.encryptNoPadding(aes, iv, input, length))
        }
    }

    @Test
    fun blowfishSingleBlockMatchesJceEcb() {
        val random = Random(2002)
        repeat(50) {
            val key = random.nextBytes(16)
            val block = random.nextBytes(8)
            val cipher = Cipher.getInstance("Blowfish/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "Blowfish"))
            val expected = cipher.doFinal(block)
            val actual = ByteArray(8)
            Blowfish(key).encryptBlock(block, 0, actual, 0)
            assertContentEquals(expected, actual)
        }
    }

    @Test
    fun deezerDecryptChunkMatchesJceBlowfishCbc() {
        val random = Random(3003)
        repeat(12) { round ->
            val key = random.nextBytes(16)
            val chunk = random.nextBytes(DeezerCrypto.CHUNK_SIZE)
            for (length in listOf(DeezerCrypto.CHUNK_SIZE, 2040, 13)) {
                val work = chunk.copyOf()
                DeezerCrypto.decryptChunk(work, length, key)
                val whole = length - length % 8
                val expected = jce("Blowfish/CBC/NoPadding", Cipher.DECRYPT_MODE, key, "Blowfish", IV_0_TO_7, chunk.copyOf(whole))
                assertContentEquals(expected, work.copyOf(whole), "round=$round length=$length")
                assertContentEquals(chunk.copyOfRange(whole, length), work.copyOfRange(whole, length))
            }
        }
    }

    @Test
    fun md5MatchesMessageDigest() {
        val random = Random(4004)
        repeat(50) {
            val input = random.nextBytes(random.nextInt(0, 301))
            val expected = MessageDigest.getInstance("MD5").digest(input)
            assertEquals(expected.joinToString("") { "%02x".format(it) }, Md5.hexDigest(input))
            assertContentEquals(expected, Md5.digest(input))
        }
    }

    @Test
    fun hlsSegmentSourceMatchesJcePkcs5Decryption() = runTest {
        val random = Random(5005)
        val key = random.nextBytes(16)
        val iv = random.nextBytes(16)
        val plain = random.nextBytes(3000)
        val cipherText = jce("AES/CBC/PKCS5Padding", Cipher.ENCRYPT_MODE, key, "AES", iv, plain)
        assertContentEquals(plain, jce("AES/CBC/PKCS5Padding", Cipher.DECRYPT_MODE, key, "AES", iv, cipherText))

        val origin = StreamOrigin("https", "cdn.example", 443)
        val lease =
            ByteSourceLease(
                account = ByteSourceAccount("hls", null),
                primaryOrigin = origin,
                permittedOrigins = setOf(origin),
                cacheKey = "seg",
                expiresAtEpochMs = null,
                lengthBytes = cipherText.size.toLong(),
            )
        val source = HlsAes128SegmentSource(ByteArrayByteSource(lease, cipherText), Aes128(key), iv)

        val whole = ByteArray(plain.size)
        assertEquals(plain.size, source.readAt(0, whole, 0, whole.size))
        assertContentEquals(plain, whole)

        repeat(40) {
            val position = random.nextInt(0, plain.size)
            val length = random.nextInt(1, 300)
            val expected = plain.copyOfRange(position, minOf(plain.size, position + length))
            val buffer = ByteArray(length)
            val count = source.readAt(position.toLong(), buffer, 0, length)
            assertContentEquals(expected, buffer.copyOf(count), "pos=$position len=$length")
        }
        assertEquals(RandomAccessByteSource.END_OF_STREAM, source.readAt(plain.size.toLong(), ByteArray(1), 0, 1))
    }

    private fun jce(
        transformation: String,
        mode: Int,
        key: ByteArray,
        algorithm: String,
        iv: ByteArray,
        input: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(transformation)
        val spec = SecretKeySpec(key, algorithm)
        if (transformation.startsWith("AES/CBC") || transformation.startsWith("Blowfish/CBC")) {
            cipher.init(mode, spec, IvParameterSpec(iv))
        } else {
            cipher.init(mode, spec)
        }
        return cipher.doFinal(input)
    }

    private companion object {
        val IV_0_TO_7 = ByteArray(8) { it.toByte() }
    }
}

package moe.rukamori.archivetune.shared.data.stream

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.data.stream.aes.Aes256Gcm

/** AES-256-GCM against `javax.crypto` as the oracle: round-trips, known blob shape and tamper rejection. */
class Aes256GcmJvmParityTest {

    private val random = SecureRandom()

    @Test
    fun `decrypts every size the feed can emit`() {
        for (size in listOf(0, 1, 15, 16, 17, 31, 32, 33, 255, 256, 1024)) {
            val plaintext = randomBytes(size)
            val (key, iv, blob) = seal(plaintext)
            assertContentEquals(plaintext, Aes256Gcm.decrypt(key, iv, blob) ?: error("GCM decrypt failed for size $size"))
        }
    }

    @Test
    fun `rejects a tampered ciphertext and tag`() {
        val (key, iv, blob) = seal("shared tidal account".encodeToByteArray())
        val tampered = blob.copyOf()
        tampered[3] = (tampered[3].toInt() xor 0x01).toByte()
        assertNull(Aes256Gcm.decrypt(key, iv, tampered))
        val badTag = blob.copyOf()
        badTag[badTag.size - 1] = (badTag[badTag.size - 1].toInt() xor 0x01).toByte()
        assertNull(Aes256Gcm.decrypt(key, iv, badTag))
    }

    @Test
    fun `rejects malformed inputs`() {
        val (key, iv, blob) = seal(randomBytes(8))
        assertNull(Aes256Gcm.decrypt(randomBytes(16), iv, blob))
        assertNull(Aes256Gcm.decrypt(key, randomBytes(16), blob))
        assertNull(Aes256Gcm.decrypt(key, iv, blob.copyOfRange(0, 10)))
    }

    @Test
    fun `decrypts the enc one blob shape the pool feed emits`() {
        val readKey = "feed-read-key"
        val derived = MessageDigest.getInstance("SHA-256").digest("archivepool-client:$readKey".toByteArray(Charsets.UTF_8))
        val (_, iv, blob) = seal("access-token".encodeToByteArray(), key = derived)
        val blobText = "enc:1:" + Base64.getMimeEncoder().encodeToString(iv) +
            ":" + Base64.getMimeEncoder().encodeToString(blob)
        val parts = blobText.removePrefix("enc:1:").split(":")
        val decoder = Base64.getMimeDecoder()
        assertEquals(
            "access-token",
            Aes256Gcm.decrypt(derived, decoder.decode(parts[0]), decoder.decode(parts[1]))?.decodeToString(),
        )
    }

    /** Seals [plaintext] under AES-256-GCM with a fresh 96-bit IV; returns (key, iv, ciphertext+tag). */
    private fun seal(plaintext: ByteArray, key: ByteArray = randomBytes(32)): Triple<ByteArray, ByteArray, ByteArray> {
        val iv = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Triple(key, iv, cipher.doFinal(plaintext))
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}

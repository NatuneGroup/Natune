package moe.rukamori.archivetune.shared.data.deviceconnect

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectPairing
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceFrameCodec
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceFrameHeader
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceMessageType

class DeviceConnectCryptoTest {
    @Test
    fun p256KeyAgreementAgrees() {
        val crypto = JvmDeviceCrypto()
        val first = crypto.generateKeyPair()
        val second = crypto.generateKeyPair()
        val firstSecret = crypto.sharedSecret(first, second.publicKey)
        val secondSecret = crypto.sharedSecret(second, first.publicKey)
        assertEquals(32, firstSecret.size)
        assertContentEquals(firstSecret, secondSecret)
        first.destroy()
        second.destroy()
    }

    @Test
    fun aesGcmMatchesJcaWithAad() {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val nonce = ByteArray(12) { (it * 5 + 1).toByte() }
        val aad = "natune-connect/1\u0000header".encodeToByteArray()
        val plaintext = "the queue is a secret only to the two paired devices".encodeToByteArray()
        val sealed = DeviceAesGcm.seal(key, nonce, aad, plaintext)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val expected = cipher.doFinal(plaintext)
        assertContentEquals(expected, sealed)
        assertContentEquals(plaintext, DeviceAesGcm.open(key, nonce, aad, sealed))
        sealed[0] = (sealed[0].toInt() xor 1).toByte()
        assertNull(DeviceAesGcm.open(key, nonce, aad, sealed))
    }

    @Test
    fun frameAadCoversTypeSequenceAndTranscript() {
        val transcript = ByteArray(32) { it.toByte() }
        val first = DeviceFrameHeader(1, DeviceMessageType.PLAYBACK, DeviceFrameCodec.FLAG_ENCRYPTED, 7).aad(transcript)
        val second = DeviceFrameHeader(1, DeviceMessageType.PLAYBACK, DeviceFrameCodec.FLAG_ENCRYPTED, 8).aad(transcript)
        val third = DeviceFrameHeader(1, DeviceMessageType.PLAYBACK, DeviceFrameCodec.FLAG_ENCRYPTED, 7).aad(transcript.copyOf().also { it[0] = 9 })
        assertEquals(39, first.size)
        assert(!first.contentEquals(second))
        assert(!first.contentEquals(third))
    }

    @Test
    fun hkdfAndSasAreStable() {
        val keys = DeviceConnectPairing.deriveKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        assertNotNull(keys)
        assertEquals(32, keys.pairSecret.size)
        assertEquals(DeviceConnectPairing.sas(keys.pairSecret), DeviceConnectPairing.sas(keys.pairSecret.copyOf()))
    }
}

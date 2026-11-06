package moe.rukamori.archivetune.shared.data.deviceconnect

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceCrypto
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceKeyPair

/** The Android host shares the JVM's JCA primitives; Android's provider supplies the P-256 implementation. */
class AndroidDeviceCrypto : DeviceCrypto {
    private val random = SecureRandom()

    override fun randomBytes(size: Int): ByteArray {
        require(size > 0)
        return ByteArray(size).also(random::nextBytes)
    }

    override fun generateKeyPair(): DeviceKeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        val pair = generator.generateKeyPair()
        return AndroidKeyPair(pair, rawPublicKey(pair))
    }

    override fun sharedSecret(keyPair: DeviceKeyPair, peerPublicKey: ByteArray): ByteArray {
        require(keyPair is AndroidKeyPair) { "key pair belongs to another crypto host" }
        require(peerPublicKey.size == 65 && peerPublicKey[0] == 4.toByte()) { "peer key is not an X9.62 P-256 point" }
        val privateKey = keyPair.pair.private as ECPrivateKey
        val x = BigInteger(1, peerPublicKey.copyOfRange(1, 33))
        val y = BigInteger(1, peerPublicKey.copyOfRange(33, 65))
        val publicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), privateKey.params))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return normalize(agreement.generateSecret(), 32)
    }

    private fun rawPublicKey(pair: KeyPair): ByteArray {
        val point = (pair.public as ECPublicKey).w
        val out = ByteArray(65)
        out[0] = 4
        writeFixed(point.affineX, out, 1)
        writeFixed(point.affineY, out, 33)
        return out
    }

    private fun writeFixed(value: BigInteger, out: ByteArray, offset: Int) {
        val bytes = value.toByteArray()
        val source = if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size) else bytes
        source.copyInto(out, offset + 32 - source.size)
    }

    private fun normalize(value: ByteArray, size: Int): ByteArray {
        if (value.size == size) return value
        val out = ByteArray(size)
        if (value.size > size) value.copyOfRange(value.size - size, value.size).copyInto(out)
        else value.copyInto(out, size - value.size)
        return out
    }

    private class AndroidKeyPair(val pair: KeyPair, override val publicKey: ByteArray) : DeviceKeyPair {
        override fun destroy() = Unit
    }
}

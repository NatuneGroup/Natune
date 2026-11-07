package moe.rukamori.archivetune.shared.domain.deviceconnect

import moe.rukamori.archivetune.shared.domain.crypto.HmacSha256
import moe.rukamori.archivetune.shared.domain.lyrics.ai.Sha256

/**
 * The pure pairing policy: commitments, the transcript hash, HKDF, the six-digit SAS and the HMAC proofs.
 * No platform crypto and no I/O live here, so the same rules run on every host and can be tested directly.
 */
object DeviceConnectPairing {
    private const val HKDF_HASH_BYTES = 32

    fun commitment(
        role: DeviceConnectRole,
        deviceId: String,
        nonce: ByteArray,
        publicKey: ByteArray,
    ): ByteArray = Sha256.digest(
        lengthPrefixed(
            DeviceConnectLimits.PROTOCOL_LABEL,
            "commit",
            role.wire,
            deviceId,
            nonce.toHex(),
            publicKey.toHex(),
        ),
    )

    /**
     * Binds both identities, both ephemeral keys and both commitments in one order. The initiator is
     * always first, so each side derives exactly the same bytes.
     */
    fun transcript(
        initiatorCommitment: ByteArray,
        responderCommitment: ByteArray,
        initiatorId: String,
        responderId: String,
        initiatorNonce: ByteArray,
        responderNonce: ByteArray,
        initiatorPublicKey: ByteArray,
        responderPublicKey: ByteArray,
    ): ByteArray = Sha256.digest(
        lengthPrefixed(
            DeviceConnectLimits.PROTOCOL_LABEL,
            "transcript",
            DeviceConnectLimits.PROTOCOL_VERSION.toString(),
            initiatorCommitment.toHex(),
            responderCommitment.toHex(),
            initiatorId,
            responderId,
            initiatorNonce.toHex(),
            responderNonce.toHex(),
            initiatorPublicKey.toHex(),
            responderPublicKey.toHex(),
        ),
    )

    data class DerivedKeys(
        val initiatorToResponder: ByteArray,
        val responderToInitiator: ByteArray,
        val pairSecret: ByteArray,
    ) {
        override fun toString(): String = "DerivedKeys(REDACTED)"
    }

    fun deriveKeys(handshakeSecret: ByteArray, transcript: ByteArray): DerivedKeys {
        require(transcript.size == 32) { "transcript must be a SHA-256 digest" }
        return DerivedKeys(
            initiatorToResponder = HkdfSha256.derive(
                ikm = handshakeSecret,
                salt = transcript,
                info = "${DeviceConnectLimits.PROTOCOL_LABEL}/initiator".encodeToByteArray(),
                length = DeviceConnectLimits.TRAFFIC_KEY_BYTES,
            ),
            responderToInitiator = HkdfSha256.derive(
                ikm = handshakeSecret,
                salt = transcript,
                info = "${DeviceConnectLimits.PROTOCOL_LABEL}/responder".encodeToByteArray(),
                length = DeviceConnectLimits.TRAFFIC_KEY_BYTES,
            ),
            pairSecret = HkdfSha256.derive(
                ikm = handshakeSecret,
                salt = transcript,
                info = "${DeviceConnectLimits.PROTOCOL_LABEL}/pair".encodeToByteArray(),
                length = DeviceConnectLimits.PAIR_SECRET_BYTES,
            ),
        )
    }

    /** The SAS both screens show: HMAC(pairSecret, label/sas), first four bytes big-endian mod 10^6. */
    fun sas(pairSecret: ByteArray): String {
        val digest = HmacSha256.sign(pairSecret, "${DeviceConnectLimits.PROTOCOL_LABEL}/sas".encodeToByteArray())
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (digest[i].toLong() and 0xff)
        return (value % DeviceConnectLimits.SAS_MODULUS).toString().padStart(DeviceConnectLimits.SAS_DIGITS, '0')
    }

    fun trafficKey(keys: DerivedKeys, senderRole: DeviceConnectRole): ByteArray =
        if (senderRole == DeviceConnectRole.INITIATOR) keys.initiatorToResponder else keys.responderToInitiator

    fun proof(
        key: ByteArray,
        phase: String,
        senderRole: DeviceConnectRole,
        senderId: String,
        receiverId: String,
        transcript: ByteArray,
    ): ByteArray = DeviceConnectProofs.proof(key, phase, senderRole, senderId, receiverId, transcript)

    fun verify(expected: ByteArray, actual: ByteArray): Boolean = DeviceConnectProofs.matches(expected, actual)

    fun verifyCommitment(expected: ByteArray, actual: ByteArray): Boolean = DeviceConnectProofs.matches(expected, actual)

    fun matchesSas(first: String, second: String): Boolean {
        if (first.length != second.length) return false
        var difference = 0
        for (i in first.indices) difference = difference or (first[i].code xor second[i].code)
        return difference == 0
    }

    /**
     * A hello is accepted only with the exact nonce/public-key sizes and a device id, so a malformed
     * key cannot reach the key agreement.
     */
    fun validHello(nonceHex: String, publicKeyHex: String, deviceId: String): Boolean {
        val nonce = nonceHex.hexToBytesOrNull() ?: return false
        val publicKey = publicKeyHex.hexToBytesOrNull() ?: return false
        return nonce.size == DeviceConnectLimits.NONCE_BYTES &&
            publicKey.size == DeviceConnectLimits.PUBLIC_KEY_BYTES &&
            DeviceConnectHosts.isValidDeviceId(deviceId)
    }

    fun randomDeviceId(crypto: DeviceCrypto): String = crypto.randomBytes(16).toHex()

    /** HKDF-SHA256 (RFC 5869); shared because the JVM, Android and iOS key agreement all need the same output. */
    object HkdfSha256 {
        fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            require(length in 1..255 * HKDF_HASH_BYTES) { "HKDF length out of range" }
            val pseudorandomKey = HmacSha256.sign(if (salt.isEmpty()) ByteArray(HKDF_HASH_BYTES) else salt, ikm)
            val out = ByteArray(length)
            var previous = ByteArray(0)
            var offset = 0
            var counter = 1
            while (offset < length) {
                val input = ByteArray(previous.size + info.size + 1)
                previous.copyInto(input)
                info.copyInto(input, previous.size)
                input[input.lastIndex] = counter.toByte()
                previous = HmacSha256.sign(pseudorandomKey, input)
                val take = minOf(previous.size, length - offset)
                previous.copyInto(out, offset, 0, take)
                offset += take
                counter++
            }
            return out
        }
    }
}

/**
 * The crypto boundary each host implements. The key pair never leaves the host; only the raw uncompressed
 * P-256 public point (65 bytes) crosses the wire.
 */
interface DeviceCrypto {
    fun randomBytes(size: Int): ByteArray

    fun generateKeyPair(): DeviceKeyPair

    fun sharedSecret(keyPair: DeviceKeyPair, peerPublicKey: ByteArray): ByteArray
}

interface DeviceKeyPair {
    val publicKey: ByteArray

    fun destroy()
}

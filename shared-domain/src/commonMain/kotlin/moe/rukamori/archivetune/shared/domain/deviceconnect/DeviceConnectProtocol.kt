package moe.rukamori.archivetune.shared.domain.deviceconnect

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The handshake and session message kinds. The numeric code is part of the v1 wire format. */
enum class DeviceMessageType(val code: Int) {
    HELLO_COMMIT(1),
    HELLO(2),
    AUTH_PROOF(3),
    PAIR_REQUEST(4),
    PAIR_APPROVE(5),
    PAIR_COMMIT(6),
    AUTH_RESULT(7),
    DEVICE_INFO(8),
    PLAYBACK(9),
    PING(10),
    PONG(11),
    BYE(12),
    ERROR(13),
    PLAYBACK_CHUNK(14),
    PAIR_REJECT(15),
    ;

    companion object {
        fun fromCode(code: Int): DeviceMessageType? = entries.firstOrNull { it.code == code }
    }
}

data class DeviceFrameHeader(
    val version: Int,
    val type: DeviceMessageType,
    val flags: Int,
    val seq: Long,
) {
    val encrypted: Boolean get() = flags and DeviceFrameCodec.FLAG_ENCRYPTED != 0

    /** The unencrypted part of a frame is authenticated together with the handshake transcript. */
    fun aad(transcript: ByteArray): ByteArray {
        val out = ByteArray(7 + transcript.size)
        out[0] = version.toByte()
        out[1] = type.code.toByte()
        out[2] = flags.toByte()
        out[3] = (seq ushr 24).toByte()
        out[4] = (seq ushr 16).toByte()
        out[5] = (seq ushr 8).toByte()
        out[6] = seq.toByte()
        transcript.copyInto(out, 7)
        return out
    }
}

data class DeviceFrame(
    val header: DeviceFrameHeader,
    /** 12 bytes on an encrypted frame, null otherwise. */
    val nonce: ByteArray?,
    /** JSON bytes on a plain handshake frame, ciphertext plus tag on an encrypted frame. */
    val payload: ByteArray,
)

private fun writeUInt32(out: ByteArray, offset: Int, value: Long) {
    out[offset] = (value ushr 24).toByte()
    out[offset + 1] = (value ushr 16).toByte()
    out[offset + 2] = (value ushr 8).toByte()
    out[offset + 3] = value.toByte()
}

object DeviceFrameCodec {
    const val FLAG_ENCRYPTED = 1
    private const val HEADER = 7
    private const val NONCE = DeviceConnectLimits.FRAME_NONCE_BYTES

    fun encode(
        type: DeviceMessageType,
        seq: Long,
        payload: ByteArray,
        nonce: ByteArray? = null,
    ): ByteArray {
        require(seq in 1..0xffff_ffffL) { "seq must be a positive 32-bit value" }
        val encrypted = nonce != null
        if (encrypted) require(nonce!!.size == NONCE) { "nonce must be 12 bytes" }
        val size = HEADER + (if (encrypted) NONCE else 0) + payload.size
        require(size <= DeviceConnectLimits.MAX_FRAME_BYTES) { "frame exceeds the 64 KiB limit" }
        val out = ByteArray(size)
        out[0] = DeviceConnectLimits.PROTOCOL_VERSION.toByte()
        out[1] = type.code.toByte()
        out[2] = (if (encrypted) FLAG_ENCRYPTED else 0).toByte()
        writeUInt32(out, 3, seq)
        var offset = HEADER
        if (nonce != null) {
            nonce.copyInto(out, offset)
            offset += NONCE
        }
        payload.copyInto(out, offset)
        return out
    }

    fun decode(bytes: ByteArray): DeviceFrame? {
        if (bytes.size < HEADER || bytes.size > DeviceConnectLimits.MAX_FRAME_BYTES) return null
        val version = bytes[0].toInt() and 0xff
        if (version != DeviceConnectLimits.PROTOCOL_VERSION) return null
        val type = DeviceMessageType.fromCode(bytes[1].toInt() and 0xff) ?: return null
        val flags = bytes[2].toInt() and 0xff
        val seq = readUInt32(bytes, 3)
        if (seq == 0L) return null
        val encrypted = flags and FLAG_ENCRYPTED != 0
        if (flags and FLAG_ENCRYPTED.inv() != 0) return null
        if (encrypted && bytes.size < HEADER + NONCE + DeviceConnectLimits.GCM_TAG_BYTES) return null
        val header = DeviceFrameHeader(version, type, flags, seq)
        if (!encrypted) return DeviceFrame(header, null, bytes.copyOfRange(HEADER, bytes.size))
        val nonce = bytes.copyOfRange(HEADER, HEADER + NONCE)
        return DeviceFrame(header, nonce, bytes.copyOfRange(HEADER + NONCE, bytes.size))
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (bytes[offset + i].toLong() and 0xff)
        return value
    }
}

object DeviceWireJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
        explicitNulls = false
    }
}

@Serializable
data class HelloCommitPayload(
    val role: String,
    val deviceId: String,
    val nonce: String,
    val publicKey: String,
    val commitment: String,
    val version: Int = DeviceConnectLimits.PROTOCOL_VERSION,
)

@Serializable
data class HelloPayload(
    val role: String,
    val deviceId: String,
    val nonce: String,
    val publicKey: String,
    val version: Int = DeviceConnectLimits.PROTOCOL_VERSION,
)

@Serializable
data class AuthProofPayload(
    val role: String,
    val senderId: String,
    val receiverId: String,
    val proof: String,
)

@Serializable
data class PairRequestPayload(
    val sas: String,
    val requiresApproval: Boolean = true,
    val senderId: String,
)

@Serializable
data class PairApprovePayload(val proof: String)

@Serializable
data class PairCommitPayload(val proof: String)

@Serializable
data class AuthResultPayload(
    val ok: Boolean,
    val error: String? = null,
)

@Serializable
data class DeviceInfoPayload(
    val deviceId: String,
    val name: String,
    val platform: String,
    val model: String = "",
    val appVersion: String = "",
    val accountScopeId: String? = null,
)

@Serializable
data class ErrorPayload(
    val code: String,
    val message: String = "",
) {
    fun toError(): DeviceConnectError =
        DeviceConnectError(
            DeviceConnectErrorCode.entries.firstOrNull { it.name == code } ?: DeviceConnectErrorCode.INVALID_MESSAGE,
            message,
        )
}

@Serializable
data class ByePayload(val reason: String = "")

@Serializable
data class PairRejectPayload(val reason: String = "")

@Serializable
data class PlaybackChunkPayload(
    val messageId: String,
    val index: Int,
    val count: Int,
    val totalBytes: Int,
    /** Hex rather than base64 so the common code needs no experimental encoding API. */
    val data: String,
)

@Serializable
data class PingPayload(val sentAtEpochMs: Long, val rttSampleMs: Long? = null)

sealed interface DeviceHandshakeMessage {
    data class HelloCommit(val value: HelloCommitPayload) : DeviceHandshakeMessage
    data class Hello(val value: HelloPayload) : DeviceHandshakeMessage
    data class AuthProof(val value: AuthProofPayload) : DeviceHandshakeMessage
    data class PairRequest(val value: PairRequestPayload) : DeviceHandshakeMessage
    data class PairApprove(val value: PairApprovePayload) : DeviceHandshakeMessage
    data class PairCommit(val value: PairCommitPayload) : DeviceHandshakeMessage
    data class AuthResult(val value: AuthResultPayload) : DeviceHandshakeMessage
    data class DeviceInfo(val value: DeviceInfoPayload) : DeviceHandshakeMessage
    data class Ping(val value: PingPayload) : DeviceHandshakeMessage
    data class Pong(val value: PingPayload) : DeviceHandshakeMessage
    data class Bye(val value: ByePayload) : DeviceHandshakeMessage
    data class Failure(val value: ErrorPayload) : DeviceHandshakeMessage
}

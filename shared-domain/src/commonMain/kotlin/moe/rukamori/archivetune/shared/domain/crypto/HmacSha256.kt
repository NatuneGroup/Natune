package moe.rukamori.archivetune.shared.domain.crypto

import moe.rukamori.archivetune.shared.domain.lyrics.ai.Sha256

/** HMAC-SHA256 (RFC 2104) over the common-code SHA-256, for request signing where `javax.crypto` is unavailable. */
object HmacSha256 {
    private const val BLOCK_SIZE = 64

    fun sign(key: ByteArray, message: ByteArray): ByteArray {
        val blockKey = (if (key.size > BLOCK_SIZE) Sha256.digest(key) else key).copyOf(BLOCK_SIZE)
        val inner = ByteArray(BLOCK_SIZE + message.size)
        val outer = ByteArray(BLOCK_SIZE + 32)
        for (i in 0 until BLOCK_SIZE) {
            inner[i] = (blockKey[i].toInt() xor 0x36).toByte()
            outer[i] = (blockKey[i].toInt() xor 0x5c).toByte()
        }
        message.copyInto(inner, BLOCK_SIZE)
        Sha256.digest(inner).copyInto(outer, BLOCK_SIZE)
        return Sha256.digest(outer)
    }

    fun signHex(key: ByteArray, message: String): String =
        sign(key, message.encodeToByteArray()).joinToString(separator = "") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}

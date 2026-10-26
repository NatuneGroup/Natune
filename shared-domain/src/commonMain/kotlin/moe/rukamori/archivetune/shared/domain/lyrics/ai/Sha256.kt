package moe.rukamori.archivetune.shared.domain.lyrics.ai

/**
 * Pure-Kotlin SHA-256 (FIPS 180-4). It replaces `java.security.MessageDigest`, which commonMain cannot use.
 * The digest is only a process-local cache identity, so it needs to be collision-resistant, not secret.
 */
internal object Sha256 {
    private val ROUND_CONSTANTS: IntArray =
        longArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
        ).map { it.toInt() }.toIntArray()

    private val INITIAL_STATE: IntArray =
        longArrayOf(
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
        ).map { it.toInt() }.toIntArray()

    /**
     * Digest of the concatenation of [parts], each preceded by its 4-byte big-endian length. The prefix
     * stops ("ab", "c") and ("a", "bc") from colliding, which the Android identity also guarded against.
     */
    fun hexDigestOfParts(parts: List<String>): String {
        val encoded = parts.map { it.encodeToByteArray() }
        val message = ByteArray(encoded.sumOf { 4 + it.size })
        var position = 0
        for (bytes in encoded) {
            val length = bytes.size
            message[position] = (length ushr 24).toByte()
            message[position + 1] = (length ushr 16).toByte()
            message[position + 2] = (length ushr 8).toByte()
            message[position + 3] = length.toByte()
            position += 4
            bytes.copyInto(message, position)
            position += bytes.size
        }
        return hexDigest(message)
    }

    fun hexDigest(message: ByteArray): String =
        state(message).joinToString(separator = "") { (it.toLong() and 0xffffffffL).toString(16).padStart(8, '0') }

    /** The 32-byte digest, big-endian. */
    fun digest(message: ByteArray): ByteArray {
        val state = state(message)
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[4 * i] = (state[i] ushr 24).toByte()
            out[4 * i + 1] = (state[i] ushr 16).toByte()
            out[4 * i + 2] = (state[i] ushr 8).toByte()
            out[4 * i + 3] = state[i].toByte()
        }
        return out
    }

    private fun state(message: ByteArray): IntArray {
        val padded = pad(message)
        val state = INITIAL_STATE.copyOf()
        var offset = 0
        while (offset < padded.size) {
            compress(state, padded, offset)
            offset += 64
        }
        return state
    }

    private fun pad(message: ByteArray): ByteArray {
        val bitLength = message.size.toLong() * 8
        val paddedSize = ((message.size + 9 + 63) / 64) * 64
        val padded = ByteArray(paddedSize)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[paddedSize - 1 - i] = (bitLength ushr (8 * i)).toByte()
        }
        return padded
    }

    private fun compress(
        state: IntArray,
        block: ByteArray,
        offset: Int,
    ) {
        val w = IntArray(64)
        for (t in 0 until 16) {
            val o = offset + 4 * t
            w[t] =
                ((block[o].toInt() and 0xff) shl 24) or
                    ((block[o + 1].toInt() and 0xff) shl 16) or
                    ((block[o + 2].toInt() and 0xff) shl 8) or
                    (block[o + 3].toInt() and 0xff)
        }
        for (t in 16 until 64) {
            val s0 = rotateRight(w[t - 15], 7) xor rotateRight(w[t - 15], 18) xor (w[t - 15] ushr 3)
            val s1 = rotateRight(w[t - 2], 17) xor rotateRight(w[t - 2], 19) xor (w[t - 2] ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (t in 0 until 64) {
            val sigma1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
            val choose = (e and f) xor (e.inv() and g)
            val temp1 = h + sigma1 + choose + ROUND_CONSTANTS[t] + w[t]
            val sigma0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temp2 = sigma0 + majority
            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }
        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }

    private fun rotateRight(
        value: Int,
        distance: Int,
    ): Int = (value ushr distance) or (value shl (32 - distance))
}

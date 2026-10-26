package moe.rukamori.archivetune.shared.data.stream.blowfish

/**
 * Blowfish block cipher (Schneier, 1993): 64-bit blocks, 4..56 byte keys, big-endian words.
 * The P-array and S-boxes are the pi-derived constants, then keyed by repeated self-encryption.
 */
class Blowfish(key: ByteArray) {
    private val p: IntArray = BLOWFISH_P_INIT.copyOf()

    /** Four 256-entry S-boxes stored flat: box b, entry e is at index b * 256 + e. */
    private val s: IntArray = BLOWFISH_S_INIT.copyOf()

    init {
        require(key.size in MIN_KEY_BYTES..MAX_KEY_BYTES) { "Blowfish key must be 4..56 bytes" }
        keySchedule(key)
    }

    /** Encrypts the 8-byte block at [inputOffset] of [input] into [output] at [outputOffset]. */
    fun encryptBlock(
        input: ByteArray,
        inputOffset: Int,
        output: ByteArray,
        outputOffset: Int,
    ) {
        var left = readWord(input, inputOffset)
        var right = readWord(input, inputOffset + 4)
        for (i in 0 until ROUNDS) {
            left = left xor p[i]
            right = right xor feistel(left)
            val swap = left
            left = right
            right = swap
        }
        val swap = left
        left = right
        right = swap
        right = right xor p[ROUNDS]
        left = left xor p[ROUNDS + 1]
        writeWord(output, outputOffset, left)
        writeWord(output, outputOffset + 4, right)
    }

    /** Decrypts the 8-byte block at [inputOffset] of [input] into [output] at [outputOffset]. */
    fun decryptBlock(
        input: ByteArray,
        inputOffset: Int,
        output: ByteArray,
        outputOffset: Int,
    ) {
        var left = readWord(input, inputOffset)
        var right = readWord(input, inputOffset + 4)
        for (i in ROUNDS + 1 downTo 2) {
            left = left xor p[i]
            right = right xor feistel(left)
            val swap = left
            left = right
            right = swap
        }
        val swap = left
        left = right
        right = swap
        right = right xor p[1]
        left = left xor p[0]
        writeWord(output, outputOffset, left)
        writeWord(output, outputOffset + 4, right)
    }

    private fun feistel(x: Int): Int {
        val a = (x ushr 24) and 0xff
        val b = (x ushr 16) and 0xff
        val c = (x ushr 8) and 0xff
        val d = x and 0xff
        return ((s[a] + s[SBOX_SIZE + b]) xor s[2 * SBOX_SIZE + c]) + s[3 * SBOX_SIZE + d]
    }

    /** Mixes the key into P, then refills P and the S-boxes by encrypting a running zero block. */
    private fun keySchedule(key: ByteArray) {
        var keyIndex = 0
        for (i in 0 until P_SIZE) {
            var word = 0
            repeat(4) {
                word = (word shl 8) or (key[keyIndex].toInt() and 0xff)
                keyIndex = (keyIndex + 1) % key.size
            }
            p[i] = p[i] xor word
        }
        var left = 0
        var right = 0
        for (i in 0 until P_SIZE step 2) {
            val pair = encryptWords(left, right)
            left = pair[0]
            right = pair[1]
            p[i] = left
            p[i + 1] = right
        }
        for (i in 0 until 4 * SBOX_SIZE step 2) {
            val pair = encryptWords(left, right)
            left = pair[0]
            right = pair[1]
            s[i] = left
            s[i + 1] = right
        }
    }

    private fun encryptWords(
        leftIn: Int,
        rightIn: Int,
    ): IntArray {
        var left = leftIn
        var right = rightIn
        for (i in 0 until ROUNDS) {
            left = left xor p[i]
            right = right xor feistel(left)
            val swap = left
            left = right
            right = swap
        }
        val swap = left
        left = right
        right = swap
        right = right xor p[ROUNDS]
        left = left xor p[ROUNDS + 1]
        return intArrayOf(left, right)
    }

    private companion object {
        const val MIN_KEY_BYTES = 4
        const val MAX_KEY_BYTES = 56
        const val ROUNDS = 16
        const val P_SIZE = 18
        const val SBOX_SIZE = 256

        fun readWord(
            bytes: ByteArray,
            offset: Int,
        ): Int =
            ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)

        fun writeWord(
            bytes: ByteArray,
            offset: Int,
            value: Int,
        ) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
    }
}

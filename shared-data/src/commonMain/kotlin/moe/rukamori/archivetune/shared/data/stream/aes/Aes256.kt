package moe.rukamori.archivetune.shared.data.stream.aes

/**
 * FIPS-197 AES-256 block cipher. Mirrors [Aes128]: the S-box is derived at init and the key schedule
 * follows the Nk=8 expansion of FIPS-197 section 5.2, including the extra SubWord on every fourth
 * word of a key-words group.
 */
class Aes256(keyBytes: ByteArray) {
    private val roundKeys: IntArray

    init {
        require(keyBytes.size == KEY_SIZE) { "AES-256 requires a 32-byte key" }
        roundKeys = expandKey(keyBytes)
    }

    /** Encrypts one 16-byte block read from [input] at [inputOffset] into [output] at [outputOffset]. */
    fun encryptBlock(
        input: ByteArray,
        inputOffset: Int,
        output: ByteArray,
        outputOffset: Int,
    ) {
        val state = IntArray(BLOCK_SIZE) { input[inputOffset + it].toInt() and 0xff }
        addRoundKey(state, 0)
        for (round in 1 until ROUNDS) {
            subBytes(state, SBOX)
            shiftRows(state)
            mixColumns(state)
            addRoundKey(state, round)
        }
        subBytes(state, SBOX)
        shiftRows(state)
        addRoundKey(state, ROUNDS)
        for (i in 0 until BLOCK_SIZE) output[outputOffset + i] = state[i].toByte()
    }

    private fun addRoundKey(
        state: IntArray,
        round: Int,
    ) {
        for (i in 0 until BLOCK_SIZE) {
            state[i] = state[i] xor roundKeys[round * BLOCK_SIZE + i]
        }
    }

    internal companion object {
        const val KEY_SIZE = 32
        const val BLOCK_SIZE = 16
        const val ROUNDS = 14
        private const val KEY_WORDS = 8
        private const val TOTAL_WORDS = 4 * (ROUNDS + 1)

        fun gfInverse(value: Int): Int {
            if (value == 0) return 0
            var result = 1
            var base = value
            var exponent = 254
            while (exponent > 0) {
                if (exponent and 1 != 0) result = gfMultiply(result, base)
                base = gfMultiply(base, base)
                exponent = exponent ushr 1
            }
            return result
        }

        fun gfMultiply(a: Int, b: Int): Int {
            var product = 0
            var x = a
            var y = b
            while (y != 0) {
                if (y and 1 != 0) product = product xor x
                x = xtime(x)
                y = y ushr 1
            }
            return product
        }

        fun xtime(value: Int): Int {
            val shifted = (value shl 1) and 0xff
            return if (value and 0x80 != 0) shifted xor 0x1b else shifted
        }

        fun rotl8(value: Int, shift: Int): Int = ((value shl shift) or (value ushr (8 - shift))) and 0xff

        val SBOX: IntArray = IntArray(256) { value ->
            val inv = gfInverse(value)
            inv xor rotl8(inv, 1) xor rotl8(inv, 2) xor rotl8(inv, 3) xor rotl8(inv, 4) xor 0x63
        }

        /** Round constants rcon[1..7], the only indices AES-256 key expansion can reach. */
        val RCON: IntArray = IntArray(KEY_WORDS).also { rcon ->
            rcon[1] = 1
            for (i in 2 until KEY_WORDS) rcon[i] = xtime(rcon[i - 1])
        }

        fun expandKey(key: ByteArray): IntArray {
            val bytes = IntArray(TOTAL_WORDS * 4)
            for (i in 0 until KEY_SIZE) bytes[i] = key[i].toInt() and 0xff
            var w = KEY_WORDS
            while (w < TOTAL_WORDS) {
                var t0 = bytes[4 * (w - 1)]
                var t1 = bytes[4 * (w - 1) + 1]
                var t2 = bytes[4 * (w - 1) + 2]
                var t3 = bytes[4 * (w - 1) + 3]
                when (w % KEY_WORDS) {
                    0 -> {
                        // RotWord, SubWord, then Rcon on the first byte.
                        val r0 = SBOX[t1] xor RCON[w / KEY_WORDS]
                        val r1 = SBOX[t2]
                        val r2 = SBOX[t3]
                        val r3 = SBOX[t0]
                        t0 = r0; t1 = r1; t2 = r2; t3 = r3
                    }
                    4 -> {
                        t0 = SBOX[t0]; t1 = SBOX[t1]; t2 = SBOX[t2]; t3 = SBOX[t3]
                    }
                }
                bytes[4 * w] = bytes[4 * (w - KEY_WORDS)] xor t0
                bytes[4 * w + 1] = bytes[4 * (w - KEY_WORDS) + 1] xor t1
                bytes[4 * w + 2] = bytes[4 * (w - KEY_WORDS) + 2] xor t2
                bytes[4 * w + 3] = bytes[4 * (w - KEY_WORDS) + 3] xor t3
                w++
            }
            return bytes
        }

        fun subBytes(state: IntArray, table: IntArray) {
            for (i in 0 until BLOCK_SIZE) state[i] = table[state[i]]
        }

        fun shiftRows(state: IntArray) {
            val copy = state.copyOf()
            for (row in 1 until 4) for (col in 0 until 4) {
                state[row + 4 * col] = copy[row + 4 * ((col + row) % 4)]
            }
        }

        fun mixColumns(state: IntArray) {
            for (col in 0 until 4) {
                val a0 = state[4 * col]
                val a1 = state[4 * col + 1]
                val a2 = state[4 * col + 2]
                val a3 = state[4 * col + 3]
                state[4 * col] = gfMultiply(a0, 2) xor gfMultiply(a1, 3) xor a2 xor a3
                state[4 * col + 1] = a0 xor gfMultiply(a1, 2) xor gfMultiply(a2, 3) xor a3
                state[4 * col + 2] = a0 xor a1 xor gfMultiply(a2, 2) xor gfMultiply(a3, 3)
                state[4 * col + 3] = gfMultiply(a0, 3) xor a1 xor a2 xor gfMultiply(a3, 2)
            }
        }
    }
}

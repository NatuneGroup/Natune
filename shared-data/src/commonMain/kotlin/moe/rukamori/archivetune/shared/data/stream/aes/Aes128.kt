package moe.rukamori.archivetune.shared.data.stream.aes

import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException

/**
 * FIPS-197 AES-128 block cipher. The S-box and its inverse are derived at init from the
 * multiplicative inverse in GF(2^8) followed by the affine map, so no 256-entry tables are typed.
 */
class Aes128(keyBytes: ByteArray) {
    private val roundKeys: IntArray

    init {
        require(keyBytes.size == KEY_SIZE) { "AES-128 requires a 16-byte key" }
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
        writeBlock(state, output, outputOffset)
    }

    /** Decrypts one 16-byte block read from [input] at [inputOffset] into [output] at [outputOffset]. */
    fun decryptBlock(
        input: ByteArray,
        inputOffset: Int,
        output: ByteArray,
        outputOffset: Int,
    ) {
        val state = IntArray(BLOCK_SIZE) { input[inputOffset + it].toInt() and 0xff }
        addRoundKey(state, ROUNDS)
        for (round in ROUNDS - 1 downTo 1) {
            invShiftRows(state)
            subBytes(state, INV_SBOX)
            addRoundKey(state, round)
            invMixColumns(state)
        }
        invShiftRows(state)
        subBytes(state, INV_SBOX)
        addRoundKey(state, 0)
        writeBlock(state, output, outputOffset)
    }

    private fun addRoundKey(
        state: IntArray,
        round: Int,
    ) {
        for (i in 0 until BLOCK_SIZE) {
            state[i] = state[i] xor roundKeys[round * BLOCK_SIZE + i]
        }
    }

    private fun writeBlock(
        state: IntArray,
        output: ByteArray,
        outputOffset: Int,
    ) {
        for (i in 0 until BLOCK_SIZE) output[outputOffset + i] = state[i].toByte()
    }

    private companion object {
        const val KEY_SIZE = 16
        const val BLOCK_SIZE = 16
        const val ROUNDS = 10

        /** Multiplicative inverse in GF(2^8) mod x^8+x^4+x^3+x+1, with 0 mapped to 0. */
        fun gfInverse(value: Int): Int {
            if (value == 0) return 0
            // a^254 == a^-1 because the multiplicative group has order 255.
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

        val INV_SBOX: IntArray = IntArray(256).also { table ->
            for (i in 0 until 256) table[SBOX[i]] = i
        }

        /** Round constants rcon[1..10] derived by repeated xtime, as in FIPS-197 section 5.2. */
        val RCON: IntArray = IntArray(ROUNDS + 1).also { rcon ->
            rcon[1] = 1
            for (i in 2..ROUNDS) rcon[i] = xtime(rcon[i - 1])
        }

        fun expandKey(key: ByteArray): IntArray {
            val words = IntArray(44 * 4)
            for (i in 0 until 16) words[i] = key[i].toInt() and 0xff
            var generated = 16
            var rconIndex = 1
            while (generated < words.size) {
                val t0 = words[generated - 4]
                val t1 = words[generated - 3]
                val t2 = words[generated - 2]
                val t3 = words[generated - 1]
                if (generated % 16 == 0) {
                    // RotWord, SubWord, then Rcon on the first byte of the word.
                    words[generated] = words[generated - 16] xor SBOX[t1] xor RCON[rconIndex]
                    words[generated + 1] = words[generated - 15] xor SBOX[t2]
                    words[generated + 2] = words[generated - 14] xor SBOX[t3]
                    words[generated + 3] = words[generated - 13] xor SBOX[t0]
                    rconIndex++
                } else {
                    for (k in 0 until 4) words[generated + k] = words[generated - 16 + k] xor words[generated - 4 + k]
                }
                generated += 4
            }
            return words
        }

        fun subBytes(state: IntArray, table: IntArray) {
            for (i in 0 until BLOCK_SIZE) state[i] = table[state[i]]
        }

        // Column-major layout: state[row + 4 * column].
        fun shiftRows(state: IntArray) {
            val copy = state.copyOf()
            for (row in 1 until 4) for (col in 0 until 4) {
                state[row + 4 * col] = copy[row + 4 * ((col + row) % 4)]
            }
        }

        fun invShiftRows(state: IntArray) {
            val copy = state.copyOf()
            for (row in 1 until 4) for (col in 0 until 4) {
                state[row + 4 * col] = copy[row + 4 * ((col - row + 4) % 4)]
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

        fun invMixColumns(state: IntArray) {
            for (col in 0 until 4) {
                val a0 = state[4 * col]
                val a1 = state[4 * col + 1]
                val a2 = state[4 * col + 2]
                val a3 = state[4 * col + 3]
                state[4 * col] = gfMultiply(a0, 14) xor gfMultiply(a1, 11) xor gfMultiply(a2, 13) xor gfMultiply(a3, 9)
                state[4 * col + 1] = gfMultiply(a0, 9) xor gfMultiply(a1, 14) xor gfMultiply(a2, 11) xor gfMultiply(a3, 13)
                state[4 * col + 2] = gfMultiply(a0, 13) xor gfMultiply(a1, 9) xor gfMultiply(a2, 14) xor gfMultiply(a3, 11)
                state[4 * col + 3] = gfMultiply(a0, 11) xor gfMultiply(a1, 13) xor gfMultiply(a2, 9) xor gfMultiply(a3, 14)
            }
        }
    }
}

/** CBC mode over [Aes128] without padding; callers own the padding policy. */
object AesCbc {
    private const val BLOCK_SIZE = 16

    fun decryptNoPadding(
        aes: Aes128,
        iv: ByteArray,
        input: ByteArray,
        length: Int,
    ): ByteArray {
        requireBlockAligned(iv, length)
        val output = ByteArray(length)
        val plainBlock = ByteArray(BLOCK_SIZE)
        var chain = iv.copyOf()
        for (offset in 0 until length step BLOCK_SIZE) {
            val cipherBlock = input.copyOfRange(offset, offset + BLOCK_SIZE)
            aes.decryptBlock(cipherBlock, 0, plainBlock, 0)
            for (i in 0 until BLOCK_SIZE) {
                output[offset + i] = (plainBlock[i].toInt() xor chain[i].toInt()).toByte()
            }
            chain = cipherBlock
        }
        return output
    }

    fun encryptNoPadding(
        aes: Aes128,
        iv: ByteArray,
        input: ByteArray,
        length: Int,
    ): ByteArray {
        requireBlockAligned(iv, length)
        val output = ByteArray(length)
        var chain = iv.copyOf()
        val xored = ByteArray(BLOCK_SIZE)
        for (offset in 0 until length step BLOCK_SIZE) {
            for (i in 0 until BLOCK_SIZE) {
                xored[i] = (input[offset + i].toInt() xor chain[i].toInt()).toByte()
            }
            aes.encryptBlock(xored, 0, output, offset)
            chain = output.copyOfRange(offset, offset + BLOCK_SIZE)
        }
        return output
    }

    /**
     * Validates PKCS#7 padding on the final decrypted [lastBlock] (16 bytes) and returns the pad length
     * (1..16). Every pad byte must equal the pad length; anything else is rejected.
     */
    fun pkcs7Unpad(lastBlock: ByteArray): Int {
        if (lastBlock.size != BLOCK_SIZE) throw ByteSourceException("PKCS#7 tail must be one 16-byte block")
        val padLength = lastBlock[BLOCK_SIZE - 1].toInt() and 0xff
        if (padLength !in 1..BLOCK_SIZE) throw ByteSourceException("Invalid PKCS#7 pad length")
        for (i in BLOCK_SIZE - padLength until BLOCK_SIZE) {
            if ((lastBlock[i].toInt() and 0xff) != padLength) throw ByteSourceException("Inconsistent PKCS#7 padding")
        }
        return padLength
    }

    private fun requireBlockAligned(iv: ByteArray, length: Int) {
        require(iv.size == BLOCK_SIZE) { "CBC IV must be 16 bytes" }
        require(length >= 0 && length % BLOCK_SIZE == 0) { "CBC length must be a multiple of 16" }
    }
}

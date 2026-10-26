package moe.rukamori.archivetune.shared.data.stream.aes

/**
 * FIPS-197 AES-256 block cipher. The S-box is derived at init from the multiplicative inverse in GF(2^8)
 * followed by the affine map, the same construction [Aes128] uses; only the key schedule differs (Nk = 8, Nr = 14).
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
            subBytes(state)
            shiftRows(state)
            mixColumns(state)
            addRoundKey(state, round)
        }
        subBytes(state)
        shiftRows(state)
        addRoundKey(state, ROUNDS)
        writeBlock(state, output, outputOffset)
    }

    private fun addRoundKey(state: IntArray, round: Int) {
        for (i in 0 until BLOCK_SIZE) {
            state[i] = state[i] xor roundKeys[round * BLOCK_SIZE + i]
        }
    }

    private fun writeBlock(state: IntArray, output: ByteArray, outputOffset: Int) {
        for (i in 0 until BLOCK_SIZE) output[outputOffset + i] = state[i].toByte()
    }

    private companion object {
        const val KEY_SIZE = 32
        const val BLOCK_SIZE = 16
        const val ROUNDS = 14

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

        fun rotl8(value: Int, shift: Int): Int = ((value shl shift) or (value ushr (8 - shift))) and 0xff

        val SBOX: IntArray = IntArray(256) { value ->
            val inv = gfInverse(value)
            inv xor rotl8(inv, 1) xor rotl8(inv, 2) xor rotl8(inv, 3) xor rotl8(inv, 4) xor 0x63
        }

        /** Round constants rcon[1..7] for the 14 rounds of AES-256. */
        val RCON: IntArray = IntArray(8).also { rcon ->
            rcon[1] = 1
            for (i in 2..7) rcon[i] = xtime(rcon[i - 1])
        }

        /** FIPS-197 key expansion with Nk = 8: 60 words of 4 bytes produce the 15 round keys. */
        fun expandKey(key: ByteArray): IntArray {
            val words = IntArray(240)
            for (i in 0 until KEY_SIZE) words[i] = key[i].toInt() and 0xff
            var generated = KEY_SIZE
            var rconIndex = 1
            while (generated < words.size) {
                val t0 = words[generated - 4]
                val t1 = words[generated - 3]
                val t2 = words[generated - 2]
                val t3 = words[generated - 1]
                if (generated % 32 == 0) {
                    words[generated] = words[generated - 32] xor SBOX[t1] xor RCON[rconIndex]
                    words[generated + 1] = words[generated - 31] xor SBOX[t2]
                    words[generated + 2] = words[generated - 30] xor SBOX[t3]
                    words[generated + 3] = words[generated - 29] xor SBOX[t0]
                    rconIndex++
                } else if (generated % 32 == 16) {
                    // AES-256 applies SubWord at the middle of each key-word group.
                    words[generated] = words[generated - 32] xor SBOX[t0]
                    words[generated + 1] = words[generated - 31] xor SBOX[t1]
                    words[generated + 2] = words[generated - 30] xor SBOX[t2]
                    words[generated + 3] = words[generated - 29] xor SBOX[t3]
                } else {
                    for (k in 0 until 4) words[generated + k] = words[generated - 32 + k] xor words[generated - 4 + k]
                }
                generated += 4
            }
            return words
        }

        fun subBytes(state: IntArray) {
            for (i in 0 until BLOCK_SIZE) state[i] = SBOX[state[i]]
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

/**
 * AES-GCM over a block cipher, decrypt only: CTR-mode plaintext recovery and the GHASH tag check. Used for the
 * `enc:1:<iv>:<ciphertext+tag>` blobs of the Playback Pool feed, where the IV is the standard 96 bits and there is
 * no additional data. A tag mismatch answers null, like the JVM `GCMParameterSpec` failure the caller replaces it
 * with on platforms that already carry a GCM cipher.
 */
object Aes256Gcm {
    private const val BLOCK_SIZE = 16
    private const val TAG_SIZE = 16
    private const val IV_SIZE = 12

    /**
     * Decrypts [ciphertextAndTag] (ciphertext followed by the 16-byte tag) under [key] with the 96-bit [iv].
     * Returns null when the IV is not 12 bytes, the blob is shorter than a tag or the tag does not verify.
     */
    fun decrypt(key: ByteArray, iv: ByteArray, ciphertextAndTag: ByteArray): ByteArray? {
        if (key.size != 32 || iv.size != IV_SIZE || ciphertextAndTag.size < TAG_SIZE) return null
        val aes = Aes256(key)
        val j0 = ByteArray(BLOCK_SIZE)
        iv.copyInto(j0)
        j0[BLOCK_SIZE - 1] = 1

        val ciphertextLength = ciphertextAndTag.size - TAG_SIZE
        val plaintext = ByteArray(ciphertextLength)
        val counter = j0.copyOf()
        val keystream = ByteArray(BLOCK_SIZE)
        for (offset in 0 until ciphertextLength step BLOCK_SIZE) {
            incrementCounter(counter)
            aes.encryptBlock(counter, 0, keystream, 0)
            val take = minOf(BLOCK_SIZE, ciphertextLength - offset)
            for (i in 0 until take) plaintext[offset + i] = (ciphertextAndTag[offset + i].toInt() xor keystream[i].toInt()).toByte()
        }

        val expected = ByteArray(BLOCK_SIZE)
        aes.encryptBlock(j0, 0, expected, 0)
        val tag = ghash(aes, ciphertextAndTag, ciphertextLength)
        for (i in 0 until BLOCK_SIZE) expected[i] = (expected[i].toInt() xor tag[i].toInt()).toByte()

        var difference = 0
        for (i in 0 until TAG_SIZE) {
            difference = difference or ((expected[i].toInt() xor ciphertextAndTag[ciphertextLength + i].toInt()) and 0xff)
        }
        return if (difference == 0) plaintext else null
    }

    /** GHASH over the ciphertext padded to whole blocks, then the (AAD=0, ciphertext) length block in bits. */
    private fun ghash(aes: Aes256, ciphertext: ByteArray, ciphertextLength: Int): ByteArray {
        val h = ByteArray(BLOCK_SIZE)
        aes.encryptBlock(ByteArray(BLOCK_SIZE), 0, h, 0)
        val y = ByteArray(BLOCK_SIZE)
        val block = ByteArray(BLOCK_SIZE)
        var offset = 0
        while (offset < ciphertextLength) {
            val take = minOf(BLOCK_SIZE, ciphertextLength - offset)
            block.fill(0)
            ciphertext.copyInto(block, 0, offset, offset + take)
            xorBlock(y, block)
            gcmMultiply(y, h)
            offset += BLOCK_SIZE
        }
        block.fill(0)
        writeLengthBlock(block, 0L, ciphertextLength.toLong() * 8L)
        xorBlock(y, block)
        gcmMultiply(y, h)
        return y
    }

    /** The GHASH length block: aad length then ciphertext length, each a 64-bit big-endian bit count. */
    private fun writeLengthBlock(block: ByteArray, aadBits: Long, ciphertextBits: Long) {
        for (i in 0 until 8) {
            block[i] = (aadBits ushr (56 - 8 * i)).toByte()
            block[8 + i] = (ciphertextBits ushr (56 - 8 * i)).toByte()
        }
    }

    private fun xorBlock(a: ByteArray, b: ByteArray) {
        for (i in 0 until BLOCK_SIZE) a[i] = (a[i].toInt() xor b[i].toInt()).toByte()
    }

    /**
     * GF(2^128) multiplication per NIST SP 800-38D: y = y * h, with the reduction polynomial
     * x^128 + x^7 + x^2 + x + 1 represented by the 0xe1 constant.
     */
    private fun gcmMultiply(y: ByteArray, h: ByteArray) {
        val z = ByteArray(BLOCK_SIZE)
        val v = h.copyOf()
        for (i in 0 until BLOCK_SIZE) {
            val yByte = y[i].toInt()
            for (bit in 0 until 8) {
                if (yByte and (0x80 ushr bit) != 0) xorBlock(z, v)
                val lsb = v[BLOCK_SIZE - 1].toInt() and 1
                for (j in BLOCK_SIZE - 1 downTo 1) {
                    v[j] = (((v[j].toInt() and 0xff) ushr 1) or ((v[j - 1].toInt() and 1) shl 7)).toByte()
                }
                v[0] = ((v[0].toInt() and 0xff) ushr 1).toByte()
                if (lsb != 0) v[0] = (v[0].toInt() xor 0xe1).toByte()
            }
        }
        z.copyInto(y)
    }

    /** The last four bytes of the counter, incremented big-endian, per SP 800-38D section 6.2. */
    private fun incrementCounter(counter: ByteArray) {
        for (i in BLOCK_SIZE - 1 downTo BLOCK_SIZE - 4) {
            val next = (counter[i].toInt() and 0xff) + 1
            counter[i] = next.toByte()
            if (next <= 0xff) return
        }
    }
}

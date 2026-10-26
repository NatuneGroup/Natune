package moe.rukamori.archivetune.shared.data.stream.aes


/** AES-GCM (CTR + GHASH) decrypt helper over [Aes256]; the block cipher lives in Aes256.kt. */
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

package moe.rukamori.archivetune.shared.data.stream.aes

/**
 * AES-256-GCM decryption, NIST SP 800-38D: GHASH over the ciphertext and the tag verified before any
 * plaintext leaves. Only the forward cipher is needed because GCTR decrypts by encrypting counters.
 * Used for the Playback Pool's `enc:1:` field encryption; the pool never encrypts, so no encrypt
 * path exists here.
 */
object AesGcm {
    private const val BLOCK = 16
    private const val TAG_BYTES = 16

    /**
     * Decrypts [ciphertextAndTag] (ciphertext followed by the 16-byte tag) under [keyBytes] and
     * [nonce]. AAD is not used by the pool protocol. Returns null when the sizes are impossible or
     * the tag does not verify.
     */
    fun decrypt(keyBytes: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray? {
        if (nonce.isEmpty() || ciphertextAndTag.size < TAG_BYTES) return null
        val aes = Aes256(keyBytes)
        val h = ByteArray(BLOCK).also { aes.encryptBlock(ByteArray(BLOCK), 0, it, 0) }
        val j0 = counter0(aes, h, nonce)
        val cipherLength = ciphertextAndTag.size - TAG_BYTES
        val expectedTag = ByteArray(BLOCK).also { aes.encryptBlock(j0, 0, it, 0) }
        val s = ghash(h, ciphertextAndTag, cipherLength)
        for (i in 0 until BLOCK) expectedTag[i] = (expectedTag[i].toInt() xor s[i].toInt()).toByte()
        var diff = 0
        for (i in 0 until TAG_BYTES) {
            diff = diff or (expectedTag[i].toInt() xor ciphertextAndTag[cipherLength + i].toInt())
        }
        if (diff != 0) return null
        val plain = ByteArray(cipherLength)
        val counter = j0.copyOf()
        val keystream = ByteArray(BLOCK)
        var offset = 0
        while (offset < cipherLength) {
            increment(counter)
            aes.encryptBlock(counter, 0, keystream, 0)
            val run = minOf(BLOCK, cipherLength - offset)
            for (i in 0 until run) {
                plain[offset + i] = (ciphertextAndTag[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            offset += run
        }
        return plain
    }

    /** J0 for a 12-byte nonce is nonce‖0x00000001; any other length is GHASHed per SP 800-38D 7.2. */
    private fun counter0(aes: Aes256, h: ByteArray, nonce: ByteArray): ByteArray {
        if (nonce.size == 12) {
            val j0 = ByteArray(BLOCK)
            nonce.copyInto(j0)
            j0[BLOCK - 1] = 1
            return j0
        }
        // GHASH over the nonce padded to blocks, then its bit length.
        var y = ByteArray(BLOCK)
        var offset = 0
        while (offset + BLOCK <= nonce.size) {
            y = ghashBlock(h, y, nonce, offset, BLOCK)
            offset += BLOCK
        }
        if (offset < nonce.size) y = ghashBlock(h, y, nonce, offset, nonce.size - offset)
        val lens = ByteArray(BLOCK)
        writeBigEndian(lens, 8, nonce.size.toLong() * 8L)
        return ghashBlock(h, y, lens, 0, BLOCK)
    }

    /** GHASH over ciphertext blocks then the lengths block (aad length is zero). */
    private fun ghash(h: ByteArray, ciphertext: ByteArray, cipherLength: Int): ByteArray {
        var y = ByteArray(BLOCK)
        var offset = 0
        while (offset + BLOCK <= cipherLength) {
            y = ghashBlock(h, y, ciphertext, offset, BLOCK)
            offset += BLOCK
        }
        if (offset < cipherLength) y = ghashBlock(h, y, ciphertext, offset, cipherLength - offset)
        val lens = ByteArray(BLOCK)
        writeBigEndian(lens, 8, cipherLength.toLong() * 8L)
        return ghashBlock(h, y, lens, 0, BLOCK)
    }

    private fun ghashBlock(h: ByteArray, y: ByteArray, data: ByteArray, offset: Int, length: Int): ByteArray {
        val block = ByteArray(BLOCK)
        for (i in 0 until length) block[i] = data[offset + i]
        for (i in 0 until BLOCK) block[i] = (block[i].toInt() xor y[i].toInt()).toByte()
        return multiply(h, block)
    }

    /** GF(2^128) multiplication with the polynomial x^128+x^7+x^2+x+1, R = 0xe1 << 120. */
    private fun multiply(h: ByteArray, x: ByteArray): ByteArray {
        val z = ByteArray(BLOCK)
        val v = x.copyOf()
        for (bit in 0 until 128) {
            val set = (h[bit / 8].toInt() ushr (7 - bit % 8)) and 1
            if (set == 1) for (i in 0 until BLOCK) z[i] = (z[i].toInt() xor v[i].toInt()).toByte()
            val carry = v[BLOCK - 1].toInt() and 1
            for (i in BLOCK - 1 downTo 1) {
                v[i] = ((v[i].toInt() ushr 1) or ((v[i - 1].toInt() and 1) shl 7)).toByte()
            }
            v[0] = (v[0].toInt() ushr 1).toByte()
            if (carry == 1) v[0] = (v[0].toInt() xor 0xe1).toByte()
        }
        return z
    }

    private fun increment(counter: ByteArray) {
        var i = BLOCK - 1
        while (i >= BLOCK - 4) {
            val next = (counter[i].toInt() + 1) and 0xff
            counter[i] = next.toByte()
            if (next != 0) return
            i--
        }
    }

    private fun writeBigEndian(out: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) out[offset + i] = (value ushr (56 - i * 8)).toByte()
    }
}

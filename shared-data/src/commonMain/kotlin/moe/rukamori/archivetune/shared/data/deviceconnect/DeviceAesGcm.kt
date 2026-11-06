package moe.rukamori.archivetune.shared.data.deviceconnect

import moe.rukamori.archivetune.shared.data.stream.aes.Aes256

/**
 * AES-256-GCM for Device Connect frames: 96-bit nonce, 128-bit tag and additional authenticated data.
 * The block cipher is the repository's existing AES-256 implementation; GCM support for AAD is kept here
 * because the Playback Pool only needs its own decrypt-only no-AAD shape.
 */
object DeviceAesGcm {
    private const val BLOCK = 16
    private const val TAG = 16

    /** Returns ciphertext followed by the 16-byte tag. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 needs a 32-byte key" }
        require(nonce.size == 12) { "GCM needs a 12-byte nonce" }
        val aes = Aes256(key)
        val h = ByteArray(BLOCK).also { aes.encryptBlock(ByteArray(BLOCK), 0, it, 0) }
        val j0 = ByteArray(BLOCK).also {
            nonce.copyInto(it)
            it[BLOCK - 1] = 1
        }
        val ciphertext = ByteArray(plaintext.size)
        val counter = j0.copyOf()
        val keystream = ByteArray(BLOCK)
        var offset = 0
        while (offset < plaintext.size) {
            increment(counter)
            aes.encryptBlock(counter, 0, keystream, 0)
            val take = minOf(BLOCK, plaintext.size - offset)
            for (i in 0 until take) ciphertext[offset + i] = (plaintext[offset + i].toInt() xor keystream[i].toInt()).toByte()
            offset += take
        }
        val tag = ByteArray(BLOCK).also { aes.encryptBlock(j0, 0, it, 0) }
        val s = ghash(h, aad, ciphertext)
        for (i in 0 until TAG) tag[i] = (tag[i].toInt() xor s[i].toInt()).toByte()
        val out = ByteArray(ciphertext.size + TAG)
        ciphertext.copyInto(out)
        tag.copyInto(out, ciphertext.size, 0, TAG)
        return out
    }

    /** Returns the plaintext, or null when the tag does not verify. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray? {
        if (key.size != 32 || nonce.size != 12 || ciphertextAndTag.size < TAG) return null
        val cipherLength = ciphertextAndTag.size - TAG
        val aes = Aes256(key)
        val h = ByteArray(BLOCK).also { aes.encryptBlock(ByteArray(BLOCK), 0, it, 0) }
        val j0 = ByteArray(BLOCK).also {
            nonce.copyInto(it)
            it[BLOCK - 1] = 1
        }
        val expected = ByteArray(BLOCK).also { aes.encryptBlock(j0, 0, it, 0) }
        val s = ghash(h, aad, ciphertextAndTag.copyOfRange(0, cipherLength))
        for (i in 0 until TAG) expected[i] = (expected[i].toInt() xor s[i].toInt()).toByte()
        var difference = 0
        for (i in 0 until TAG) {
            difference = difference or (expected[i].toInt() xor ciphertextAndTag[cipherLength + i].toInt())
        }
        if (difference != 0) return null
        val plaintext = ByteArray(cipherLength)
        val counter = j0.copyOf()
        val keystream = ByteArray(BLOCK)
        var offset = 0
        while (offset < cipherLength) {
            increment(counter)
            aes.encryptBlock(counter, 0, keystream, 0)
            val take = minOf(BLOCK, cipherLength - offset)
            for (i in 0 until take) plaintext[offset + i] = (ciphertextAndTag[offset + i].toInt() xor keystream[i].toInt()).toByte()
            offset += take
        }
        return plaintext
    }

    private fun ghash(h: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        var y = ByteArray(BLOCK)
        var offset = 0
        while (offset < aad.size) {
            val take = minOf(BLOCK, aad.size - offset)
            val block = ByteArray(BLOCK)
            aad.copyInto(block, 0, offset, offset + take)
            xor(y, block)
            gcmMultiply(y, h)
            offset += take
        }
        offset = 0
        while (offset < ciphertext.size) {
            val take = minOf(BLOCK, ciphertext.size - offset)
            val block = ByteArray(BLOCK)
            ciphertext.copyInto(block, 0, offset, offset + take)
            xor(y, block)
            gcmMultiply(y, h)
            offset += take
        }
        val lengths = ByteArray(BLOCK)
        writeLength(lengths, 0, aad.size.toLong() * 8L)
        writeLength(lengths, 8, ciphertext.size.toLong() * 8L)
        xor(y, lengths)
        gcmMultiply(y, h)
        return y
    }

    private fun xor(target: ByteArray, block: ByteArray) {
        for (i in 0 until BLOCK) target[i] = (target[i].toInt() xor block[i].toInt()).toByte()
    }

    /** NIST SP 800-38D GF(2^128) multiplication by H, R = 0xe1 at the top byte. */
    private fun gcmMultiply(y: ByteArray, h: ByteArray) {
        val z = ByteArray(BLOCK)
        val v = h.copyOf()
        for (i in 0 until BLOCK) {
            val byte = y[i].toInt()
            for (bit in 0 until 8) {
                if (byte and (0x80 ushr bit) != 0) xor(z, v)
                val lsb = v[BLOCK - 1].toInt() and 1
                for (j in BLOCK - 1 downTo 1) {
                    v[j] = (((v[j].toInt() and 0xff) ushr 1) or ((v[j - 1].toInt() and 1) shl 7)).toByte()
                }
                v[0] = ((v[0].toInt() and 0xff) ushr 1).toByte()
                if (lsb != 0) v[0] = (v[0].toInt() xor 0xe1).toByte()
            }
        }
        z.copyInto(y)
    }

    private fun increment(counter: ByteArray) {
        for (i in BLOCK - 1 downTo BLOCK - 4) {
            val next = (counter[i].toInt() and 0xff) + 1
            counter[i] = next.toByte()
            if (next <= 0xff) return
        }
    }

    private fun writeLength(out: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) out[offset + i] = (value ushr (56 - 8 * i)).toByte()
    }
}

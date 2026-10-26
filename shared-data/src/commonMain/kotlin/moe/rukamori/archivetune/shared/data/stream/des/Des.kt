package moe.rukamori.archivetune.shared.data.stream.des

import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException

/**
 * FIPS 46-3 DES decryption in ECB mode with PKCS#5 padding removal, the cipher JioSaavn applies to
 * its `encrypted_media_url` field. The host supplies the 8-byte key; nothing here stores or derives
 * one. Tables are the standard DES permutations and S-boxes.
 */
class Des(keyBytes: ByteArray) {
    private val subKeys: LongArray

    init {
        require(keyBytes.size == 8) { "DES requires an 8-byte key" }
        subKeys = schedule(keyBytes.toLong())
    }

    /** Decrypts one 8-byte block from [input] at [offset] into [output]. */
    fun decryptBlock(input: ByteArray, offset: Int, output: ByteArray, outputOffset: Int) {
        var block = permute(input.toLong(offset), IP, 64)
        var left = (block ushr 32).toInt()
        var right = block.toInt()
        for (round in 15 downTo 0) {
            val next = left xor f(right, subKeys[round])
            left = right
            right = next
        }
        block = permute((right.toLong() shl 32) or (left.toLong() and 0xffffffffL), IP_INVERSE, 64)
        output.writeLong(block, outputOffset)
    }

    /** DES/ECB decryption then PKCS#5 unpad, the exact transform `DES/ECB/PKCS5Padding` performs. */
    fun decryptEcbPkcs5(input: ByteArray): ByteArray {
        require(input.isNotEmpty() && input.size % 8 == 0) { "DES input must be block aligned" }
        val out = ByteArray(input.size)
        var offset = 0
        while (offset < input.size) {
            decryptBlock(input, offset, out, offset)
            offset += 8
        }
        val pad = out[out.size - 1].toInt() and 0xff
        if (pad !in 1..8) throw ByteSourceException("Invalid DES padding")
        for (i in out.size - pad until out.size) {
            if ((out[i].toInt() and 0xff) != pad) throw ByteSourceException("Invalid DES padding")
        }
        return out.copyOf(out.size - pad)
    }

    private fun f(right: Int, subKey: Long): Int {
        val expanded = permute(right.toLong() and 0xffffffffL, E, 32) xor subKey
        var out = 0
        for (box in 0 until 8) {
            val six = ((expanded ushr (42 - box * 6)) and 0x3f).toInt()
            val row = ((six and 0x20) ushr 4) or (six and 0x01)
            val col = (six ushr 1) and 0x0f
            out = (out shl 4) or S_BOXES[box][row * 16 + col]
        }
        return permute(out.toLong() and 0xffffffffL, P, 32).toInt()
    }

    private companion object {
        private fun schedule(key: Long): LongArray {
            var value = permute(key, PC1, 64)
            var c = (value ushr 28) and 0x0fffffff
            var d = value and 0x0fffffff
            val keys = LongArray(16)
            for (round in 0 until 16) {
                c = ((c shl SHIFTS[round]) or (c ushr (28 - SHIFTS[round]))) and 0x0fffffff
                d = ((d shl SHIFTS[round]) or (d ushr (28 - SHIFTS[round]))) and 0x0fffffff
                keys[round] = permute((c shl 28) or d, PC2, 56)
            }
            return keys
        }

        /** Permutes the low [inputBits] of [input] left-to-right through [table]; tables are 1-based bit positions. */
        private fun permute(input: Long, table: IntArray, inputBits: Int): Long {
            var out = 0L
            for (position in table) {
                out = (out shl 1) or ((input ushr (inputBits - position)) and 1L)
            }
            return out
        }

        private fun ByteArray.toLong(offset: Int = 0): Long {
            var out = 0L
            for (i in 0 until 8) out = (out shl 8) or (this[offset + i].toLong() and 0xff)
            return out
        }

        private fun ByteArray.writeLong(value: Long, offset: Int = 0) {
            for (i in 0 until 8) this[offset + i] = (value ushr (56 - i * 8)).toByte()
        }

        private val SHIFTS = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

        private val IP = intArrayOf(
            58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4,
            62, 54, 46, 38, 30, 22, 14, 6, 64, 56, 48, 40, 32, 24, 16, 8,
            57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
            61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
        )

        private val IP_INVERSE = intArrayOf(
            40, 8, 48, 16, 56, 24, 64, 32, 39, 7, 47, 15, 55, 23, 63, 31,
            38, 6, 46, 14, 54, 22, 62, 30, 37, 5, 45, 13, 53, 21, 61, 29,
            36, 4, 44, 12, 52, 20, 60, 28, 35, 3, 43, 11, 51, 19, 59, 27,
            34, 2, 42, 10, 50, 18, 58, 26, 33, 1, 41, 9, 49, 17, 57, 25,
        )

        private val E = intArrayOf(
            32, 1, 2, 3, 4, 5, 4, 5, 6, 7, 8, 9, 8, 9, 10, 11,
            12, 13, 12, 13, 14, 15, 16, 17, 16, 17, 18, 19, 20, 21, 20, 21,
            22, 23, 24, 25, 24, 25, 26, 27, 28, 29, 28, 29, 30, 31, 32, 1,
        )

        private val P = intArrayOf(
            16, 7, 20, 21, 29, 12, 28, 17, 1, 15, 23, 26, 5, 18, 31, 10,
            2, 8, 24, 14, 32, 27, 3, 9, 19, 13, 30, 6, 22, 11, 4, 25,
        )

        private val PC1 = intArrayOf(
            57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18,
            10, 2, 59, 51, 43, 35, 27, 19, 11, 3, 60, 52, 44, 36,
            63, 55, 47, 39, 31, 23, 15, 7, 62, 54, 46, 38, 30, 22,
            14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 28, 20, 12, 4,
        )

        private val PC2 = intArrayOf(
            14, 17, 11, 24, 1, 5, 3, 28, 15, 6, 21, 10,
            23, 19, 12, 4, 26, 8, 16, 7, 27, 20, 13, 2,
            41, 52, 31, 37, 47, 55, 30, 40, 51, 45, 33, 48,
            44, 49, 39, 56, 34, 53, 46, 42, 50, 36, 29, 32,
        )

        private val S_BOXES = arrayOf(
            intArrayOf(
                14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
                0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
                4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
                15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
            ),
            intArrayOf(
                15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
                3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5,
                0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
                13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
            ),
            intArrayOf(
                10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
                13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
                13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
                1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
            ),
            intArrayOf(
                7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
                13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
                10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
                3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
            ),
            intArrayOf(
                2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
                14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
                4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
                11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
            ),
            intArrayOf(
                12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
                10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
                9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
                4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
            ),
            intArrayOf(
                4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
                13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
                1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
                6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
            ),
            intArrayOf(
                13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
                1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
                7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
                2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
            ),
        )
    }
}

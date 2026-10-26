package moe.rukamori.archivetune.shared.data.bytesource

import kotlin.math.abs
import kotlin.math.sin

/** Pure-Kotlin MD5 (RFC 1321) so common code does not need a platform digest. */
internal object Md5 {
    private val roundConstants: IntArray =
        IntArray(64) { i -> (abs(sin((i + 1).toDouble())) * 4294967296.0).toLong().toInt() }

    private val shifts: IntArray =
        intArrayOf(
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
        )

    fun digest(input: ByteArray): ByteArray {
        val paddedLength = ((input.size + 8) / 64 + 1) * 64
        val message = input.copyOf(paddedLength)
        message[input.size] = 0x80.toByte()
        val bitLength = input.size.toLong() * 8
        for (i in 0 until 8) {
            message[paddedLength - 8 + i] = (bitLength ushr (8 * i)).toByte()
        }

        var a0 = 0x67452301
        var b0 = 0xefcdab89.toInt()
        var c0 = 0x98badcfe.toInt()
        var d0 = 0x10325476

        val words = IntArray(16)
        for (blockStart in 0 until paddedLength step 64) {
            for (w in 0 until 16) {
                val base = blockStart + w * 4
                words[w] =
                    (message[base].toInt() and 0xff) or
                        ((message[base + 1].toInt() and 0xff) shl 8) or
                        ((message[base + 2].toInt() and 0xff) shl 16) or
                        ((message[base + 3].toInt() and 0xff) shl 24)
            }
            var a = a0
            var b = b0
            var c = c0
            var d = d0
            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when (i / 16) {
                    0 -> { f = (b and c) or (b.inv() and d); g = i }
                    1 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    2 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                val rotated = f + a + roundConstants[i] + words[g]
                a = d
                d = c
                c = b
                b += (rotated shl shifts[i]) or (rotated ushr (32 - shifts[i]))
            }
            a0 += a
            b0 += b
            c0 += c
            d0 += d
        }

        val out = ByteArray(16)
        val state = intArrayOf(a0, b0, c0, d0)
        for (w in 0 until 4) {
            for (i in 0 until 4) {
                out[w * 4 + i] = (state[w] ushr (8 * i)).toByte()
            }
        }
        return out
    }

    fun hexDigest(input: ByteArray): String =
        digest(input).joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
}

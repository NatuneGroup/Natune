package moe.rukamori.archivetune.shared.domain.localmedia

/**
 * The stable local identities, the portable form of the retained `LOCAL_ARTIST_`/`LOCAL_ALBUM_`/track ids built by
 * `UUID.nameUUIDFromBytes`: an MD5 digest over the natural key with the RFC 4122 version/variant bits set. The same
 * name always maps to the same id on every platform, so a rescan never re-keys a row.
 */
object LocalStableIds {
    const val TRACK_PREFIX = "local:"
    const val ARTIST_PREFIX = "LOCAL_ARTIST_"
    const val ALBUM_PREFIX = "LOCAL_ALBUM_"

    fun trackId(path: String): String = TRACK_PREFIX + nameUuid(path.encodeToByteArray())
    fun artistId(name: String): String = ARTIST_PREFIX + nameUuid(name.encodeToByteArray())
    fun albumId(title: String, artistId: String?): String =
        ALBUM_PREFIX + nameUuid("$title|${artistId ?: ""}".encodeToByteArray())

    /** `UUID.nameUUIDFromBytes`: MD5, version 3, IETF variant, lower hex in the canonical 8-4-4-4-12 layout. */
    private fun nameUuid(bytes: ByteArray): String {
        val digest = md5(bytes)
        digest[6] = (digest[6].toInt() and 0x0F or 0x30).toByte()
        digest[8] = (digest[8].toInt() and 0x3F or 0x80).toByte()
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private fun md5(input: ByteArray): ByteArray {
        val s = intArrayOf(
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
        )
        val k = IntArray(64) { i ->
            val radians = kotlin.math.abs(kotlin.math.sin(i + 1.0)) * 4294967296.0
            radians.toInt()
        }
        var a0 = 0x67452301; var b0 = -0x10325477; var c0 = -0x67452302; var d0 = 0x10325476
        val message = input.copyOf()
        val bitLength = input.size.toLong() * 8
        val padded = message + ByteArray(((56 - (message.size + 1) % 64 + 64) % 64) + 1 + 8)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 8 + i] = ((bitLength ushr (8 * i)) and 0xFF).toByte()
        for (blockStart in padded.indices step 64) {
            val m = IntArray(16) { i ->
                (padded[blockStart + i * 4].toInt() and 0xFF) or
                    ((padded[blockStart + i * 4 + 1].toInt() and 0xFF) shl 8) or
                    ((padded[blockStart + i * 4 + 2].toInt() and 0xFF) shl 16) or
                    ((padded[blockStart + i * 4 + 3].toInt() and 0xFF) shl 24)
            }
            var a = a0; var b = b0; var c = c0; var d = d0
            for (i in 0 until 64) {
                var f: Int; var g: Int
                when (i / 16) {
                    0 -> { f = (b and c) or (b.inv() and d); g = i }
                    1 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    2 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                val tmp = d; d = c; c = b
                b = b + (a + f + k[i] + m[g]).rotateLeft(s[i]); a = tmp
            }
            a0 += a; b0 += b; c0 += c; d0 += d
        }
        return intArrayOf(a0, b0, c0, d0).flatMap { value ->
            listOf(value and 0xFF, (value ushr 8) and 0xFF, (value ushr 16) and 0xFF, (value ushr 24) and 0xFF)
        }.map { it.toByte() }.toByteArray()
    }
}

package moe.rukamori.archivetune.shared.data.stream.cenc

import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource

/**
 * Amazon-style CENC fragmented MP4 support (scheme `cenc`, AES-128-CTR, FLAC or Opus sample entries). Port of the
 * Android `AmazonCencDecryptor`, reshaped from an in-place decrypt of a whole downloaded file into a random-access
 * view. The ciphertext stays behind its lease, so a large hi-res track is never staged as a plaintext file.
 */
object CencMp4 {
    /** A plaintext view of an encrypted stream and the codec label its sample entry was restored to ("flac", "opus"). */
    class Plaintext internal constructor(
        val source: RandomAccessByteSource,
        val codec: String,
    )

    /**
     * Opens [ciphertext] as plaintext using [keyHex]. Returns null when the stream is not a supported `cenc` MP4 or
     * its structure is malformed, so the caller can fall through to another source. I/O failures and cancellation
     * propagate unchanged.
     */
    suspend fun openPlaintext(
        ciphertext: RandomAccessByteSource,
        keyHex: String,
    ): Plaintext? {
        val key = parseKey(keyHex) ?: return null
        val indexed =
            try {
                CencMp4Index.build(ciphertext)
            } catch (anomaly: CencFormatException) {
                return null
            }
        return Plaintext(CencPlaintextSource(ciphertext, indexed.map, key), indexed.codec)
    }

    /**
     * Parses the 16-byte content key. The instance API hands out bare hex, and `KID:KEY` pairs are accepted too, keeping
     * only the key. The key is never logged or echoed in an error.
     */
    fun parseKey(raw: String): ByteArray? {
        val hex = raw.trim().substringAfterLast(':').removePrefix("0x").filterNot { it.isWhitespace() }
        if (hex.length != KEY_HEX_DIGITS) return null
        val out = ByteArray(KEY_BYTES)
        for (i in 0 until KEY_BYTES) {
            val high = hexDigit(hex[i * 2])
            val low = hexDigit(hex[i * 2 + 1])
            if (high < 0 || low < 0) return null
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private const val KEY_BYTES = 16
    private const val KEY_HEX_DIGITS = 32

    private fun hexDigit(char: Char): Int =
        when (char) {
            in '0'..'9' -> char - '0'
            in 'a'..'f' -> char - 'a' + 10
            in 'A'..'F' -> char - 'A' + 10
            else -> -1
        }
}

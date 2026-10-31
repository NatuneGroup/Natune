package moe.rukamori.archivetune.shared.data.stream.spotify

import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.bytesource.readUpTo
import moe.rukamori.archivetune.shared.data.stream.NativePlaintext
import moe.rukamori.archivetune.shared.data.stream.aes.Aes128

/** The retained Echo AES-CTR view, including Spotify's Vorbis header, without a plaintext file or URL. */
object SpotifyPlayPlaySource {
    suspend fun open(
        ciphertext: RandomAccessByteSource,
        keyHex: String,
        ivHex: String,
        flac: Boolean,
    ): NativePlaintext? {
        val key = hexBytes(keyHex) ?: return null
        val iv = hexBytes(ivHex) ?: return null
        val skip = if (flac) 0L else VORBIS_HEADER_BYTES
        if (ciphertext.lease.lengthBytes?.let { it < skip + 4 } == true) return null
        val source = Plaintext(ciphertext, key, iv, skip)
        val magic = source.readUpTo(0, 4).bytes.decodeToString()
        if (magic != if (flac) "fLaC" else "OggS") return null
        return NativePlaintext(source, if (flac) "audio/flac" else "audio/ogg",
            if (flac) "flac" else "vorbis", source.lease.lengthBytes, source.lease.expiresAtEpochMs)
    }

    private fun hexBytes(value: String): ByteArray? {
        if (value.length != 32 || value.any { it.digitToIntOrNull(16) == null }) return null
        return ByteArray(16) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private class Plaintext(
        private val ciphertext: RandomAccessByteSource,
        key: ByteArray,
        private val iv: ByteArray,
        private val skip: Long,
    ) : RandomAccessByteSource {
        override val lease: ByteSourceLease = ciphertext.lease.copy(
            lengthBytes = ciphertext.lease.lengthBytes?.minus(skip),
        )
        private val aes = Aes128(key)

        override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            require(position >= 0 && position <= Long.MAX_VALUE - skip)
            require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
            if (length == 0) return 0
            if (lease.lengthBytes?.let { position >= it } == true) return RandomAccessByteSource.END_OF_STREAM
            val absolute = position + skip
            val count = minOf(length.toLong(), Long.MAX_VALUE - absolute,
                lease.lengthBytes?.minus(position) ?: length.toLong()).toInt()
            if (count == 0) return RandomAccessByteSource.END_OF_STREAM
            val read = ciphertext.readAt(absolute, buffer, offset, count)
            if (read <= 0) return read
            val counter = ByteArray(16)
            val keystream = ByteArray(16)
            var done = 0
            while (done < read) {
                val at = absolute + done
                iv.copyInto(counter)
                var carry = at / 16
                for (index in 15 downTo 0) {
                    val sum = (counter[index].toLong() and 255L) + (carry and 255L)
                    counter[index] = sum.toByte()
                    carry = (carry ushr 8) + (sum ushr 8)
                }
                aes.encryptBlock(counter, 0, keystream, 0)
                val inBlock = (at % 16).toInt()
                val take = minOf(16 - inBlock, read - done)
                repeat(take) { index ->
                    val target = offset + done + index
                    buffer[target] = (buffer[target].toInt() xor keystream[inBlock + index].toInt()).toByte()
                }
                done += take
            }
            return read
        }
    }

    private const val VORBIS_HEADER_BYTES = 0xA7L
}

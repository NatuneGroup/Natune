package moe.rukamori.archivetune.shared.data.stream.hls

import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.bytesource.readUpTo
import moe.rukamori.archivetune.shared.data.stream.aes.Aes128
import moe.rukamori.archivetune.shared.data.stream.aes.AesCbc
import kotlin.math.max
import kotlin.math.min

/** A parsed `#EXT-X-KEY` AES-128 tag. [iv] is null when the playlist omits it. */
class HlsKeyTag(
    val keyUri: String,
    val iv: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HlsKeyTag) return false
        return keyUri == other.keyUri && (iv == null && other.iv == null || iv != null && other.iv != null && iv.contentEquals(other.iv))
    }

    override fun hashCode(): Int = 31 * keyUri.hashCode() + (iv?.contentHashCode() ?: 0)

    override fun toString(): String = "HlsKeyTag(keyUri=$keyUri, hasIv=${iv != null})"
}

/** HLS AES-128 tag parsing and IV derivation (RFC 8216 section 4.3.2.4). */
object HlsAes128 {
    private const val KEY_TAG_PREFIX = "#EXT-X-KEY:"
    private const val METHOD_AES_128 = "AES-128"
    private const val IV_BYTES = 16
    private const val HEX_IV_DIGITS = 32

    /**
     * Parses an `#EXT-X-KEY` line. Returns null unless METHOD is AES-128 and URI is present, so
     * SAMPLE-AES and NONE are left to the caller. A malformed IV is a playlist error and throws.
     */
    fun parseKeyTag(line: String): HlsKeyTag? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(KEY_TAG_PREFIX)) return null
        val attributes = parseAttributes(trimmed.substring(KEY_TAG_PREFIX.length))
        if (attributes["METHOD"] != METHOD_AES_128) return null
        val uri = attributes["URI"]?.takeIf { it.isNotEmpty() } ?: return null
        val iv = attributes["IV"]?.let { parseHexIv(it) }
        return HlsKeyTag(uri, iv)
    }

    /**
     * Parses `0x` followed by 1 to 32 hex digits (case-insensitive) into 16 big-endian bytes. A short literal is the
     * low bytes of the IV and left-pads with zeros, as the specification's 128-bit big-endian form does.
     */
    fun parseHexIv(text: String): ByteArray {
        if (!text.startsWith("0x", ignoreCase = true)) {
            throw ByteSourceException("HLS IV must be 0x followed by hex digits")
        }
        val digits = text.substring(2)
        if (digits.isEmpty() || digits.length > HEX_IV_DIGITS) {
            throw ByteSourceException("HLS IV must be 1 to 32 hex digits")
        }
        val padded = digits.padStart(HEX_IV_DIGITS, '0')
        val out = ByteArray(IV_BYTES)
        for (i in 0 until IV_BYTES) {
            val high = hexValue(padded[2 * i])
            val low = hexValue(padded[2 * i + 1])
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    /**
     * The explicit IV when the playlist supplied one; otherwise the media sequence number as a 16-byte
     * big-endian value, which is what the HLS specification mandates when IV is omitted.
     */
    fun initializationVector(
        explicitIv: ByteArray?,
        mediaSequenceNumber: Long,
    ): ByteArray {
        if (explicitIv != null) return explicitIv.copyOf()
        val out = ByteArray(IV_BYTES)
        for (i in 0 until 8) {
            out[IV_BYTES - 1 - i] = (mediaSequenceNumber ushr (8 * i)).toByte()
        }
        return out
    }

    private fun hexValue(char: Char): Int =
        when (char) {
            in '0'..'9' -> char - '0'
            in 'a'..'f' -> char - 'a' + 10
            in 'A'..'F' -> char - 'A' + 10
            else -> throw ByteSourceException("Invalid hex digit in HLS IV")
        }

    /**
     * Splits a comma-separated attribute list. Commas inside a quoted value do not split, and
     * quotes are stripped from the value.
     */
    internal fun parseAttributes(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var index = 0
        while (index < text.length) {
            val equals = text.indexOf('=', index)
            if (equals < 0) break
            val key = text.substring(index, equals).trim()
            var valueStart = equals + 1
            val value: String
            if (valueStart < text.length && text[valueStart] == '"') {
                val close = text.indexOf('"', valueStart + 1)
                if (close < 0) break
                value = text.substring(valueStart + 1, close)
                valueStart = close + 1
            } else {
                val comma = text.indexOf(',', valueStart).let { if (it < 0) text.length else it }
                value = text.substring(valueStart, comma).trim()
                valueStart = comma
            }
            if (key.isNotEmpty()) result[key] = value
            val next = text.indexOf(',', valueStart)
            if (next < 0) break
            index = next + 1
        }
        return result
    }
}

/**
 * Decrypts one AES-128 CBC encrypted HLS segment with PKCS#7 padding, exposing the plaintext as a
 * random-access source. The ciphertext length must be known up front so the padding can be learned.
 */
class HlsAes128SegmentSource(
    private val ciphertext: RandomAccessByteSource,
    private val aes: Aes128,
    private val iv: ByteArray,
) : RandomAccessByteSource {
    override val lease = ciphertext.lease

    private val cipherLength: Long

    /** Null until the last block has been read; a plaintext length is only known after that. */
    private var plaintextLengthCache: Long? = null

    init {
        val length = ciphertext.lease.lengthBytes ?: throw ByteSourceException("HLS segment needs a known ciphertext length")
        if (length <= 0 || length % BLOCK_SIZE != 0L) {
            throw ByteSourceException("HLS segment ciphertext must be a positive multiple of 16 bytes")
        }
        require(iv.size == BLOCK_SIZE) { "HLS segment IV must be 16 bytes" }
        cipherLength = length
    }

    override suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val plainLength = plaintextLength() ?: return 0
        if (position >= plainLength) return RandomAccessByteSource.END_OF_STREAM
        if (length == 0) return 0
        val wanted = min(length.toLong(), plainLength - position).toInt()

        val firstBlock = position / BLOCK_SIZE
        val lastBlock = (position + wanted - 1) / BLOCK_SIZE
        // One block of lead-in provides the CBC chaining value for the first decrypted block.
        val fetchStart = max(firstBlock - 1, 0L)
        val fetchBlocks = (lastBlock - fetchStart + 1).toInt()
        val fetched = ciphertext.readUpTo(fetchStart * BLOCK_SIZE, fetchBlocks * BLOCK_SIZE).bytes

        val chainOffset: Int
        val chain: ByteArray
        if (firstBlock == 0L) {
            chainOffset = 0
            chain = iv
        } else {
            if (fetched.size < BLOCK_SIZE) return 0
            chainOffset = BLOCK_SIZE
            chain = fetched.copyOfRange(0, BLOCK_SIZE)
        }
        val available = (fetched.size - chainOffset) / BLOCK_SIZE
        val targetBlocks = (lastBlock - firstBlock + 1).toInt()
        val blockCount = min(available, targetBlocks)
        if (blockCount <= 0) return 0

        val plain = decryptBlocks(fetched, chainOffset, blockCount, chain)
        val skip = (position - firstBlock * BLOCK_SIZE).toInt()
        val count = min(wanted, plain.size - skip)
        if (count <= 0) return 0
        plain.copyInto(buffer, offset, skip, skip + count)
        return count
    }

    private fun decryptBlocks(
        cipher: ByteArray,
        start: Int,
        blockCount: Int,
        initialChain: ByteArray,
    ): ByteArray {
        val plain = ByteArray(blockCount * BLOCK_SIZE)
        var chain = initialChain
        val decrypted = ByteArray(BLOCK_SIZE)
        for (b in 0 until blockCount) {
            val blockStart = start + b * BLOCK_SIZE
            aes.decryptBlock(cipher, blockStart, decrypted, 0)
            for (i in 0 until BLOCK_SIZE) {
                plain[b * BLOCK_SIZE + i] = (decrypted[i].toInt() xor chain[i].toInt()).toByte()
            }
            chain = cipher.copyOfRange(blockStart, blockStart + BLOCK_SIZE)
        }
        return plain
    }

    /** Learns the plaintext length from the padded last block; null when that block is not yet readable. */
    internal suspend fun plaintextLength(): Long? {
        plaintextLengthCache?.let { return it }
        val lastIndex = cipherLength / BLOCK_SIZE - 1
        val prefixStart = max(lastIndex - 1, 0L)
        val span = ciphertext.readUpTo(prefixStart * BLOCK_SIZE, ((lastIndex - prefixStart + 1) * BLOCK_SIZE).toInt()).bytes
        if (span.size < BLOCK_SIZE) return null
        val lastCipher = span.copyOfRange(span.size - BLOCK_SIZE, span.size)
        val previousBlock =
            if (lastIndex == 0L) {
                iv
            } else {
                if (span.size < 2 * BLOCK_SIZE) return null
                span.copyOfRange(0, BLOCK_SIZE)
            }
        val lastPlain = ByteArray(BLOCK_SIZE)
        aes.decryptBlock(lastCipher, 0, lastPlain, 0)
        for (i in 0 until BLOCK_SIZE) {
            lastPlain[i] = (lastPlain[i].toInt() xor previousBlock[i].toInt()).toByte()
        }
        val padLength = AesCbc.pkcs7Unpad(lastPlain)
        val plainLength = cipherLength - padLength
        plaintextLengthCache = plainLength
        return plainLength
    }

    private companion object {
        const val BLOCK_SIZE = 16
    }
}

@file:OptIn(ExperimentalEncodingApi::class)

package moe.rukamori.archivetune.shared.data.stream.qmc

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.tan
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource

/**
 * Tencent's modified TEA, which wraps every QMC2 key the service issues.
 *
 * Two departures from stock TEA: the first block is ECB, while each later block is XORed with the previous *decrypted*
 * block before its own ECB pass, and the whole tail is finally XORed with the ciphertext shifted by one block. The
 * plaintext is framed with a pad-length byte, up to seven padding bytes, a two-byte salt and seven trailing zero bytes.
 * Those trailing zeros double as the check that tells a right key from a wrong one.
 */
internal object TcTea {
    private const val ROUNDS = 16
    private const val DELTA = 0x9E3779B9.toInt()
    private const val SALT_LEN = 2
    private const val ZERO_LEN = 7
    private const val FIXED_PADDING_LEN = 1 + SALT_LEN + ZERO_LEN

    /** Salt for the per-file TEA key. It is part of the scheme, not a tunable. */
    private const val KEY_SALT = 106

    fun decrypt(
        encrypted: ByteArray,
        key: ByteArray,
    ): ByteArray? {
        if (key.size < 16) return null
        val length = encrypted.size
        if (length < FIXED_PADDING_LEN || length % 8 != 0) return null

        val schedule = IntArray(4) { readU32Be(key, it * 4) }
        val buffer = encrypted.copyOf()
        decryptBlock(buffer, 0, schedule)
        var offset = 8
        while (offset < length) {
            for (index in 0 until 8) {
                buffer[offset + index] =
                    (buffer[offset + index].toInt() xor buffer[offset + index - 8].toInt()).toByte()
            }
            decryptBlock(buffer, offset, schedule)
            offset += 8
        }
        for (index in 0 until length - 8) {
            buffer[8 + index] = (buffer[8 + index].toInt() xor encrypted[index].toInt()).toByte()
        }

        val padLength = buffer[0].toInt() and 0x07
        val start = 1 + padLength + SALT_LEN
        val end = length - ZERO_LEN
        if (start > end) return null
        for (index in end until length) {
            if (buffer[index].toInt() != 0) return null
        }
        return buffer.copyOfRange(start, end)
    }

    /** `simple[i] = truncate(100 * |tan(seed + i / 10)|)`, in single precision as the scheme does. */
    fun simpleMakeKey(
        seed: Int,
        size: Int,
    ): ByteArray =
        ByteArray(size) { index ->
            val value = seed.toFloat() + index.toFloat() * 0.1f
            (100.0f * abs(tan(value))).toInt().toByte()
        }

    /** The 16-byte TEA key: the salt bytes interleaved with the file's own 8-byte header. */
    fun deriveTeaKey(header: ByteArray): ByteArray {
        val simple = simpleMakeKey(KEY_SALT, 8)
        val teaKey = ByteArray(16)
        for (index in 0 until 8) {
            teaKey[index * 2] = simple[index]
            teaKey[index * 2 + 1] = header[index]
        }
        return teaKey
    }

    private fun decryptBlock(
        buffer: ByteArray,
        offset: Int,
        schedule: IntArray,
    ) {
        var y = readU32Be(buffer, offset)
        var z = readU32Be(buffer, offset + 4)
        var sum = DELTA * ROUNDS
        repeat(ROUNDS) {
            z -= round(y, sum, schedule[2], schedule[3])
            y -= round(z, sum, schedule[0], schedule[1])
            sum -= DELTA
        }
        writeU32Be(buffer, offset, y)
        writeU32Be(buffer, offset + 4, z)
    }

    private fun round(
        value: Int,
        sum: Int,
        key1: Int,
        key2: Int,
    ): Int = ((value shl 4) + key1) xor (sum + value) xor ((value ushr 5) + key2)

    private fun readU32Be(
        buffer: ByteArray,
        offset: Int,
    ): Int =
        ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)

    private fun writeU32Be(
        buffer: ByteArray,
        offset: Int,
        value: Int,
    ) {
        buffer[offset] = (value ushr 24).toByte()
        buffer[offset + 1] = (value ushr 16).toByte()
        buffer[offset + 2] = (value ushr 8).toByte()
        buffer[offset + 3] = value.toByte()
    }
}

/**
 * The tail a QMC2 container ends with, and the key it carries if any.
 *
 * Four layouts are recognised, tried in the order the modern clients try them. Two of them, the Android `STag` one and
 * the current PC `musicex` one, deliberately carry *metadata* only: the key is not in those files at all, so their
 * payload can only be decrypted with a key the service disclosed. That case is reported as such rather than run through
 * a cipher that cannot possibly be right.
 *
 * Only the 16-byte tail and, for the two key-carrying layouts, the key block are read. A remote container is therefore
 * never fetched whole just to find its footer.
 */
internal object QmcEkey {
    /** `QTag` read as a little-endian word. */
    private const val QTAG_MAGIC = 0x67615451

    /** The older layout's key-length word; anything longer than this is not a key length. */
    private const val V1_MAX_KEY_SIZE = 0x400

    /**
     * Largest QTag metadata block this port reads. Real blocks are a few hundred bytes. A larger declared block is
     * refused instead of fetched, because the alternative is guessing at a key that was never read.
     */
    private const val MAX_QTAG_META_BYTES = 0x10000

    private const val STAG_MAGIC = "STag"
    private const val MUSICEX_MAGIC = "musicex\u0000"
    private const val MUSICEX_VERSION = 1
    private const val MUSICEX_TAG_SIZE = 0xC0

    private const val ENCV2_PREFIX = "QQMusic EncV2,Key:"

    /** Fixed TEA keys for the two passes that unwrap an `EncV2` key. */
    private const val ENCV2_STAGE1_KEY = "386ZJY!@#*$%^&)("
    private const val ENCV2_STAGE2_KEY = "**#!(#$%&^a1cZ,T"

    /** The footer fields all sit within the last sixteen bytes of a container. */
    private const val TAIL_BYTES = 16

    /** Where a container's key comes from, and therefore whether it is readable at all. */
    enum class FooterKind {
        /** Newer Android layout: a big-endian payload length, then the key in a CSV before `QTag`. */
        QTAG,

        /** Older layout: the key's own length as a little-endian word at the very end. */
        LEGACY_LENGTH,

        /** Android layout carrying metadata only. The key has to come from the service. */
        STAG,

        /** Current PC layout carrying metadata only. The key has to come from the service. */
        MUSICEX,

        /** Nothing recognised, so the whole file is the payload. */
        NONE,
    }

    /** A container's tail: how it is laid out, the key it carries if any, and where the audio ends. */
    class Footer(
        val kind: FooterKind,
        val keyText: String?,
        val audioLength: Long,
    )

    /**
     * Reads the footer of a container of [total] bytes. A file whose tail is not one of the known layouts comes back as
     * [FooterKind.NONE] with the whole file as its payload, which is the correct reading of a container without a
     * trailer. Returns null only when a recognised key block is unreadable or larger than this port will fetch.
     */
    suspend fun findFooter(
        source: RandomAccessByteSource,
        total: Long,
    ): Footer? {
        val tail = readExactly(source, total - TAIL_BYTES, TAIL_BYTES) ?: return null
        val whole = Footer(FooterKind.NONE, null, total)

        if (endsWith(tail, STAG_MAGIC)) {
            val payloadLength = readU32Be(tail, TAIL_BYTES - 8)
            val footerSize = payloadLength.toLong() + 8L
            return if (payloadLength > 0 && footerSize < total) {
                Footer(FooterKind.STAG, null, total - footerSize)
            } else {
                whole
            }
        }

        if (endsWith(tail, MUSICEX_MAGIC)) {
            // The trailer is a little-endian tag size and version immediately before the magic.
            val version = readU32Le(tail, TAIL_BYTES - 12)
            val tagSize = readU32Le(tail, TAIL_BYTES - 16)
            return if (version == MUSICEX_VERSION && tagSize == MUSICEX_TAG_SIZE && tagSize.toLong() < total) {
                Footer(FooterKind.MUSICEX, null, total - tagSize)
            } else {
                whole
            }
        }

        val trailing = readU32Le(tail, TAIL_BYTES - 4)
        if (trailing == QTAG_MAGIC) {
            val metaSize = readU32Be(tail, TAIL_BYTES - 8).toLong()
            val endOfMeta = total - 8L
            if (metaSize <= 0L || metaSize >= endOfMeta) return whole
            if (metaSize > MAX_QTAG_META_BYTES) return null
            val keyStart = endOfMeta - metaSize
            val meta = readExactly(source, keyStart, metaSize.toInt()) ?: return null
            val comma = indexOfByte(meta, ',')
            if (comma < 0) return whole
            return Footer(FooterKind.QTAG, meta.decodeToString(0, comma), keyStart)
        }

        if (trailing in 1..V1_MAX_KEY_SIZE) {
            val keyStart = total - 4L - trailing
            if (keyStart < 0L) return whole
            val key = readExactly(source, keyStart, trailing) ?: return null
            return Footer(FooterKind.LEGACY_LENGTH, key.decodeToString(), keyStart)
        }
        return whole
    }

    /**
     * Turns a footer key into the raw bytes the stream ciphers take.
     *
     * A key is base64 of an 8-byte header followed by a TEA-wrapped body, and the body's TEA key is derived from that
     * header. The `EncV2` variant wraps the whole thing in two more TEA passes under fixed keys before base64. If the
     * body does not survive the TEA pass the blob is taken as a raw key, which is how the service hands out a key it
     * generated itself.
     */
    fun derive(ekey: String): ByteArray? {
        val trimmed = ekey.trim().trim('\u0000')
        if (trimmed.isBlank()) return null
        val decoded = decodeBase64(trimmed) ?: return null
        if (decoded.size < 8) return null

        val unwrapped =
            if (decoded.startsWithBytes(ENCV2_PREFIX.encodeToByteArray())) {
                val stage1 =
                    TcTea.decrypt(
                        decoded.copyOfRange(ENCV2_PREFIX.length, decoded.size),
                        ENCV2_STAGE1_KEY.encodeToByteArray(),
                    ) ?: return null
                val stage2 = TcTea.decrypt(stage1, ENCV2_STAGE2_KEY.encodeToByteArray()) ?: return null
                decodeBase64(stage2.decodeToString().trim().trim('\u0000')) ?: return null
            } else {
                decoded
            }
        if (unwrapped.size < 8) return null

        val header = unwrapped.copyOfRange(0, 8)
        if (unwrapped.size == 8) return header
        val body = TcTea.decrypt(unwrapped.copyOfRange(8, unwrapped.size), TcTea.deriveTeaKey(header))
        return if (body == null) unwrapped else header + body
    }

    /** Strict base64 with the standard alphabet. Anything else is refused, never skipped over. */
    private fun decodeBase64(text: String): ByteArray? =
        try {
            Base64.Default.decode(text)
        } catch (malformed: IllegalArgumentException) {
            null
        }

    private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }

    private fun indexOfByte(
        data: ByteArray,
        target: Char,
    ): Int {
        val wanted = target.code.toByte()
        for (index in data.indices) {
            if (data[index] == wanted) return index
        }
        return -1
    }

    /** True when the last bytes of [tail] are the ASCII [suffix]. */
    private fun endsWith(
        tail: ByteArray,
        suffix: String,
    ): Boolean {
        val start = tail.size - suffix.length
        if (start < 0) return false
        for (index in suffix.indices) {
            if (tail[start + index] != suffix[index].code.toByte()) return false
        }
        return true
    }

    private fun readU32Le(
        data: ByteArray,
        offset: Int,
    ): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    private fun readU32Be(
        data: ByteArray,
        offset: Int,
    ): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
}

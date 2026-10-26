package moe.rukamori.archivetune.shared.domain.mediainfo

/** Milliseconds per second, used to turn sample or byte counts into a duration. */
internal const val MILLIS_PER_SECOND: Long = 1000L

/**
 * Big- and little-endian readers for media headers. Callers bounds-check every offset before reading, so these
 * helpers never need to report a short read. Kept internal: the header parser is the only public surface.
 */
internal fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

internal fun ByteArray.beU16(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)

internal fun ByteArray.beU32(offset: Int): Long =
    (u8(offset).toLong() shl 24) or
        (u8(offset + 1).toLong() shl 16) or
        (u8(offset + 2).toLong() shl 8) or
        u8(offset + 3).toLong()

internal fun ByteArray.beU64(offset: Int): Long = (beU32(offset) shl 32) or beU32(offset + 4)

internal fun ByteArray.leU16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

internal fun ByteArray.leU32(offset: Int): Long =
    u8(offset).toLong() or
        (u8(offset + 1).toLong() shl 8) or
        (u8(offset + 2).toLong() shl 16) or
        (u8(offset + 3).toLong() shl 24)

/** An ID3 synchsafe integer: 7 bits per byte, so the high bit of every byte is always clear. */
internal fun ByteArray.synchsafeInt(offset: Int): Int =
    ((u8(offset) and 0x7F) shl 21) or
        ((u8(offset + 1) and 0x7F) shl 14) or
        ((u8(offset + 2) and 0x7F) shl 7) or
        (u8(offset + 3) and 0x7F)

/** True when [text] appears at [offset] exactly, and the whole text fits inside the array. */
internal fun ByteArray.matchesAscii(
    offset: Int,
    text: String,
): Boolean {
    if (offset < 0 || offset + text.length > size) return false
    return text.indices.all { index -> this[offset + index] == text[index].code.toByte() }
}

/** A stated sample rate as an Int, or null when the value is zero, negative or out of range. */
internal fun Long.asRate(): Int? = takeIf { it > 0L && it <= Int.MAX_VALUE.toLong() }?.toInt()

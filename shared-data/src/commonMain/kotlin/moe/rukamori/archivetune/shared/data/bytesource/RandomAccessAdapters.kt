package moe.rukamori.archivetune.shared.data.bytesource

import kotlin.math.min
import kotlinx.coroutines.delay

/** In-memory source over [content]; also the reference implementation used by tests. */
class ByteArrayByteSource(
    override val lease: ByteSourceLease,
    private val content: ByteArray,
) : RandomAccessByteSource {
    override suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (position >= content.size) return RandomAccessByteSource.END_OF_STREAM
        val start = position.toInt()
        val count = min(length, content.size - start)
        content.copyInto(buffer, offset, start, start + count)
        return count
    }
}

/**
 * Synthetic source of [totalLength] bytes whose value at position p is a fixed function of p.
 * It exercises 64-bit offsets without allocating the content.
 */
class PatternByteSource(
    override val lease: ByteSourceLease,
    private val totalLength: Long,
) : RandomAccessByteSource {
    override suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (position >= totalLength) return RandomAccessByteSource.END_OF_STREAM
        val count = min(length.toLong(), totalLength - position).toInt()
        for (i in 0 until count) {
            buffer[offset + i] = patternByte(position + i)
        }
        return count
    }

    companion object {
        fun patternByte(position: Long): Byte = ((position * 31 + 7) xor (position ushr 13)).toByte()
    }
}

/** Result of [readUpTo]: the bytes collected and whether upstream reported end of stream. */
internal class ReadResult(
    val bytes: ByteArray,
    val endOfStream: Boolean,
)

/**
 * Reads until [length] bytes are collected, upstream returns 0 or end of stream. Returns the
 * bytes actually collected. Used by the block-cipher and chunk layers, which need whole units.
 */
internal suspend fun RandomAccessByteSource.readUpTo(
    position: Long,
    length: Int,
): ReadResult {
    val out = ByteArray(length)
    var got = 0
    var ended = false
    while (got < length) {
        val read = readAt(position + got, out, got, length - got)
        if (read == RandomAccessByteSource.END_OF_STREAM) {
            ended = true
            break
        }
        if (read <= 0) break
        got += read
    }
    return ReadResult(if (got == length) out else out.copyOf(got), ended)
}

/**
 * Reads a small resource whole, sequentially from position zero. Returns the bytes, or null when the resource is
 * larger than [maxBytes]. A zero-count read is not yet an answer: it is polled for a bounded time, the same bounded
 * idle poll as the TIDAL index uses, and a source that stays silent is a transport failure rather than an empty file.
 */
internal suspend fun RandomAccessByteSource.readWholeBounded(maxBytes: Int): ByteArray? {
    require(maxBytes > 0) { "the whole-read bound must be positive" }
    var buffer = ByteArray(minOf(maxBytes, 16 * 1024))
    var size = 0
    var idlePolls = 0
    while (true) {
        if (size == buffer.size) {
            if (size >= maxBytes) return null
            buffer = buffer.copyOf(minOf(maxBytes, size * 2))
        }
        val read = readAt(size.toLong(), buffer, size, buffer.size - size)
        when {
            read == RandomAccessByteSource.END_OF_STREAM -> return buffer.copyOf(size)
            read > 0 -> {
                size += read
                idlePolls = 0
            }
            else -> {
                idlePolls++
                if (idlePolls > MAX_IDLE_POLLS) throw ByteSourceException("the resource stalled during a whole read")
                delay(IDLE_POLL_MILLIS)
            }
        }
    }
}

private const val MAX_IDLE_POLLS = 400
private const val IDLE_POLL_MILLIS = 25L

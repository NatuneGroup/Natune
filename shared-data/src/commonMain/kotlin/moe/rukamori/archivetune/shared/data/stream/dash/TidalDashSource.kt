/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.stream.dash

import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.stream.NativeCiphertextOpener
import moe.rukamori.archivetune.shared.data.stream.qmc.readExactly

/**
 * A plaintext view over one TIDAL DASH stream, indexed lazily as the reader moves through it.
 *
 * A FLAC stream is rebuilt exactly like the retained progressive remux: the "fLaC" marker, the init segment's STREAMINFO
 * and metadata blocks, then the mdat payloads of each media segment in order. Any other codec is the raw concatenation
 * of the segments. Each segment is read through a host range source, so nothing is staged on disk and no whole segment
 * is held in memory. The index grows only as far as a read needs it, and it commits one segment at a time, so a retried
 * read after a transient failure never duplicates a payload.
 */
internal class TidalDashSource(
    override val lease: ByteSourceLease,
    private val segmentUrls: List<String>,
    private val flac: Boolean,
    private val accountScope: String,
    private val opener: NativeCiphertextOpener,
    // A seam over the wall clock, so the lease expiry check reads one injectable time source.
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : RandomAccessByteSource {
    private val lock = Mutex()
    private val pieces = ArrayList<Piece>()
    private var indexedEnd = 0L
    private var nextSegment = 0

    override suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        // Signed segment URLs stop working at the lease expiry, so a later read fails and the host re-prepares.
        lease.expiresAtEpochMs?.let { expiry ->
            if (nowEpochMs() >= expiry) {
                throw ByteSourceException("TIDAL DASH authorization has expired; prepare the track again")
            }
        }
        if (position < 0L) throw ByteSourceException("TIDAL DASH read before the start of the stream")
        if (length == 0) return 0
        return lock.withLock {
            try {
                while (indexedEnd <= position && nextSegment < segmentUrls.size) indexNextSegment()
            } catch (refusal: DashStreamException) {
                throw ByteSourceException(refusal.message ?: "TIDAL DASH stream is invalid", refusal)
            }
            if (position >= indexedEnd) return@withLock RandomAccessByteSource.END_OF_STREAM

            val piece = pieceAt(position)
            val within = position - piece.start
            val wanted = minOf(length.toLong(), piece.extent.length - within).toInt()
            val literal = piece.extent.literal
            if (literal != null) {
                val from = within.toInt()
                literal.copyInto(buffer, offset, from, from + wanted)
                return@withLock wanted
            }
            val source =
                piece.extent.source ?: throw ByteSourceException("TIDAL DASH stream piece has no source")
            val read = source.readAt(piece.extent.sourceOffset + within, buffer, offset, wanted)
            // The piece length comes from the segment's own box sizes, so an early end means the segment is truncated.
            if (read == RandomAccessByteSource.END_OF_STREAM) {
                throw ByteSourceException("TIDAL DASH segment ended before its declared payload")
            }
            read
        }
    }

    /**
     * Indexes the init segment and the first media segment, so a structural refusal surfaces during preparation rather
     * than in the middle of playback.
     */
    suspend fun indexOpeningSegments() {
        lock.withLock {
            while (nextSegment < minOf(2, segmentUrls.size)) indexNextSegment()
        }
    }

    /** Appends the next segment's extents in one step: a failure leaves the index as it was before the segment. */
    private suspend fun indexNextSegment() {
        val index = nextSegment
        val extents =
            when {
                !flac -> listOf(rawExtent(index))
                index == 0 -> listOf(headerExtent())
                else -> mdatExtents(index)
            }
        var end = indexedEnd
        for (extent in extents) {
            if (extent.length == 0L) continue
            pieces.add(Piece(end, extent))
            end += extent.length
        }
        indexedEnd = end
        nextSegment = index + 1
    }

    private suspend fun openSegment(index: Int): RandomAccessByteSource =
        opener.open(segmentUrls[index], accountScope)
            ?: throw DashStreamException("TIDAL DASH segment ${index + 1} is unavailable")

    /** Raw concatenation needs each segment's length up front, so a host that does not state it cannot be indexed. */
    private suspend fun rawExtent(index: Int): Extent {
        val source = openSegment(index)
        val length =
            source.lease.lengthBytes
                ?: throw DashStreamException("TIDAL DASH segment ${index + 1} did not state its length")
        return Extent(length = length, literal = null, source = source, sourceOffset = 0L)
    }

    /** The FLAC header is the marker plus the init segment's metadata blocks, which are small and kept in memory. */
    private suspend fun headerExtent(): Extent {
        val initBytes = readWhole(openSegment(0), MAX_INIT_BYTES)
        val header = FLAC_MARKER + flacMetadataBlocks(initBytes)
        return Extent(length = header.size.toLong(), literal = header, source = null, sourceOffset = 0L)
    }

    /**
     * Walks the top-level boxes of one media segment with small header reads and records each mdat payload as a
     * range of the segment, so no payload is fetched until a read needs it. Other boxes are skipped, as the retained
     * remux skips them.
     */
    private suspend fun mdatExtents(index: Int): List<Extent> {
        val source = openSegment(index)
        val segmentLength = source.lease.lengthBytes
        val extents = ArrayList<Extent>()
        var offset = 0L
        var foundPayload = false
        while (true) {
            if (segmentLength != null && offset >= segmentLength) break
            // A clean end of the segment lands exactly on a box boundary, so a short header read is the normal stop.
            val head = readExactly(source, offset, 8) ?: break
            val size32 = head.uint32(0)
            val type = head.decodeToString(4, 8)
            val headerSize: Int
            val size: Long
            when (size32) {
                1L -> {
                    val large =
                        readExactly(source, offset + 8, 8)
                            ?: throw DashStreamException("TIDAL DASH segment had a truncated large MP4 box")
                    headerSize = 16
                    size = large.uint64(0)
                }
                0L -> {
                    // A zero size means the box runs to the end of the segment, which only a known length can state.
                    headerSize = 8
                    size =
                        (segmentLength ?: throw DashStreamException("TIDAL DASH segment's last box has no known end")) -
                            offset
                }
                else -> {
                    headerSize = 8
                    size = size32
                }
            }
            if (size < headerSize || (segmentLength != null && offset + size > segmentLength)) {
                throw DashStreamException("TIDAL DASH segment had an invalid MP4 box")
            }
            if (type == "mdat") {
                foundPayload = true
                val payload = size - headerSize
                if (payload > 0L) {
                    extents.add(
                        Extent(length = payload, literal = null, source = source, sourceOffset = offset + headerSize),
                    )
                }
            }
            offset += size
        }
        if (!foundPayload) throw DashStreamException("TIDAL DASH segment had no audio payload")
        return extents
    }

    /** Index of the piece that holds [position]. Pieces are contiguous and sorted, so a binary search finds it. */
    private fun pieceAt(position: Long): Piece {
        var low = 0
        var high = pieces.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (pieces[middle].start <= position) low = middle else high = middle - 1
        }
        return pieces[low]
    }

    /**
     * Reads a short init segment to its end. A zero-byte answer means "not yet", so it is polled for a bounded time,
     * the same bounded idle poll as the shared exact reader.
     */
    private suspend fun readWhole(
        source: RandomAccessByteSource,
        limit: Int,
    ): ByteArray {
        var buffer = ByteArray(INITIAL_READ_BYTES)
        var size = 0
        var idlePolls = 0
        while (true) {
            if (size == buffer.size) {
                if (size >= limit) throw DashStreamException("TIDAL DASH init segment is larger than a FLAC header")
                buffer = buffer.copyOf(minOf(limit, size * 2))
            }
            val read = source.readAt(size.toLong(), buffer, size, buffer.size - size)
            when {
                read == RandomAccessByteSource.END_OF_STREAM -> return buffer.copyOf(size)
                read > 0 -> {
                    size += read
                    idlePolls = 0
                }
                else -> {
                    idlePolls++
                    if (idlePolls > MAX_IDLE_POLLS) throw ByteSourceException("TIDAL DASH init segment stalled")
                    delay(IDLE_POLL_MILLIS)
                }
            }
        }
    }

    /** One contiguous run of the plaintext: either literal bytes or a range of a segment source. */
    private class Extent(
        val length: Long,
        val literal: ByteArray?,
        val source: RandomAccessByteSource?,
        val sourceOffset: Long,
    )

    private class Piece(
        val start: Long,
        val extent: Extent,
    )
}

/**
 * A structural or availability refusal inside a TIDAL DASH stream. Preparation maps it to a refusal, and a read maps it
 * to an I/O failure, so the engine never mistakes a broken stream for a clean end.
 */
internal class DashStreamException(message: String) : Exception(message)

private const val MAX_INIT_BYTES = 1024 * 1024
private const val INITIAL_READ_BYTES = 16 * 1024
private const val MAX_IDLE_POLLS = 400
private const val IDLE_POLL_MILLIS = 25L
private val FLAC_MARKER = "fLaC".encodeToByteArray()

/**
 * Extracts the metadata blocks from the init segment's dfLa box, following the retained rules: the box is found by its
 * type, the first block must be STREAMINFO with the 34-byte length, and any other block is copied unchanged.
 */
private fun flacMetadataBlocks(initBytes: ByteArray): ByteArray {
    val typeOffset = indexOfAscii(initBytes, "dfLa")
    if (typeOffset < 4) throw DashStreamException("TIDAL HiRes init segment had no FLAC metadata")
    val boxStart = typeOffset - 4
    val boxSize = mp4BoxSize(initBytes, boxStart)
    val boxEnd = boxStart.toLong() + boxSize
    // The dfLa payload starts after the box type and its four version and flag bytes.
    val payloadStart = typeOffset + 8
    if (boxSize < 16 || payloadStart + 4 > boxEnd || boxEnd > initBytes.size) {
        throw DashStreamException("TIDAL HiRes FLAC metadata box was invalid")
    }
    val blocks = initBytes.copyOfRange(payloadStart, boxEnd.toInt())
    val firstType = blocks[0].toInt() and 0x7f
    val firstLength =
        ((blocks[1].toInt() and 0xff) shl 16) or
            ((blocks[2].toInt() and 0xff) shl 8) or
            (blocks[3].toInt() and 0xff)
    if (firstType != 0 || firstLength != 34 || blocks.size < 4 + firstLength) {
        throw DashStreamException("TIDAL HiRes FLAC STREAMINFO was invalid")
    }
    return blocks
}

private fun indexOfAscii(
    bytes: ByteArray,
    text: String,
): Int {
    val needle = text.encodeToByteArray()
    if (needle.isEmpty() || bytes.size < needle.size) return -1
    for (start in 0..bytes.size - needle.size) {
        var matches = true
        for (index in needle.indices) {
            if (bytes[start + index] != needle[index]) {
                matches = false
                break
            }
        }
        if (matches) return start
    }
    return -1
}

/** A zero size runs to the end of the buffer and a one size selects the 64-bit size field, as in the retained remux. */
private fun mp4BoxSize(
    bytes: ByteArray,
    offset: Int,
): Long {
    val size =
        when (bytes.uint32(offset)) {
            0L -> bytes.size.toLong() - offset
            1L -> bytes.uint64(offset + 8)
            else -> bytes.uint32(offset)
        }
    if (size <= 0L) throw DashStreamException("TIDAL HiRes segment had an invalid MP4 box size")
    return size
}

private fun ByteArray.uint32(at: Int): Long {
    if (at < 0 || at + 4 > size) throw DashStreamException("TIDAL DASH segment had a truncated MP4 box")
    return ((this[at].toLong() and 0xffL) shl 24) or
        ((this[at + 1].toLong() and 0xffL) shl 16) or
        ((this[at + 2].toLong() and 0xffL) shl 8) or
        (this[at + 3].toLong() and 0xffL)
}

private fun ByteArray.uint64(at: Int): Long {
    if (at < 0 || at + 8 > size) throw DashStreamException("TIDAL DASH segment had a truncated large MP4 box")
    var value = 0L
    for (index in 0 until 8) {
        value = (value shl 8) or (this[at + index].toLong() and 0xffL)
    }
    return value
}

package moe.rukamori.archivetune.shared.data.stream.hls

import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.data.bytesource.readUpTo
import moe.rukamori.archivetune.shared.data.stream.NativeCiphertextOpener
import moe.rukamori.archivetune.shared.data.stream.aes.Aes128

/** The playlist document and the AES-128 key documents are small; anything larger is refused, not truncated. */
private const val MAX_PLAYLIST_BYTES = 1 shl 20
private const val MAX_KEY_BYTES = 64
private const val AES_KEY_BYTES = 16
private const val SNIFF_BYTES = 16

/** A structural or availability refusal inside one segmented stream. Preparation maps it to a refusal; a read maps it
 * to an I/O failure, so the engine never mistakes a broken stream for a clean end. */
internal class HlsSegmentException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * A plaintext view over one non-DRM HLS media playlist, indexed lazily as the reader moves through it, like the TIDAL
 * DASH view it mirrors.
 *
 * The view is the byte concatenation of the init sections and the media segments in playlist order. Each segment is
 * opened through the host's [NativeCiphertextOpener] only when a read reaches it, so nothing is staged and no whole
 * segment is held in memory. An `#EXT-X-BYTERANGE` segment reads its sub-range of the resource. An AES-128 segment is
 * decrypted by [HlsAes128SegmentSource] with the playlist's explicit IV or the media-sequence IV the specification
 * mandates; its key document is fetched once per key tag through [NativeCiphertextOpener.openWhole], under the same
 * lease-origin policy as the segments. An index entry commits only after its plaintext length is known, so a retried
 * read after a transient failure never duplicates a payload and never assumes a length.
 */
internal class HlsSegmentedSource(
    override val lease: ByteSourceLease,
    private val playlistUrl: String,
    private val playlistText: String?,
    private val accountScope: String,
    private val opener: NativeCiphertextOpener,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : RandomAccessByteSource {
    private val lock = Mutex()
    private val pieces = ArrayList<Piece>()
    private var indexedEnd = 0L
    private var nextSegment = 0
    private var playlist: HlsMediaPlaylist? = null
    private var emittedInitIndex = -1
    private val keys = HashMap<Int, Aes128>()
    private val rangeContinuations = HashMap<String, Long>()
    // The origins the parsed playlist approves, set once it parses; an open outside this set is refused.
    private var resourceOrigins: Set<StreamOrigin>? = null
    // One opened source per resource URI, shared by every byte range of that resource.
    private val resourceSources = HashMap<String, RandomAccessByteSource>()

    /** The container the first indexed payload bytes advertise, or null until the first segment is indexed. */
    var sniffedMimeType: String? = null
        private set

    override suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        // Signed segment URLs stop working at the lease expiry, so a later read fails and the host re-prepares.
        lease.expiresAtEpochMs?.let { expiry ->
            if (nowEpochMs() >= expiry) {
                throw ByteSourceException("the stream authorization has expired; prepare the audio again")
            }
        }
        if (position < 0L) throw ByteSourceException("the read position is before the start of the stream")
        if (length == 0) return 0
        return lock.withLock {
            try {
                ensurePlaylist()
                while (indexedEnd <= position && nextSegment < segments().size) indexNextSegment()
            } catch (refusal: HlsSegmentException) {
                throw ByteSourceException(refusal.message ?: "the segmented stream is invalid", refusal)
            } catch (refusal: HlsPlaylistException) {
                throw ByteSourceException(refusal.message ?: "the segmented playlist is invalid", refusal)
            }
            if (position >= indexedEnd) return@withLock RandomAccessByteSource.END_OF_STREAM
            val piece = pieceAt(position)
            val within = position - piece.start
            val wanted = minOf(length.toLong(), piece.extent.length - within).toInt()
            val read =
                piece.extent.source.readAt(piece.extent.sourceOffset + within, buffer, offset, wanted)
            if (read == RandomAccessByteSource.END_OF_STREAM) {
                throw ByteSourceException("a segment ended before its declared payload")
            }
            read
        }
    }

    /**
     * Parses the playlist and indexes its first segment, so a structural refusal surfaces during preparation rather
     * than in the middle of playback, and the container sniff has real bytes to classify.
     */
    suspend fun indexOpeningSegments() {
        lock.withLock {
            ensurePlaylist()
            while (nextSegment < minOf(OPENING_SEGMENTS, segments().size)) indexNextSegment()
            if (sniffedMimeType == null && pieces.isNotEmpty()) sniffedMimeType = sniffFirstPiece()
        }
    }

    private fun segments(): List<HlsSegment> = playlist?.segments ?: emptyList()

    private suspend fun ensurePlaylist() {
        if (playlist != null) return
        val text =
            playlistText ?: run {
                val bytes =
                    opener.openWhole(playlistUrl, accountScope, MAX_PLAYLIST_BYTES)
                        ?: throw HlsSegmentException("the HLS playlist is unavailable")
                bytes.decodeToString()
            }
        val parsed = HlsMediaPlaylistParser.parse(playlistUrl, text)
        // The one approved origin set, bounded like the preparation path, gates every resource open below.
        resourceOrigins = hlsPlaylistOrigins(playlistUrl, parsed)
        playlist = parsed
    }

    /** Appends the next segment's extents in one step: a failure leaves the index as it was before the segment. */
    private suspend fun indexNextSegment() {
        val index = nextSegment
        val segment = playlist!!.segments[index]
        val extents = ArrayList<Extent>(2)
        // The init-map state and the byte-range continuation commit only after the whole entry indexes, so a
        // failed or cancelled open leaves the index exactly as it was and a retried read reopens this segment.
        var pendingInitIndex = emittedInitIndex
        val pendingContinuations = HashMap(rangeContinuations)
        if (segment.initIndex != pendingInitIndex) {
            pendingInitIndex = segment.initIndex
            if (segment.initIndex >= 0) extents.add(initExtent(segment.initIndex, pendingContinuations))
        }
        extents.add(segmentExtent(index, segment, pendingContinuations))
        var end = indexedEnd
        for (extent in extents) {
            if (extent.length == 0L) continue
            pieces.add(Piece(end, extent))
            end += extent.length
        }
        indexedEnd = end
        emittedInitIndex = pendingInitIndex
        rangeContinuations.clear()
        rangeContinuations.putAll(pendingContinuations)
        nextSegment = index + 1
    }

    /** The init section of an `#EXT-X-MAP`, as a whole resource, a declared sub-range, or its AES-128 plaintext. */
    private suspend fun initExtent(
        initIndex: Int,
        continuations: MutableMap<String, Long>,
    ): Extent {
        val map = playlist!!.initMaps[initIndex]
        val source = openResource(map.uri)
        if (map.keyIndex < 0) {
            return rangedExtent(
                source,
                map.byteRange,
                map.uri,
                continuations,
                "the HLS init section did not state its length",
            )
        }
        val tag =
            playlist!!.keys.getOrNull(map.keyIndex)
                ?: throw HlsSegmentException("the HLS init section names a key tag that does not exist")
        // The parser already required the explicit IV, because an init section has no media sequence to derive one.
        val iv = tag.iv ?: throw HlsSegmentException("an encrypted HLS init section needs an explicit IV")
        val cipher = rangedSource(source, map.byteRange, map.uri, continuations)
        val cipherLength =
            map.byteRange?.length
                ?: cipher.lease.lengthBytes
                ?: throw HlsSegmentException("the HLS init section did not state its length")
        if (cipherLength <= 0L || cipherLength % AES_BLOCK_BYTES != 0L) {
            throw HlsSegmentException("the HLS init section is not block-aligned for AES-128")
        }
        val plaintext = HlsAes128SegmentSource(cipher, keyFor(map.keyIndex, tag), iv.copyOf())
        val length =
            plaintext.plaintextLength()
                ?: throw HlsSegmentException("the HLS init section did not reveal its plaintext length")
        return Extent(length = length, source = plaintext, sourceOffset = 0L)
    }

    /** The plaintext extent of one media segment: a clear range or the AES-128-decrypted view of its cipher range. */
    private suspend fun segmentExtent(
        index: Int,
        segment: HlsSegment,
        continuations: MutableMap<String, Long>,
    ): Extent {
        val source = openResource(segment.uri)
        if (segment.keyIndex < 0) {
            return rangedExtent(
                source,
                segment.byteRange,
                segment.uri,
                continuations,
                "HLS segment ${index + 1} did not state its length",
            )
        }
        val cipherLength =
            segment.byteRange?.length
                ?: source.lease.lengthBytes
                ?: throw HlsSegmentException("HLS segment ${index + 1} did not state its length")
        if (cipherLength <= 0L || cipherLength % AES_BLOCK_BYTES != 0L) {
            throw HlsSegmentException("HLS segment ${index + 1} is not block-aligned for AES-128")
        }
        val tag = playlist!!.keys.getOrNull(segment.keyIndex)
            ?: throw HlsSegmentException("HLS segment ${index + 1} names a key tag that does not exist")
        val cipher = rangedSource(source, segment.byteRange, segment.uri, continuations)
        val plaintext =
            HlsAes128SegmentSource(cipher, keyFor(segment.keyIndex, tag), ivFor(tag, segment))
        // The plaintext ends one padding block short of the ciphertext; the index commits only once that is known.
        val length =
            plaintext.plaintextLength()
                ?: throw HlsSegmentException("HLS segment ${index + 1} did not reveal its plaintext length")
        return Extent(length = length, source = plaintext, sourceOffset = 0L)
    }

    /**
     * The source of one resource URI, opened once and shared by every byte range that names it, so consecutive
     * `EXT-X-BYTERANGE` segments of one file reuse a single lease rather than holding a connection each. A URI
     * outside the parsed playlist's approved origin set is refused before any request leaves.
     */
    private suspend fun openResource(uri: String): RandomAccessByteSource {
        resourceSources[uri]?.let { return it }
        val origin =
            StreamOrigin.parse(uri) ?: throw HlsSegmentException("an HLS resource is not an HTTP(S) URL")
        val approved = resourceOrigins
        if (approved != null && origin !in approved) {
            throw HlsSegmentException("an HLS resource origin is outside the playlist's approved set")
        }
        val source =
            opener.open(uri, accountScope) ?: throw HlsSegmentException("an HLS resource is unavailable")
        resourceSources[uri] = source
        return source
    }

    /** Wraps [source] in the declared byte range, or returns it unchanged when the segment uses the whole resource. */
    private fun rangedSource(
        source: RandomAccessByteSource,
        range: HlsByteRange?,
        uri: String,
        continuations: MutableMap<String, Long>,
    ): RandomAccessByteSource {
        range ?: return source
        // A range without an offset continues the previous byte range of the same resource, per RFC 8216.
        val offset = range.offset ?: (continuations[uri] ?: 0L)
        continuations[uri] = offset + range.length
        return SubRangeSource(source, offset, range.length)
    }

    /** A range-aware extent: [rangedSource] plus the length check a clear extent needs. */
    private fun rangedExtent(
        source: RandomAccessByteSource,
        range: HlsByteRange?,
        uri: String,
        continuations: MutableMap<String, Long>,
        noLengthMessage: String,
    ): Extent {
        val ranged = rangedSource(source, range, uri, continuations)
        val length = range?.length ?: ranged.lease.lengthBytes ?: throw HlsSegmentException(noLengthMessage)
        return Extent(length = length, source = ranged, sourceOffset = 0L)
    }

    /** The AES-128 cipher of one key tag, fetched once and cached; the key bytes never leave this object. */
    private suspend fun keyFor(
        keyIndex: Int,
        tag: HlsKeyTag,
    ): Aes128 =
        keys[keyIndex] ?: run {
            // The parser resolved every key URI, so the tag already names an absolute HTTP(S) resource; the
            // approved-set check applies to keys exactly as it does to segments and init sections.
            val origin =
                StreamOrigin.parse(tag.keyUri)
                    ?: throw HlsSegmentException("an HLS key is not an HTTP(S) resource")
            val approved = resourceOrigins
            if (approved != null && origin !in approved) {
                throw HlsSegmentException("an HLS key origin is outside the playlist's approved set")
            }
            val bytes =
                opener.openWhole(tag.keyUri, accountScope, MAX_KEY_BYTES)
                    ?: throw HlsSegmentException("an HLS key is unavailable")
            if (bytes.size != AES_KEY_BYTES) {
                throw HlsSegmentException("an HLS AES-128 key is not 16 bytes")
            }
            Aes128(bytes).also { keys[keyIndex] = it }
        }

    private fun ivFor(
        tag: HlsKeyTag,
        segment: HlsSegment,
    ): ByteArray = HlsAes128.initializationVector(tag.iv, segment.mediaSequence)

    /** Reads the first piece's leading bytes and classifies the container the plaintext actually carries. */
    private suspend fun sniffFirstPiece(): String? {
        val extent = pieces.firstOrNull()?.extent ?: return null
        val head = extent.source.readUpTo(extent.sourceOffset, SNIFF_BYTES).bytes
        return sniffContainer(head)
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

    private class Extent(
        val length: Long,
        val source: RandomAccessByteSource,
        val sourceOffset: Long,
    )

    private class Piece(
        val start: Long,
        val extent: Extent,
    )

    private companion object {
        const val AES_BLOCK_BYTES = 16L
        const val OPENING_SEGMENTS = 1
    }
}

/** A fixed window of a larger source, so a byte-ranged segment reads its declared sub-range only. */
private class SubRangeSource(
    private val delegate: RandomAccessByteSource,
    private val start: Long,
    private val span: Long,
) : RandomAccessByteSource {
    override val lease = delegate.lease.copy(lengthBytes = span)

    override suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (position < 0L || position >= span) return RandomAccessByteSource.END_OF_STREAM
        val wanted = minOf(length.toLong(), span - position).toInt()
        return delegate.readAt(start + position, buffer, offset, wanted)
    }
}

/** Classifies the container of a stream head: fragmented MP4, MPEG-TS, FLAC, Ogg or MP3, else unknown. */
internal fun sniffContainer(head: ByteArray): String? =
    when {
        head.size >= 8 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte() -> "audio/mp4"
        head.isNotEmpty() && head[0].toInt() == 0x47 -> "video/mp2t"
        head.size >= 4 && head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
            head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte() -> "audio/flac"
        head.size >= 4 && head[0] == 'O'.code.toByte() && head[1] == 'g'.code.toByte() &&
            head[2] == 'g'.code.toByte() && head[3] == 'S'.code.toByte() -> "audio/ogg"
        head.size >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() &&
            head[2] == '3'.code.toByte() -> "audio/mpeg"
        head.size >= 2 && (head[0].toInt() and 0xff) == 0xff && (head[1].toInt() and 0xe0) == 0xe0 -> "audio/mpeg"
        else -> null
    }

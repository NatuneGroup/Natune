package moe.rukamori.archivetune.shared.domain.mediainfo

/**
 * Reads the technical facts a media header states: container, codec, rate, channels, depth, bitrate and duration.
 *
 * The parser is pure and never throws. Its bytes come from storage or the network, and an unreadable header must
 * degrade to unknown fields rather than fail a details screen. Bytes past the end of the supplied prefix are treated
 * as unknown, never guessed.
 */
object MediaHeaderParser {
    /**
     * Parses [header], the beginning of a file or stream of any length, into its technical facts. Returns null when the
     * bytes match no known container. [totalBytes] is the complete file length when it is known. It is used only to
     * derive an average bitrate for containers whose header states none.
     */
    fun parse(
        header: ByteArray,
        totalBytes: Long?,
    ): MediaTechnicalInfo? {
        val container = AudioContainer.detect(header) ?: return null
        val facts =
            when (container) {
                AudioContainer.FLAC -> flacFacts(header)
                AudioContainer.WAV -> wavFacts(header)
                AudioContainer.MP3 -> mp3Facts(header)
                AudioContainer.OGG -> oggFacts(header)
                AudioContainer.MP4 -> Mp4AudioInspector.inspect(header, skipId3Tag(header))
                // Matroska/WebM codec tables are not read here, so the container is stated and the rest is not.
                AudioContainer.WEBM -> MediaFacts()
            }
        return MediaTechnicalInfo(
            container = container,
            codec = facts.codec,
            sampleRate = facts.sampleRate,
            channels = facts.channels,
            bitDepth = facts.bitDepth,
            bitrateBitsPerSecond = facts.bitrateBitsPerSecond ?: averageBitrate(totalBytes, facts.durationMs),
            durationMs = facts.durationMs,
        )
    }

    /** The average rate of the whole file, in bits per second, or null when the length or duration is unknown. */
    private fun averageBitrate(
        totalBytes: Long?,
        durationMs: Long?,
    ): Long? {
        if (totalBytes == null || durationMs == null) return null
        if (totalBytes <= 0L || durationMs <= 0L) return null
        return totalBytes * BITS_PER_BYTE * MILLIS_PER_SECOND / durationMs
    }

    /** The offset of the first byte after a leading ID3v2 tag, or 0 when the bytes do not start with one. */
    private fun skipId3Tag(bytes: ByteArray): Int {
        if (bytes.size < ID3_HEADER_BYTES || !bytes.matchesAscii(0, "ID3")) return 0
        val footer = if ((bytes.u8(ID3_FLAGS_OFFSET) and ID3_FOOTER_FLAG) != 0) ID3_HEADER_BYTES else 0
        return ID3_HEADER_BYTES + bytes.synchsafeInt(ID3_SIZE_OFFSET) + footer
    }

    /**
     * FLAC: the magic is followed by a metadata block header, and the first block must be STREAMINFO. Its packed 64-bit
     * field holds the sample rate, channel count, bit depth and total sample count, and gives the duration exactly.
     */
    private fun flacFacts(bytes: ByteArray): MediaFacts {
        if (bytes.size < FLAC_REQUIRED_BYTES) return MediaFacts(codec = MediaCodec.FLAC)
        val blockType = bytes.u8(FLAC_MAGIC_BYTES) and 0x7F
        val blockLength =
            (bytes.u8(FLAC_MAGIC_BYTES + 1) shl 16) or
                (bytes.u8(FLAC_MAGIC_BYTES + 2) shl 8) or
                bytes.u8(FLAC_MAGIC_BYTES + 3)
        if (blockType != FLAC_STREAMINFO_TYPE || blockLength != FLAC_STREAMINFO_BYTES) {
            return MediaFacts(codec = MediaCodec.FLAC)
        }
        val packed = bytes.beU64(FLAC_PACKED_OFFSET)
        // A zero sample rate is illegal in FLAC and means the field was misread, so the rate is left unstated.
        val sampleRate = ((packed ushr 44) and 0xFFFFFL).toInt()
        if (sampleRate <= 0) return MediaFacts(codec = MediaCodec.FLAC)
        val channels = ((packed ushr 41) and 0x7L).toInt() + 1
        val bitDepth = ((packed ushr 36) and 0x1FL).toInt() + 1
        val totalSamples = packed and 0xF_FFFF_FFFFL
        return MediaFacts(
            codec = MediaCodec.FLAC,
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
            durationMs = if (totalSamples > 0L) totalSamples * MILLIS_PER_SECOND / sampleRate else null,
        )
    }

    /**
     * WAV: a RIFF chunk walk. The `fmt ` chunk states the format tag, channels, rate, byte rate and sample width, and
     * the `data` chunk states the payload size, which together give the duration. Streamed files that declare a zero or
     * all-ones data size leave the duration unknown.
     */
    private fun wavFacts(bytes: ByteArray): MediaFacts {
        var format: WavFormat? = null
        var dataChunkBytes: Long? = null
        var offset = WAV_FIRST_CHUNK
        while (offset + WAV_CHUNK_HEADER_BYTES <= bytes.size) {
            val chunkBytes = bytes.leU32(offset + 4)
            val body = offset + WAV_CHUNK_HEADER_BYTES
            if (bytes.matchesAscii(offset, "fmt ")) format = readWavFormat(bytes, body, chunkBytes)
            if (bytes.matchesAscii(offset, "data")) {
                dataChunkBytes = chunkBytes
                break
            }
            // RIFF chunks are padded to an even length.
            val next = body + chunkBytes + (chunkBytes and 1L)
            if (next > bytes.size) break
            offset = next.toInt()
        }
        val fmt = format ?: return MediaFacts()
        val codec =
            when (fmt.tag) {
                WAVE_FORMAT_PCM -> MediaCodec.PCM
                WAVE_FORMAT_IEEE_FLOAT -> MediaCodec.PCM_FLOAT
                else -> MediaCodec.UNKNOWN
            }
        val uncompressed = codec == MediaCodec.PCM || codec == MediaCodec.PCM_FLOAT
        val payloadBytes = dataChunkBytes?.takeIf { it > 0L && it != UNKNOWN_CHUNK_SIZE }
        return MediaFacts(
            codec = codec,
            sampleRate = fmt.sampleRate.asRate(),
            channels = fmt.channels.takeIf { it > 0 },
            // A bit depth describes sample width only for uncompressed payloads; a compressed WAV leaves it unstated.
            bitDepth = fmt.bitsPerSample.takeIf { uncompressed && it > 0 },
            bitrateBitsPerSecond = fmt.byteRate.takeIf { it > 0L }?.times(BITS_PER_BYTE),
            durationMs =
                payloadBytes?.let { size ->
                    if (fmt.byteRate > 0L) size * MILLIS_PER_SECOND / fmt.byteRate else null
                },
        )
    }

    /** Reads the `fmt ` body at [body]. Extensible formats name the real codec in their sub-format GUID. */
    private fun readWavFormat(
        bytes: ByteArray,
        body: Int,
        chunkBytes: Long,
    ): WavFormat? {
        if (body + WAV_FMT_MIN_BYTES > bytes.size) return null
        var tag = bytes.leU16(body)
        if (tag == WAVE_FORMAT_EXTENSIBLE) {
            if (chunkBytes < WAV_EXTENSIBLE_FMT_BYTES || body + WAV_EXTENSIBLE_FMT_BYTES > bytes.size) return null
            tag = bytes.leU16(body + WAV_SUBFORMAT_OFFSET)
        }
        return WavFormat(
            tag = tag,
            channels = bytes.leU16(body + 2),
            sampleRate = bytes.leU32(body + 4),
            byteRate = bytes.leU32(body + 8),
            bitsPerSample = bytes.leU16(body + 14),
        )
    }

    /**
     * MPEG audio: the first valid frame header states the version, layer, bitrate, rate and channel mode. A Xing or Info
     * tag inside that first frame gives the frame count, which gives the exact duration. With that duration known, the
     * average rate is derived from the file length, because a variable-bitrate frame states only its own rate.
     */
    private fun mp3Facts(bytes: ByteArray): MediaFacts {
        val frame = findMp3Frame(bytes, skipId3Tag(bytes)) ?: return MediaFacts()
        if (!frame.layerIII) return MediaFacts()
        val samplesPerFrame = if (frame.mpeg1) MPEG1_LAYER3_SAMPLES else MPEG2_LAYER3_SAMPLES
        val sideInfoBytes =
            when {
                frame.mpeg1 && frame.mono -> 17
                frame.mpeg1 -> 32
                frame.mono -> 9
                else -> 17
            }
        val xing = frame.offset + FRAME_HEADER_BYTES + (if (frame.hasCrc) CRC_BYTES else 0) + sideInfoBytes
        val frames =
            if (bytes.matchesAscii(xing, "Xing") || bytes.matchesAscii(xing, "Info")) xingFrameCount(bytes, xing) else null
        val durationMs = frames?.let { count -> count * samplesPerFrame * MILLIS_PER_SECOND / frame.sampleRate }
        return MediaFacts(
            codec = MediaCodec.MP3,
            sampleRate = frame.sampleRate,
            channels = if (frame.mono) 1 else 2,
            bitrateBitsPerSecond = if (durationMs == null) frame.bitrateKbps * MILLIS_PER_SECOND else null,
            durationMs = durationMs,
        )
    }

    /** The frame count from a Xing/Info tag at [offset], or null when the tag does not carry one. */
    private fun xingFrameCount(
        bytes: ByteArray,
        offset: Int,
    ): Long? {
        if (offset + XING_MIN_BYTES > bytes.size) return null
        val flags = bytes.beU32(offset + 4)
        if ((flags and XING_FRAMES_FLAG) == 0L) return null
        return bytes.beU32(offset + 8).takeIf { it > 0L }
    }

    /** Scans a bounded window from [start] for the first frame header that parses as a valid MPEG audio frame. */
    private fun findMp3Frame(
        bytes: ByteArray,
        start: Int,
    ): Mp3Frame? {
        val last = minOf(bytes.size - FRAME_HEADER_BYTES, start + MP3_SCAN_BYTES)
        for (offset in start..last) {
            val frame = readMp3Frame(bytes, offset)
            if (frame != null) return frame
        }
        return null
    }

    /**
     * OGG: the first packet follows the page header and its segment table. The codec names itself in that packet, so a
     * Vorbis identification header or an OpusHead decides the codec. Any other packet leaves the codec unknown.
     */
    private fun oggFacts(bytes: ByteArray): MediaFacts {
        if (bytes.size < OGG_PAGE_HEADER_BYTES) return MediaFacts()
        val packet = OGG_PAGE_HEADER_BYTES + bytes.u8(OGG_SEGMENT_COUNT_OFFSET)
        if (packet + OGG_MAGIC_BYTES > bytes.size) return MediaFacts()
        if (bytes.u8(packet) == VORBIS_PACKET_TYPE && bytes.matchesAscii(packet + 1, "vorbis")) {
            if (packet + VORBIS_ID_BYTES > bytes.size || bytes.leU32(packet + 7) != 0L) {
                return MediaFacts(codec = MediaCodec.VORBIS)
            }
            return MediaFacts(
                codec = MediaCodec.VORBIS,
                sampleRate = bytes.leU32(packet + 12).asRate(),
                channels = bytes.u8(packet + 11).takeIf { it > 0 },
                // The nominal bitrate is zero when the encoder left it unset, which is then unstated.
                bitrateBitsPerSecond = bytes.leU32(packet + 20).takeIf { it > 0L },
            )
        }
        if (bytes.matchesAscii(packet, "OpusHead") && packet + OPUS_HEAD_BYTES <= bytes.size) {
            // Opus always decodes at 48 kHz whatever rate the original input had, so the decoded rate is stated here.
            return MediaFacts(
                codec = MediaCodec.OPUS,
                sampleRate = OPUS_DECODE_RATE,
                channels = bytes.u8(packet + 9).takeIf { it > 0 },
            )
        }
        return MediaFacts()
    }

    private const val BITS_PER_BYTE = 8L
    private const val ID3_HEADER_BYTES = 10
    private const val ID3_FLAGS_OFFSET = 5
    private const val ID3_SIZE_OFFSET = 6
    private const val ID3_FOOTER_FLAG = 0x10
    private const val FLAC_MAGIC_BYTES = 4
    private const val FLAC_PACKED_OFFSET = 18
    private const val FLAC_REQUIRED_BYTES = 42
    private const val FLAC_STREAMINFO_TYPE = 0
    private const val FLAC_STREAMINFO_BYTES = 34
    private const val WAV_FIRST_CHUNK = 12
    private const val WAV_CHUNK_HEADER_BYTES = 8
    private const val WAV_FMT_MIN_BYTES = 16
    private const val WAV_EXTENSIBLE_FMT_BYTES = 40
    private const val WAV_SUBFORMAT_OFFSET = 24
    private const val WAVE_FORMAT_PCM = 1
    private const val WAVE_FORMAT_IEEE_FLOAT = 3
    private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    private const val UNKNOWN_CHUNK_SIZE = 0xFFFF_FFFFL
    private const val FRAME_HEADER_BYTES = 4
    private const val CRC_BYTES = 2
    private const val MP3_SCAN_BYTES = 8192
    private const val MPEG1_LAYER3_SAMPLES = 1152
    private const val MPEG2_LAYER3_SAMPLES = 576
    private const val XING_MIN_BYTES = 12
    private const val XING_FRAMES_FLAG = 1L
    private const val OGG_PAGE_HEADER_BYTES = 27
    private const val OGG_SEGMENT_COUNT_OFFSET = 26
    private const val OGG_MAGIC_BYTES = 8
    private const val VORBIS_PACKET_TYPE = 1
    private const val VORBIS_ID_BYTES = 30
    private const val OPUS_HEAD_BYTES = 19
    private const val OPUS_DECODE_RATE = 48_000
}

/** What one container's header states. Every field stays null when the header does not state it. */
internal class MediaFacts(
    val codec: MediaCodec = MediaCodec.UNKNOWN,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val bitDepth: Int? = null,
    val bitrateBitsPerSecond: Long? = null,
    val durationMs: Long? = null,
)

private class WavFormat(
    val tag: Int,
    val channels: Int,
    val sampleRate: Long,
    val byteRate: Long,
    val bitsPerSample: Int,
)

private class Mp3Frame(
    val offset: Int,
    val mpeg1: Boolean,
    val layerIII: Boolean,
    val mono: Boolean,
    val hasCrc: Boolean,
    val bitrateKbps: Int,
    val sampleRate: Int,
)

private val MPEG1_LAYER3_KBPS = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
private val MPEG2_LAYER3_KBPS = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
private val MPEG1_RATES = intArrayOf(44_100, 48_000, 32_000)
private val MPEG2_RATES = intArrayOf(22_050, 24_000, 16_000)
private val MPEG25_RATES = intArrayOf(11_025, 12_000, 8_000)

/**
 * Decodes one MPEG audio frame header at [offset]. Index 0 (free format) and index 15 (invalid) are rejected, as is
 * the reserved sample-rate index, so a false sync pattern inside other data is not accepted as a frame.
 */
private fun readMp3Frame(
    bytes: ByteArray,
    offset: Int,
): Mp3Frame? {
    if (offset < 0 || offset + FRAME_HEADER_BYTES > bytes.size) return null
    val b1 = bytes.u8(offset + 1)
    val b2 = bytes.u8(offset + 2)
    val b3 = bytes.u8(offset + 3)
    if (bytes.u8(offset) != 0xFF || (b1 and 0xE0) != 0xE0) return null
    val version = (b1 shr 3) and 0x3
    val layer = (b1 shr 1) and 0x3
    val bitrateIndex = (b2 shr 4) and 0xF
    val rateIndex = (b2 shr 2) and 0x3
    if (version == 1 || layer == 0 || bitrateIndex == 0 || bitrateIndex == 0xF || rateIndex == 3) return null
    val mpeg1 = version == 3
    val layerIII = layer == 1
    val rates =
        when (version) {
            3 -> MPEG1_RATES
            2 -> MPEG2_RATES
            else -> MPEG25_RATES
        }
    val kbpsTable = if (mpeg1) MPEG1_LAYER3_KBPS else MPEG2_LAYER3_KBPS
    return Mp3Frame(
        offset = offset,
        mpeg1 = mpeg1,
        layerIII = layerIII,
        mono = ((b3 shr 6) and 0x3) == 3,
        // The protection bit is clear when a 16-bit CRC follows the header.
        hasCrc = (b1 and 0x01) == 0,
        bitrateKbps = if (layerIII) kbpsTable[bitrateIndex] else 0,
        sampleRate = rates[rateIndex],
    )
}

package moe.rukamori.archivetune.shared.domain.mediainfo

/**
 * Reads the first audio track of an ISO base-media (MP4/M4A) header. The sample entry names the codec, rate and
 * channels. The `esds` descriptor states an AAC or MP3 average bitrate, and `mvhd` states the duration. The inspection
 * sees only the bytes it is given, so a `moov` box stored after the media data leaves the codec unknown rather than
 * guessed.
 */
internal object Mp4AudioInspector {
    /** Inspects the top-level boxes that begin at [start], which lies after any leading ID3 tag. */
    fun inspect(
        bytes: ByteArray,
        start: Int,
    ): MediaFacts {
        val moov = bytes.findBoxes(Mp4Span(start, bytes.size), "moov").firstOrNull() ?: return MediaFacts()
        val durationMs = bytes.movieDurationMs(moov)
        for (trak in bytes.findBoxes(moov, "trak")) {
            val entry = bytes.audioSampleEntry(trak) ?: continue
            return bytes.describeSampleEntry(entry, durationMs)
        }
        return MediaFacts(durationMs = durationMs)
    }

    /**
     * The direct child boxes of [within] whose four-character type is [type]. The walk stops at the first box that is
     * truncated or malformed, so a partial header yields only the boxes it fully contains.
     */
    private fun ByteArray.findBoxes(
        within: Mp4Span,
        type: String,
    ): List<Mp4Span> {
        val found = mutableListOf<Mp4Span>()
        var offset = within.start
        while (offset + BOX_HEADER_BYTES <= within.end) {
            var size = beU32(offset)
            var headerBytes = BOX_HEADER_BYTES
            if (size == 1L) {
                if (offset + LARGE_BOX_HEADER_BYTES > within.end) break
                size = beU64(offset + BOX_HEADER_BYTES)
                headerBytes = LARGE_BOX_HEADER_BYTES
            } else if (size == 0L) {
                // A zero size means the box runs to the end of its container.
                size = (within.end - offset).toLong()
            }
            if (size < headerBytes || offset + size > within.end) break
            if (matchesAscii(offset + BOX_TYPE_OFFSET, type)) {
                found += Mp4Span(offset + headerBytes, offset + size.toInt())
            }
            offset += size.toInt()
        }
        return found
    }

    /** The duration in milliseconds from `mvhd`, which holds the movie timescale and duration. */
    private fun ByteArray.movieDurationMs(moov: Mp4Span): Long? {
        val mvhd = findBoxes(moov, "mvhd").firstOrNull() ?: return null
        if (mvhd.start + FULL_BOX_HEADER_BYTES > mvhd.end) return null
        val timescale: Long
        val duration: Long
        if (u8(mvhd.start) == 1) {
            if (mvhd.start + MVHD_V1_BYTES > mvhd.end) return null
            timescale = beU32(mvhd.start + MVHD_V1_TIMESCALE_OFFSET)
            duration = beU64(mvhd.start + MVHD_V1_DURATION_OFFSET)
        } else {
            if (mvhd.start + MVHD_V0_BYTES > mvhd.end) return null
            timescale = beU32(mvhd.start + MVHD_V0_TIMESCALE_OFFSET)
            duration = beU32(mvhd.start + MVHD_V0_DURATION_OFFSET)
        }
        if (timescale <= 0L || duration <= 0L) return null
        return duration * MILLIS_PER_SECOND / timescale
    }

    /**
     * The first sample entry of the first sound track inside [trak], as a span that starts at the entry's size field.
     * Video, text and hint tracks are skipped by their handler type.
     */
    private fun ByteArray.audioSampleEntry(trak: Mp4Span): Mp4Span? {
        val mdia = findBoxes(trak, "mdia").firstOrNull() ?: return null
        val handler = findBoxes(mdia, "hdlr").firstOrNull() ?: return null
        if (handler.start + HANDLER_TYPE_OFFSET + HANDLER_TYPE_BYTES > handler.end) return null
        if (!matchesAscii(handler.start + HANDLER_TYPE_OFFSET, "soun")) return null
        val minf = findBoxes(mdia, "minf").firstOrNull() ?: return null
        val stbl = findBoxes(minf, "stbl").firstOrNull() ?: return null
        val stsd = findBoxes(stbl, "stsd").firstOrNull() ?: return null
        // The sample description payload is version/flags and entry_count, then the first entry.
        val entryStart = stsd.start + SAMPLE_DESCRIPTION_HEADER_BYTES
        if (entryStart + BOX_HEADER_BYTES > stsd.end) return null
        val entryBytes = beU32(entryStart)
        if (entryBytes < SAMPLE_ENTRY_BYTES || entryStart + entryBytes > stsd.end) return null
        return Mp4Span(entryStart, entryStart + entryBytes.toInt())
    }

    /** Maps an audio sample entry to its codec and the facts the entry states. */
    private fun ByteArray.describeSampleEntry(
        entry: Mp4Span,
        durationMs: Long?,
    ): MediaFacts {
        // The AudioSampleEntry is laid out from the box start: the type at 4, channelcount at 24, samplesize at 26,
        // and a 16.16 fixed-point samplerate at 32, whose integer part is the upper 16 bits.
        val channels = beU16(entry.start + ENTRY_CHANNELS_OFFSET).takeIf { it > 0 }
        val sampleSize = beU16(entry.start + ENTRY_SAMPLE_SIZE_OFFSET).takeIf { it > 0 }
        val sampleRate = (beU32(entry.start + ENTRY_SAMPLE_RATE_OFFSET) ushr 16).asRate()
        return when {
            matchesAscii(entry.start + BOX_TYPE_OFFSET, "mp4a") -> {
                val esdsBox = findBoxes(Mp4Span(entry.start + SAMPLE_ENTRY_BYTES, entry.end), "esds").firstOrNull()
                val esds = esdsBox?.let { parseEsds(it) }
                val mp3 =
                    esds != null &&
                        (esds.objectType == MPEG_AUDIO_OBJECT_MPEG2 || esds.objectType == MPEG_AUDIO_OBJECT_MPEG1)
                MediaFacts(
                    codec = if (mp3) MediaCodec.MP3 else MediaCodec.AAC,
                    sampleRate = sampleRate,
                    channels = channels,
                    bitrateBitsPerSecond = esds?.avgBitrate?.takeIf { it > 0L },
                    durationMs = durationMs,
                )
            }
            matchesAscii(entry.start + BOX_TYPE_OFFSET, "alac") ->
                MediaFacts(
                    codec = MediaCodec.ALAC,
                    sampleRate = sampleRate,
                    channels = channels,
                    bitDepth = sampleSize,
                    durationMs = durationMs,
                )
            matchesAscii(entry.start + BOX_TYPE_OFFSET, "fLaC") ->
                MediaFacts(
                    codec = MediaCodec.FLAC,
                    sampleRate = sampleRate,
                    channels = channels,
                    bitDepth = sampleSize,
                    durationMs = durationMs,
                )
            matchesAscii(entry.start + BOX_TYPE_OFFSET, "Opus") ->
                MediaFacts(codec = MediaCodec.OPUS, sampleRate = sampleRate, channels = channels, durationMs = durationMs)
            else -> MediaFacts(sampleRate = sampleRate, channels = channels, durationMs = durationMs)
        }
    }

    /** The object type and average bitrate from an `esds` box, or null when its descriptors are malformed. */
    private fun ByteArray.parseEsds(box: Mp4Span): EsdsFacts? {
        // The payload is version/flags, then an ES_Descriptor whose optional fields depend on its flags.
        val es = descriptorBody(box.start + FULL_BOX_HEADER_BYTES, box.end, ES_DESCRIPTOR_TAG) ?: return null
        if (es + ES_ID_AND_FLAGS_BYTES > box.end) return null
        val flags = u8(es + ES_FLAGS_OFFSET)
        var offset = es + ES_ID_AND_FLAGS_BYTES
        if ((flags and ES_DEPENDS_ON_FLAG) != 0) offset += ES_ID_BYTES
        if ((flags and ES_URL_FLAG) != 0) {
            if (offset >= box.end) return null
            offset += 1 + u8(offset)
        }
        if ((flags and ES_OCR_FLAG) != 0) offset += ES_ID_BYTES
        val config = descriptorBody(offset, box.end, DECODER_CONFIG_TAG) ?: return null
        if (config + DECODER_CONFIG_STATED_BYTES > box.end) return null
        return EsdsFacts(
            objectType = u8(config),
            avgBitrate = beU32(config + DECODER_CONFIG_AVG_BITRATE_OFFSET),
        )
    }

    /**
     * The body offset of the MPEG-4 descriptor tagged [tag] at [tagOffset]. The variable-length size is one to four
     * bytes, each carrying seven bits with the high bit set on every byte but the last.
     */
    private fun ByteArray.descriptorBody(
        tagOffset: Int,
        end: Int,
        tag: Int,
    ): Int? {
        if (tagOffset >= end || u8(tagOffset) != tag) return null
        var offset = tagOffset + 1
        repeat(MAX_DESCRIPTOR_SIZE_BYTES) {
            if (offset >= end) return null
            val byte = u8(offset)
            offset++
            if ((byte and 0x80) == 0) return offset
        }
        return null
    }

    private class EsdsFacts(
        val objectType: Int,
        val avgBitrate: Long,
    )
}

/** A box or box payload inside the header bytes. [start] is inclusive and [end] is exclusive. */
private class Mp4Span(
    val start: Int,
    val end: Int,
)

private const val BOX_HEADER_BYTES = 8
private const val LARGE_BOX_HEADER_BYTES = 16
private const val BOX_TYPE_OFFSET = 4
private const val FULL_BOX_HEADER_BYTES = 4
private const val SAMPLE_ENTRY_BYTES = 36
private const val SAMPLE_DESCRIPTION_HEADER_BYTES = 8
private const val HANDLER_TYPE_OFFSET = 8
private const val HANDLER_TYPE_BYTES = 4
private const val ENTRY_CHANNELS_OFFSET = 24
private const val ENTRY_SAMPLE_SIZE_OFFSET = 26
private const val ENTRY_SAMPLE_RATE_OFFSET = 32
private const val MVHD_V0_TIMESCALE_OFFSET = 12
private const val MVHD_V0_DURATION_OFFSET = 16
private const val MVHD_V0_BYTES = 20
private const val MVHD_V1_TIMESCALE_OFFSET = 20
private const val MVHD_V1_DURATION_OFFSET = 24
private const val MVHD_V1_BYTES = 32
private const val ES_DESCRIPTOR_TAG = 0x03
private const val DECODER_CONFIG_TAG = 0x04
private const val MAX_DESCRIPTOR_SIZE_BYTES = 4
private const val ES_ID_BYTES = 2
private const val ES_FLAGS_OFFSET = 2
private const val ES_ID_AND_FLAGS_BYTES = 3
private const val ES_DEPENDS_ON_FLAG = 0x80
private const val ES_URL_FLAG = 0x40
private const val ES_OCR_FLAG = 0x20
private const val DECODER_CONFIG_AVG_BITRATE_OFFSET = 9
private const val DECODER_CONFIG_STATED_BYTES = 13
private const val MPEG_AUDIO_OBJECT_MPEG1 = 0x6B
private const val MPEG_AUDIO_OBJECT_MPEG2 = 0x69

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.mediainfo

import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.domain.mediainfo.MediaHeaderParser
import moe.rukamori.archivetune.shared.domain.mediainfo.MediaTechnicalInfo

/**
 * Inspects the technical facts of an audio byte stream from its bounded header. It reads at most [PREFIX_BYTES], so a
 * remote or Telegram source is never downloaded just to show a details sheet. A source that has no bytes ready stops
 * the read early, and the facts are then taken from what arrived rather than waiting on a stalled connection.
 */
object MediaTechnicalInfoReader {
    /**
     * Covers the FLAC, WAV, MPEG and Ogg headers in full, plus an MP4 `moov` box stored before the media data (the
     * "faststart" layout). A `moov` stored after the media data is not reached, and its codec stays unknown.
     */
    const val PREFIX_BYTES: Int = 256 * 1024

    private const val CHUNK_BYTES: Int = 64 * 1024

    /**
     * Returns the technical facts of [source], or null when the bytes match no known audio container. The file length
     * comes from the source's lease, so the average bitrate can be derived when the header states none.
     */
    suspend fun inspect(source: RandomAccessByteSource): MediaTechnicalInfo? {
        val buffer = ByteArray(PREFIX_BYTES)
        var filled = 0
        while (filled < buffer.size) {
            val wanted = minOf(CHUNK_BYTES, buffer.size - filled)
            val read = source.readAt(filled.toLong(), buffer, filled, wanted)
            // Zero means no bytes are available yet and a negative value marks the end of the stream; either way the
            // facts come from what has arrived so far.
            if (read == RandomAccessByteSource.END_OF_STREAM || read <= 0) break
            filled += read
        }
        return MediaHeaderParser.parse(buffer.copyOf(filled), source.lease.lengthBytes)
    }
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.mediainfo

/**
 * The coded audio format. [PCM] and [PCM_FLOAT] are uncompressed WAV payloads. [UNKNOWN] covers a valid header with
 * an unnamed or unsupported codec, and a container whose codec lies beyond the bytes that were inspected.
 */
enum class MediaCodec { FLAC, PCM, PCM_FLOAT, MP3, VORBIS, OPUS, AAC, ALAC, UNKNOWN }

/**
 * Technical facts stated by a media header. Every value is null when the header does not state it, so the host shows
 * an unknown field as unknown instead of estimating it from a quality tier. [container] comes from the file's own
 * magic bytes, never from its extension or MIME type. [bitrateBitsPerSecond] is the rate the header states. When the
 * header states none, the average rate is derived from the complete byte length and [durationMs], and stays null
 * when either is unknown.
 */
data class MediaTechnicalInfo(
    val container: AudioContainer,
    val codec: MediaCodec,
    val sampleRate: Int?,
    val channels: Int?,
    val bitDepth: Int?,
    val bitrateBitsPerSecond: Long?,
    val durationMs: Long?,
)

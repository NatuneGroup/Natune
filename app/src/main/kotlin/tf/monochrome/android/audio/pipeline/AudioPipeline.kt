/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © 4nx3b — github.com/4nx3b (audio pipeline inspector)
 */

package tf.monochrome.android.audio.pipeline

data class DecodedStream(
    val mimeType: String? = null,
    val sampleRate: Int? = null,
    val channelCount: Int? = null,

    val bitrate: Int? = null,

    val pcmBits: Int? = null,
    val pcmIsFloat: Boolean = false,
)

internal fun bits(value: Int?, isFloat: Boolean = false): String? =
    value?.takeIf { it > 0 }?.let { if (isFloat) "$it-bit float" else "$it-bit" }

internal fun kbps(bitsPerSecond: Int?): String? =
    bitsPerSecond?.takeIf { it > 0 }?.let { "${(it + 500) / 1000} kbps" }

internal fun channels(count: Int?, layout: String?): String? {
    if (count == null || count <= 0) return null
    val name = layout?.takeIf { it.isNotBlank() }
    return if (name == null) "$count" else "$count ($name)"
}

internal fun codecName(mimeType: String?, tagged: String?): String? {
    val mime = mimeType?.lowercase()
    val fromMime = when {
        mime == null -> null
        mime.endsWith("/flac") -> "FLAC"
        mime.endsWith("/alac") -> "ALAC"
        mime.endsWith("/mpeg") || mime.endsWith("/mp3") -> "MP3"
        mime.endsWith("/mp4a-latm") || mime.contains("aac") -> "AAC"
        mime.endsWith("/opus") -> "Opus"
        mime.endsWith("/vorbis") -> "Vorbis"
        mime.endsWith("/raw") -> "PCM"
        mime.endsWith("/wav") || mime.endsWith("/x-wav") -> "WAV"
        mime.endsWith("/eac3-joc") -> "E-AC-3 JOC"
        mime.endsWith("/eac3") -> "E-AC-3"
        mime.endsWith("/ac3") -> "AC-3"
        mime.endsWith("/ape") -> "APE"
        mime.endsWith("/x-ms-wma") -> "WMA"
        else -> mime.substringAfterLast('/').uppercase().takeIf { it.isNotBlank() }
    }
    return fromMime ?: tagged?.takeIf { it.isNotBlank() }
}

internal fun latencyMs(frames: Int?, sampleRate: Int?): Double? {
    if (frames == null || frames <= 0) return null
    if (sampleRate == null || sampleRate <= 0) return null
    return frames * 1000.0 / sampleRate
}

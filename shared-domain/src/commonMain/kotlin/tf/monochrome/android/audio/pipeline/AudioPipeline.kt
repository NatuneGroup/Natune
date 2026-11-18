
package tf.monochrome.android.audio.pipeline

data class DecodedStream(
    val mimeType: String? = null,
    val sampleRate: Int? = null,
    val channelCount: Int? = null,

    val bitrate: Int? = null,

    val pcmBits: Int? = null,
    val pcmIsFloat: Boolean = false,
)

fun bits(value: Int?, isFloat: Boolean = false): String? =
    value?.takeIf { it > 0 }?.let { if (isFloat) "$it-bit float" else "$it-bit" }

fun kbps(bitsPerSecond: Int?): String? =
    bitsPerSecond?.takeIf { it > 0 }?.let { "${(it + 500) / 1000} kbps" }

fun channels(count: Int?, layout: String?): String? {
    if (count == null || count <= 0) return null
    val name = layout?.takeIf { it.isNotBlank() }
    return if (name == null) "$count" else "$count ($name)"
}

fun codecName(mimeType: String?, tagged: String?): String? {
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

fun latencyMs(frames: Int?, sampleRate: Int?): Double? {
    if (frames == null || frames <= 0) return null
    if (sampleRate == null || sampleRate <= 0) return null
    return frames * 1000.0 / sampleRate
}

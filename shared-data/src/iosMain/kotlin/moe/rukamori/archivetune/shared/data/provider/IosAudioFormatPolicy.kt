package moe.rukamori.archivetune.shared.data.provider

/** Metrolist's iOS policy prefers progressive MP4/AAC; FFmpeg can still use other audio as fallback. */
internal fun <T> selectIosAudioFormat(
    formats: List<T>,
    mimeType: (T) -> String,
    bitrate: (T) -> Int,
    low: Boolean,
): T? {
    val mp4 = formats.filter { mimeType(it).substringBefore(';').trim().equals("audio/mp4", ignoreCase = true) }
    val candidates = mp4.ifEmpty { formats }
    return if (low) candidates.minByOrNull(bitrate) else candidates.maxByOrNull(bitrate)
}

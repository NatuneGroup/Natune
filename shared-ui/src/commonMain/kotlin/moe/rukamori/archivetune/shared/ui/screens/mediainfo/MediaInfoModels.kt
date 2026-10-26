package moe.rukamori.archivetune.shared.ui.screens.mediainfo

/**
 * Stream format the provider reported for a track (the baseline FormatEntity fields).
 * Values stay raw so every surface formats them the same way.
 */
data class MediaInfoStreamFormat(
    val itag: Int = 0,
    val mimeType: String? = null,
    val codecs: String? = null,
    val bitrateBps: Int? = null,
    val sampleRateHz: Int? = null,
    val contentLengthBytes: Long? = null,
    val loudnessDb: Double? = null,
    val perceptualLoudnessDb: Double? = null,
)

/** Container-reported PCM description for the track (baseline ReportedAudioFormat). */
data class ReportedAudioFormatUi(
    val sampleRateHz: Int,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
)

/** Decoded PCM observed at the sink-processor input (baseline DecodedPcmFormat). */
data class DecodedPcmUi(
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int? = null,
    val isFloatingPoint: Boolean = false,
)

/** Android AudioTrack configuration observed for the track (baseline AndroidAudioOutputFormat). */
data class AndroidOutputUi(
    val sampleRateHz: Int,
    val channelCount: Int,
    val pcmBitsPerSample: Int? = null,
    val pcmIsFloatingPoint: Boolean = false,
    val offload: Boolean? = null,
)

enum class TrackCreditRole {
    WRITTEN_BY,
    COMPOSED_BY,
    LYRICS_BY,
}

enum class TrackCreditSource {
    VIDEO_DESCRIPTION,
    LYRICS_METADATA,
}

/**
 * One credited role with its names. The host derives credits from the video description and
 * lyric metadata; this model only displays them.
 */
data class TrackCreditUi(
    val role: TrackCreditRole,
    val names: String,
    val source: TrackCreditSource,
)

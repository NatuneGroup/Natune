package moe.rukamori.archivetune.shared.ui.screens.mediainfo

import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_hertz
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_info
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_ruler
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_tab_details
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_tab_information
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_tab_numbers
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource

/** Information, Details and Numbers tabs of the media-info screen, in baseline order. */
internal enum class MediaInfoTab(
    val label: StringResource,
    val icon: DrawableResource,
) {
    Information(Res.string.m9_media_info_tab_information, Res.drawable.m9_media_info_solar_info),
    Details(Res.string.m9_media_info_tab_details, Res.drawable.m9_media_info_solar_ruler),
    Numbers(Res.string.m9_media_info_tab_numbers, Res.drawable.m9_media_info_solar_hertz),
}

/** Song metadata the host knows from the local library; all fields are optional. */
data class MediaInfoSongUi(
    val title: String? = null,
    val artists: List<String> = emptyList(),
    val thumbnailUrl: String? = null,
    val durationSeconds: Long = 0L,
)

/** Provider video details. [MediaInfoUi.video] stays null while they load. */
data class MediaInfoVideoUi(
    val title: String? = null,
    val author: String? = null,
    val authorThumbnailUrl: String? = null,
    val description: String? = null,
    val subscribers: String? = null,
    val viewCount: Long? = null,
    val likeCount: Long? = null,
    val dislikeCount: Long? = null,
)

/** Provider identities the media-info screen can name. The host maps its own source enum onto these. */
enum class MediaProviderKey {
    TIDAL,
    QOBUZ,
    QOBUZ_BACKUP,
    DEEZER,
    APPLE,
    AMAZON,
    QQ,
    SPOTIFY,
    JIOSAAVN,
    YOUTUBE,
}

/** Stream the playback resolver actually used for this track, when the host recorded one. */
data class MediaInfoLiveStream(
    val source: MediaProviderKey? = null,
    val deliveryLabel: String? = null,
    val protocol: String? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
)

/**
 * Pipeline facts for the playing track, populated by the host from the audio pipeline monitor.
 * Field meanings follow the baseline TrackInfoViewModel.PipelineFacts.
 */
data class MediaInfoPipelineFacts(
    val decodedBits: Int? = null,
    val decodedFloat: Boolean = false,
    val decodedChannels: Int? = null,
    val chainChannels: Int? = null,
    val decoderName: String? = null,
    val routedName: String? = null,
    val halSampleRateHz: Int? = null,
    val engineName: String? = null,
    val pendingEngineName: String? = null,
    val usbExclusive: Boolean = false,
    val usbVendorId: Int = -1,
    val usbProductId: Int = -1,
    val tryptifyUsbRateHz: Int = 0,
)

/**
 * Bit-perfect runtime snapshot. [sourceFloatEncoding] and [outputFloatEncoding] are true when the
 * encoding is PCM float; a source sample rate of zero hides the pipeline row.
 */
data class MediaInfoBitPerfectStatus(
    val sourceSampleRateHz: Int = 0,
    val sourceIsLossy: Boolean = false,
    val sourceFloatEncoding: Boolean = false,
    val sourceBitDepth: Int = 0,
    val outputFloatEncoding: Boolean = false,
    val outputBitDepth: Int = 0,
    val mixerBitPerfectActive: Boolean = false,
)

/**
 * Live playback facts for the track that is currently playing; the host supplies them only for
 * that track. The playback, providers and DAC sections render only when [MediaInfoUi.isLiveTrack]
 * is true; the stream-format rows read the stream and pipeline facts whenever they are present,
 * as the baseline did.
 *
 * [usbWireBitDepth] is the host's wire-depth chain (LastWave, then Tryptify stream, then the
 * USB exclusive depth), with null when none is positive; the UI then uses the output depth.
 * [MediaInfoPipelineFacts.usbExclusive] is the single USB-exclusive-active flag for both the
 * signal-path and the pipeline rows.
 */
data class MediaInfoLiveState(
    val stream: MediaInfoLiveStream? = null,
    val availableProviders: List<MediaProviderKey> = emptyList(),
    val normalizeFactor: Float = 1f,
    val pipeline: MediaInfoPipelineFacts? = null,
    val bitPerfectUsbActive: Boolean = false,
    val tryptifyUsbPinActive: Boolean = false,
    val bitPerfect: MediaInfoBitPerfectStatus? = null,
    val usbWireBitDepth: Int? = null,
)

/** Output route summary from the platform output probe. [hasData] is false until something was observed. */
data class MediaInfoOutputStats(
    val deviceLabel: String,
    val deviceTypeLabel: String,
    val deviceSampleRates: List<Int> = emptyList(),
    val mixSampleRateHz: Int = 0,
) {
    val hasData: Boolean
        get() = mixSampleRateHz > 0 || deviceSampleRates.isNotEmpty()
}

/**
 * Everything the media-info screen shows for one media id. Blank ids render nothing.
 *
 * [format] is the reported stream format. [streamQualityLabel] is the stream-quality label, which
 * the host supplies only for a non-LOSSY tier; null falls back to the bitrate tier label.
 * [fileSizeLabel] overrides the file-size text; null uses the common binary-unit formatter.
 * [lyricsWriters] comes from lyric metadata and is parsed by the host.
 */
data class MediaInfoUi(
    val mediaId: String,
    val song: MediaInfoSongUi? = null,
    val video: MediaInfoVideoUi? = null,
    val format: MediaInfoStreamFormat? = null,
    val streamQualityLabel: String? = null,
    val fileSizeLabel: String? = null,
    val lyricsWriters: String = "",
    val isLocal: Boolean = false,
    val isLiveTrack: Boolean = false,
    val playbackVolume: Float? = null,
    val live: MediaInfoLiveState? = null,
    val outputStats: MediaInfoOutputStats? = null,
)

/** Callbacks for the media-info screen. The host performs clipboard, toast, and share intents. */
data class MediaInfoActions(
    val onCopy: (String) -> Unit = {},
    val onShare: (String) -> Unit = {},
    val onClose: () -> Unit = {},
)

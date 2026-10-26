/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.shared.ui.screens.mediainfo

/**
 * State for the track info sheet. The host resolves persistence, playback and provider lookups
 * and supplies plain values. Blank title and artists fall back to the video details, as the
 * baseline sheet did. Callers must pass only the decoded PCM, reported format and Android output
 * observed for this same track; those observations are track-scoped and are not device output.
 */
data class TrackInfoUi(
    val trackId: String,
    val title: String = "",
    val artists: String = "",
    val album: String? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    val videoTitle: String? = null,
    val videoAuthor: String? = null,
    val format: MediaInfoStreamFormat? = null,
    val reportedFormat: ReportedAudioFormatUi? = null,
    val decodedPcm: DecodedPcmUi? = null,
    val androidOutput: AndroidOutputUi? = null,
    val credits: List<TrackCreditUi> = emptyList(),
    val appleExperience: Boolean = false,
)

/** Callbacks for the track info sheet; the host decides navigation and presentation. */
data class TrackInfoActions(
    val onShowVideoDetails: () -> Unit = {},
)

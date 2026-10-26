/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lastfm

/** One artwork URL that Last.fm reports, with the size label it carries. Blank URLs are never kept. */
data class LastFmImage(
    val url: String,
    val size: String?,
)

/** The periods that the `user.getTop*` methods accept. [apiValue] is the exact text Last.fm expects. */
enum class LastFmChartPeriod(
    val apiValue: String,
) {
    OVERALL("overall"),
    SEVEN_DAYS("7day"),
    ONE_MONTH("1month"),
    THREE_MONTHS("3month"),
    SIX_MONTHS("6month"),
    TWELVE_MONTHS("12month"),
}

/**
 * Page sizes the dashboard reads keep from the retained baseline. [DEFAULT_PAGE_SIZE] is the baseline default. The
 * recent-tracks endpoint accepts at most [MAX_RECENT_TRACKS_PER_PAGE] per page.
 */
object LastFmDashboardLimits {
    const val DEFAULT_PAGE_SIZE = 50
    const val MAX_RECENT_TRACKS_PER_PAGE = 200

    /** The sizes the baseline dashboard requests: 200 recent tracks and 20 entries for each top list. */
    const val DASHBOARD_RECENT_TRACKS = 200
    const val DASHBOARD_TOP_ITEMS = 20
}

/**
 * One page of a Last.fm listing. [total] and [totalPages] are the counts Last.fm reports for the whole listing. They
 * stay null when the response omits them, so a consumer shows a placeholder rather than a guessed count.
 */
data class LastFmPage<T>(
    val items: List<T>,
    val page: Int,
    val perPage: Int,
    val totalPages: Int?,
    val total: Int?,
)

/** The profile from `user.getInfo`. Last.fm omits empty values, so every field is optional. */
data class LastFmUserProfile(
    val name: String?,
    val realName: String?,
    val profileUrl: String?,
    val images: List<LastFmImage>,
    val country: String?,
    val age: Int?,
    val gender: String?,
    val subscriber: Int?,
    val playCount: Long?,
    val playlistCount: Int?,
    val registeredEpochSeconds: Long?,
)

/** One scrobble from `user.getRecentTracks`. [playedAtEpochSeconds] is null while the track is now playing. */
data class LastFmRecentTrack(
    val name: String?,
    val artist: String?,
    val album: String?,
    val url: String?,
    val images: List<LastFmImage>,
    val playedAtEpochSeconds: Long?,
    val isNowPlaying: Boolean,
)

/** One ranked entry from `user.getTopTracks`. */
data class LastFmTopTrack(
    val rank: Int?,
    val name: String?,
    val artist: String?,
    val url: String?,
    val playCount: Int?,
    val images: List<LastFmImage>,
)

/** One ranked entry from `user.getTopArtists`. */
data class LastFmTopArtist(
    val rank: Int?,
    val name: String?,
    val url: String?,
    val playCount: Int?,
    val images: List<LastFmImage>,
)

/** One ranked entry from `user.getTopAlbums`. */
data class LastFmTopAlbum(
    val rank: Int?,
    val name: String?,
    val artist: String?,
    val url: String?,
    val playCount: Int?,
    val images: List<LastFmImage>,
)

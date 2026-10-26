/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lastfm

/**
 * The read side of the Last.fm dashboard and the track tags. The reads are signed like the write calls, but they never
 * need a session key, so the account is chosen by the user name passed in. Every call returns a [Result]. Implementations
 * rethrow cancellation instead of turning it into a failure.
 */
interface LastFmDashboardService {
    /** user.getInfo for [username]. */
    suspend fun getUserProfile(username: String): Result<LastFmUserProfile>

    /** user.getRecentTracks, newest first. [limit] is 1 to [LastFmDashboardLimits.MAX_RECENT_TRACKS_PER_PAGE]. */
    suspend fun getRecentTracks(
        username: String,
        limit: Int = LastFmDashboardLimits.DEFAULT_PAGE_SIZE,
        page: Int = 1,
    ): Result<LastFmPage<LastFmRecentTrack>>

    /** user.getTopTracks for [period]. */
    suspend fun getTopTracks(
        username: String,
        period: LastFmChartPeriod = LastFmChartPeriod.OVERALL,
        limit: Int = LastFmDashboardLimits.DEFAULT_PAGE_SIZE,
        page: Int = 1,
    ): Result<LastFmPage<LastFmTopTrack>>

    /** user.getTopArtists for [period]. */
    suspend fun getTopArtists(
        username: String,
        period: LastFmChartPeriod = LastFmChartPeriod.OVERALL,
        limit: Int = LastFmDashboardLimits.DEFAULT_PAGE_SIZE,
        page: Int = 1,
    ): Result<LastFmPage<LastFmTopArtist>>

    /** user.getTopAlbums for [period]. */
    suspend fun getTopAlbums(
        username: String,
        period: LastFmChartPeriod = LastFmChartPeriod.OVERALL,
        limit: Int = LastFmDashboardLimits.DEFAULT_PAGE_SIZE,
        page: Int = 1,
    ): Result<LastFmPage<LastFmTopAlbum>>

    /**
     * track.getInfo for the community tag names of one track, in Last.fm's order. A track without tags gives an empty
     * list, which is an answer, not a failure.
     */
    suspend fun getTrackTags(
        artist: String,
        track: String,
    ): Result<List<String>>
}

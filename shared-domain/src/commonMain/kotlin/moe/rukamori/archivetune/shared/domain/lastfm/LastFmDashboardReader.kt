/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lastfm

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * One finished dashboard load for one account. Each section keeps its own [Result], so a failed section never hides the
 * others. [endpoint] and [username] say which account the data belongs to.
 */
data class LastFmDashboardLoad(
    val endpoint: String,
    val username: String,
    val profile: Result<LastFmUserProfile>,
    val recentTracks: Result<LastFmPage<LastFmRecentTrack>>,
    val topTracks: Result<LastFmPage<LastFmTopTrack>>,
    val topArtists: Result<LastFmPage<LastFmTopArtist>>,
    val topAlbums: Result<LastFmPage<LastFmTopAlbum>>,
)

/**
 * The last successful value of each dashboard section for one endpoint and account. A refresh that fails for a section
 * keeps that section's previous value, but only while the endpoint and account stay the same. One account's numbers
 * therefore never stay on screen after the signed-in account changes.
 */
data class LastFmDashboardSnapshot(
    val endpoint: String,
    val username: String,
    val profile: LastFmUserProfile? = null,
    val recentTracks: LastFmPage<LastFmRecentTrack>? = null,
    val topTracks: LastFmPage<LastFmTopTrack>? = null,
    val topArtists: LastFmPage<LastFmTopArtist>? = null,
    val topAlbums: LastFmPage<LastFmTopAlbum>? = null,
) {
    /** Applies [load]. Values from another account are dropped, and a failed section keeps its own last value. */
    fun refreshedWith(load: LastFmDashboardLoad): LastFmDashboardSnapshot {
        val sameAccount = endpoint == load.endpoint && username.equals(load.username, ignoreCase = true)
        return LastFmDashboardSnapshot(
            endpoint = load.endpoint,
            username = load.username,
            profile = load.profile.getOrNull() ?: profile.takeIf { sameAccount },
            recentTracks = load.recentTracks.getOrNull() ?: recentTracks.takeIf { sameAccount },
            topTracks = load.topTracks.getOrNull() ?: topTracks.takeIf { sameAccount },
            topArtists = load.topArtists.getOrNull() ?: topArtists.takeIf { sameAccount },
            topAlbums = load.topAlbums.getOrNull() ?: topAlbums.takeIf { sameAccount },
        )
    }
}

/**
 * Reads the signed-in account's dashboard. The five reads run in parallel, as the baseline did, and the account comes
 * from the stored session at the moment of the call, never from a value the screen remembered.
 */
class LastFmDashboardReader(
    private val service: LastFmDashboardService,
    private val sessions: LastFmSessionPort,
    private val config: () -> LastFmApiConfig,
) {
    /**
     * Returns null when no account is signed in or the credentials are not usable, so nothing is requested anonymously.
     * Otherwise every section reports its own outcome. Only cancellation escapes this call.
     */
    suspend fun load(): LastFmDashboardLoad? {
        val current = config()
        if (!current.credentials.isUsable) return null
        val session =
            sessions.currentSession()?.takeIf { it.username.isNotBlank() && it.sessionKey.isNotBlank() }
                ?: return null
        val username = session.username.trim()
        return coroutineScope {
            val topLimit = LastFmDashboardLimits.DASHBOARD_TOP_ITEMS
            val profile = async { service.getUserProfile(username) }
            val recent =
                async { service.getRecentTracks(username, limit = LastFmDashboardLimits.DASHBOARD_RECENT_TRACKS) }
            val topTracks = async { service.getTopTracks(username, limit = topLimit) }
            val topArtists = async { service.getTopArtists(username, limit = topLimit) }
            val topAlbums = async { service.getTopAlbums(username, limit = topLimit) }
            LastFmDashboardLoad(
                endpoint = current.endpoint,
                username = username,
                profile = profile.await(),
                recentTracks = recent.await(),
                topTracks = topTracks.await(),
                topArtists = topArtists.await(),
                topAlbums = topAlbums.await(),
            )
        }
    }
}

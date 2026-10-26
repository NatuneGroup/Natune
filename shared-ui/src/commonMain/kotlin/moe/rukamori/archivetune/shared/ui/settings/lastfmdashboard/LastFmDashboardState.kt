/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings.lastfmdashboard

import moe.rukamori.archivetune.shared.domain.lastfm.LastFmDashboardSnapshot
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmImage
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmRecentTrack
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmTopTrack

/** The four lists the dashboard can show. Labels and icons are resources in the page. */
enum class LastFmDashboardFilter { RECENT, TOP_TRACKS, TOP_ARTISTS, TOP_ALBUMS }

/** Track actions that resolve a YouTube song first. Each one shows its own spinner in the track sheet. */
enum class LastFmTrackAction { START_MIX, PLAY, PLAY_NEXT, ADD_TO_QUEUE }

/** Short results that the page shows as snackbars. */
enum class LastFmDashboardMessage { SONG_COPIED, NO_YOUTUBE_MATCH, PLAYER_UNAVAILABLE }

/**
 * One track as the rows and the track sheet carry it. [lastFmArtwork] is a usable Last.fm image or null. [playCount] is
 * the merged repeat count for a recent row, or the chart count for a top track.
 */
data class LastFmDashboardTrack(
    val title: String,
    val artist: String?,
    val url: String?,
    val lastFmArtwork: String?,
    val playCount: Int?,
) {
    /** Lowercase key shared by the artwork lookups and the recent-artwork fallback. */
    val artworkKey: String
        get() = trackKey(title, artist)
}

/** Everything the page draws. */
data class LastFmDashboardUiState(
    /** False until the first account check has finished. The page shows a spinner until then. */
    val checked: Boolean = false,
    val signedIn: Boolean = false,
    /** True after one load has finished for the current account, so an empty section is a result, not a wait. */
    val loaded: Boolean = false,
    val snapshot: LastFmDashboardSnapshot? = null,
    val refreshing: Boolean = false,
    val filter: LastFmDashboardFilter = LastFmDashboardFilter.RECENT,
    val searchVisible: Boolean = false,
    val searchQuery: String = "",
    val sheetTrack: LastFmDashboardTrack? = null,
    /** Up to three community tags joined with ", ", or null when Last.fm has none or the read failed. */
    val sheetGenre: String? = null,
    /** The track's Last.fm image, or a YouTube thumbnail that was resolved for the banner. */
    val sheetArtwork: String? = null,
    val loadingAction: LastFmTrackAction? = null,
)

/** Last.fm uses one grey placeholder for artists and albums without art. The placeholder is never shown. */
internal object LastFmArtwork {
    private const val NO_ART_HASH = "2a96cbd8b46e442fc41c2b86b821562f"
    private val sizePreference = listOf("extralarge", "large", "medium", "small")

    fun isRealImage(url: String?): Boolean = !url.isNullOrBlank() && !url.contains(NO_ART_HASH)

    /** The largest real image: extralarge, then large, medium, small, then any real image. */
    fun best(images: List<LastFmImage>): String? {
        val real = images.filter { isRealImage(it.url) }
        for (size in sizePreference) {
            real.firstOrNull { it.size.equals(size, ignoreCase = true) }?.let { return it.url }
        }
        return real.firstOrNull()?.url
    }
}

/** Groups digits in threes with commas. Common code has no String.format, so the baseline's "%,d" is written out. */
internal fun groupedCount(value: Long): String {
    val digits = value.toString()
    val negative = digits.startsWith("-")
    val body = if (negative) digits.substring(1) else digits
    val grouped = StringBuilder()
    body.forEachIndexed { index, char ->
        if (index > 0 && (body.length - index) % 3 == 0) grouped.append(',')
        grouped.append(char)
    }
    return if (negative) "-$grouped" else grouped.toString()
}

/** The lowercase, trimmed key that the artwork caches and the recent-artwork fallback use. */
internal fun trackKey(title: String?, artist: String?): String =
    "${title.orEmpty().trim().lowercase()}::${artist.orEmpty().trim().lowercase()}"

/** Case-insensitive contains match over any of [fields]. The caller passes an already trimmed, non-empty [query]. */
internal fun matchesSearch(query: String, vararg fields: String?): Boolean =
    fields.any { field -> field?.contains(query, ignoreCase = true) == true }

/** One recent row after consecutive repeats are merged. */
internal data class LastFmRecentRow(
    val track: LastFmRecentTrack,
    val playCount: Int,
    val nowPlaying: Boolean,
)

private fun sameScrobble(a: LastFmRecentTrack, b: LastFmRecentTrack): Boolean =
    trackKey(a.name, a.artist) == trackKey(b.name, b.artist)

/**
 * Merges consecutive repeats of one scrobble into a single row with a count. Older copies of the track that is playing
 * now are hidden, and a merged run that holds the now-playing scrobble shows as now playing.
 */
internal fun recentRows(tracks: List<LastFmRecentTrack>): List<LastFmRecentRow> {
    val nowPlaying = tracks.firstOrNull { it.isNowPlaying }
    val kept = if (nowPlaying == null) {
        tracks
    } else {
        tracks.filter { it.isNowPlaying || !sameScrobble(it, nowPlaying) }
    }
    val rows = ArrayList<LastFmRecentRow>()
    for (track in kept) {
        val last = rows.lastOrNull()
        if (last != null && sameScrobble(last.track, track)) {
            rows[rows.lastIndex] = last.copy(
                track = if (track.isNowPlaying) track else last.track,
                playCount = last.playCount + 1,
                nowPlaying = last.nowPlaying || track.isNowPlaying,
            )
        } else {
            rows.add(LastFmRecentRow(track = track, playCount = 1, nowPlaying = track.isNowPlaying))
        }
    }
    return rows
}

/** The first real Last.fm image for each recent track, keyed the way the track rows look them up. */
internal fun recentArtworkByTrack(tracks: List<LastFmRecentTrack>): Map<String, String> {
    val artwork = HashMap<String, String>()
    for (track in tracks) {
        val url = LastFmArtwork.best(track.images) ?: continue
        val key = trackKey(track.name, track.artist)
        if (!artwork.containsKey(key)) artwork[key] = url
    }
    return artwork
}

internal fun LastFmRecentTrack.toDashboardTrack(playCount: Int?): LastFmDashboardTrack =
    LastFmDashboardTrack(
        title = name.orEmpty(),
        artist = artist,
        url = url,
        lastFmArtwork = LastFmArtwork.best(images),
        playCount = playCount,
    )

internal fun LastFmTopTrack.toDashboardTrack(): LastFmDashboardTrack =
    LastFmDashboardTrack(
        title = name.orEmpty(),
        artist = artist,
        url = url,
        lastFmArtwork = LastFmArtwork.best(images),
        playCount = playCount,
    )

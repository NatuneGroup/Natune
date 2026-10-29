package moe.rukamori.archivetune.ui.screens.settings

import java.util.concurrent.ConcurrentHashMap
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.SongMetadataOverrideEntity
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.pages.SearchResult
import moe.rukamori.archivetune.lastfm.models.RecentTrack
import moe.rukamori.archivetune.lastfm.models.TopTrack
import moe.rukamori.archivetune.lastfm.models.UserImage
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.songmetadata.resolveEffectiveSongMetadata
import timber.log.Timber

/**
 * Process-wide cache of YouTube search results keyed by "<title>::<artist>". Last.fm tracks don't
 * carry YouTube video IDs, so every playback action on a Last.fm track needs to round-trip through
 * a YouTube song search first.
 */
private val ytSearchCache = ConcurrentHashMap<String, SongItem?>()

/**
 * Searches YouTube Music for a Last.fm track by "<title> <artist>" query and returns the first
 * SongItem result, or null if no match. Results are cached in [ytSearchCache] to avoid re-searching
 * for the same track.
 */
private suspend fun searchYtForLastFmTrack(title: String, artist: String?): SongItem? {
    if (title.isBlank()) return null
    val cacheKey = "${title.trim().lowercase()}::${artist?.trim()?.lowercase().orEmpty()}"
    ytSearchCache[cacheKey]?.let {
        Timber.d("searchYt cache hit: %s → %s", cacheKey, it?.id ?: "null-cached")
        return it
    }
    val term = listOfNotNull(artist?.takeIf(String::isNotBlank), title).joinToString(" ")
    Timber.d("searchYt query: \"%s\" (key=%s)", term, cacheKey)
    val searchResult = YouTube.search(term, YouTube.SearchFilter.FILTER_SONG).getOrNull()
    if (searchResult == null) {
        Timber.w("searchYt no result for: \"%s\"", term)
        ytSearchCache[cacheKey] = null
        return null
    }
    val first = findFirstSongItem(searchResult)
    if (first == null) {
        Timber.w("searchYt no SongItem in results for: \"%s\"", term)
    } else {
        Timber.d("searchYt resolved: \"%s\" → videoId=%s thumb=%s", term, first.id, first.thumbnail)
    }
    ytSearchCache[cacheKey] = first
    return first
}

/** Extracts the first SongItem from a SearchResult's items list. */
internal fun findFirstSongItem(result: SearchResult): SongItem? {
    for (item in result.items) {
        if (item is SongItem) return item
    }
    return null
}

/** Lightweight track reference carried across the bottom-sheet boundary. */
internal data class LastFmTrackRef(
    val title: String,
    val artist: String?,
    val url: String?,
    val image: List<UserImage>?,
    val playCount: Int?,
    val isNowPlaying: Boolean,
    val songId: String? = null,
) {
    fun artworkKey(): String = "${title.trim().lowercase()}::${artist.orEmpty().trim().lowercase()}"

    fun effectiveNames(overrides: Map<String, SongMetadataOverrideEntity>) =
        resolveEffectiveSongMetadata(
            title, listOfNotNull(artist), songId?.let(overrides::get),
        )

    suspend fun resolveMetadata(database: MusicDatabase): MediaMetadata? =
        songId?.let { database.getSongById(it)?.toMediaMetadata() }
            ?: searchYtForLastFmTrack(title, artist)?.toMediaMetadata()
}

internal fun RecentTrack.toRef(playCount: Int? = null, songIds: Map<String, String> = emptyMap()): LastFmTrackRef = LastFmTrackRef(
    title = name.orEmpty(),
    artist = artist?.text,
    url = url,
    image = image,
    playCount = playCount,
    isNowPlaying = isNowPlaying,
    songId = songIds[trackArtworkKey()],
)

internal fun TopTrack.toRef(songIds: Map<String, String> = emptyMap()): LastFmTrackRef = LastFmTrackRef(
    title = name.orEmpty(),
    artist = artist?.text,
    url = url,
    image = image,
    playCount = playcount,
    isNowPlaying = false,
    songId = songIds[trackArtworkKey()],
)

internal fun RecentTrack.trackArtworkKey(): String = "${name.orEmpty().trim().lowercase()}::${artist?.text.orEmpty().trim().lowercase()}"

internal fun TopTrack.trackArtworkKey(): String = "${name.orEmpty().trim().lowercase()}::${artist?.text.orEmpty().trim().lowercase()}"

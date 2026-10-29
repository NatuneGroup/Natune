package moe.rukamori.archivetune.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.LocalSongMetadataOverrides
import moe.rukamori.archivetune.viewmodels.YearInMusicUiState

@Composable
internal fun rememberYearInMusicCards(content: YearInMusicUiState.Content): List<YearInMusicRecapCard> {
    val overrides = LocalSongMetadataOverrides.current
    val effectiveTopSongs = remember(content.topSongsStats, overrides) {
        content.topSongsStats.map { song -> song.copy(title = overrides[song.id]?.title ?: song.title) }
    }
    val introLabel = stringResource(R.string.year_in_music_recap)
    val totalsLabel = stringResource(R.string.total_listening_time)
    val topTrackLabel = stringResource(R.string.year_in_music_top_track)
    val artistsLabel = stringResource(R.string.year_in_music_ranked_artists)
    val albumsLabel = stringResource(R.string.year_in_music_ranked_albums)
    val summaryLabel = stringResource(R.string.share_summary)
    val emptyLabel = stringResource(R.string.no_listening_data)

    return remember(
        content.selectedYear,
        content.totalListeningTime,
        content.totalSongsPlayed,
        content.topSongsStats,
        effectiveTopSongs,
        content.topSongs,
        content.topArtists,
        content.topAlbums,
        introLabel,
        totalsLabel,
        topTrackLabel,
        artistsLabel,
        albumsLabel,
        summaryLabel,
        emptyLabel,
    ) {
        if (!content.hasData) {
            listOf(YearInMusicRecapCard.Empty(content.selectedYear, emptyLabel))
        } else {
            buildList {
                add(
                    YearInMusicRecapCard.Intro(
                        year = content.selectedYear,
                        totalListeningTime = content.totalListeningTime,
                        totalSongsPlayed = content.totalSongsPlayed,
                        label = introLabel,
                    ),
                )
                add(
                    YearInMusicRecapCard.Totals(
                        totalListeningTime = content.totalListeningTime,
                        totalSongsPlayed = content.totalSongsPlayed,
                        topSong = effectiveTopSongs.firstOrNull(),
                        topArtist = content.topArtists.firstOrNull(),
                        label = totalsLabel,
                    ),
                )
                effectiveTopSongs.firstOrNull()?.let { topSong ->
                    add(
                        YearInMusicRecapCard.TopSong(
                            song = topSong,
                            originalSong = content.topSongs.firstOrNull { it.id == topSong.id } ?: content.topSongs.firstOrNull(),
                            label = topTrackLabel,
                        ),
                    )
                }
                if (content.topArtists.isNotEmpty()) {
                    add(
                        YearInMusicRecapCard.RankedArtists(
                            artists = content.topArtists,
                            label = artistsLabel,
                        ),
                    )
                }
                if (content.topAlbums.isNotEmpty()) {
                    add(
                        YearInMusicRecapCard.RankedAlbums(
                            albums = content.topAlbums,
                            label = albumsLabel,
                        ),
                    )
                }
                add(
                    YearInMusicRecapCard.Summary(
                        year = content.selectedYear,
                        totalListeningTime = content.totalListeningTime,
                        totalSongsPlayed = content.totalSongsPlayed,
                        topSongs = effectiveTopSongs,
                        topArtists = content.topArtists,
                        topAlbums = content.topAlbums,
                        label = summaryLabel,
                    ),
                )
            }
        }
    }
}

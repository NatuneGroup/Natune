package moe.rukamori.archivetune.ui.screens

import moe.rukamori.archivetune.db.entities.EventWithSong
import moe.rukamori.archivetune.db.entities.SongMetadataOverrideEntity
import moe.rukamori.archivetune.innertube.pages.HistoryPage
import moe.rukamori.archivetune.songmetadata.resolveEffectiveSongMetadata
import moe.rukamori.archivetune.viewmodels.DateAgo

internal fun filterLocalEvents(
    events: Map<DateAgo, List<EventWithSong>>,
    query: String,
): Map<DateAgo, List<EventWithSong>> {
    if (query.isBlank()) return events

    return events
        .mapValues { (_, songs) ->
            songs.filter { event ->
                event.song.effectiveTitle
                    .contains(query, ignoreCase = true) ||
                    event.song.effectiveArtistNames.any { artistName ->
                        artistName.contains(query, ignoreCase = true)
                    }
            }
        }.filterValues { it.isNotEmpty() }
}

internal fun filterRemoteSections(
    sections: List<HistoryPage.HistorySection>,
    query: String,
    overrides: Map<String, SongMetadataOverrideEntity>,
): List<HistoryPage.HistorySection> {
    if (query.isBlank()) return sections

    return sections
        .map { section ->
            section.copy(
                songs =
                    section.songs.filter { song ->
                        val names = resolveEffectiveSongMetadata(
                            song.title, song.artists.map { it.name }, overrides[song.id],
                        )
                        names.title.contains(query, ignoreCase = true) ||
                            names.artistNames.any { artist ->
                                artist.contains(query, ignoreCase = true)
                            }
                    },
            )
        }.filter { it.songs.isNotEmpty() }
}

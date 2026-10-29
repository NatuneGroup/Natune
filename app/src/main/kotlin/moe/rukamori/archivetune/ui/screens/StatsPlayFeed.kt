package moe.rukamori.archivetune.ui.screens

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import moe.rukamori.archivetune.db.entities.SongMetadataOverrideEntity
import moe.rukamori.archivetune.innertube.pages.HistoryPage
import moe.rukamori.archivetune.songmetadata.resolveEffectiveSongMetadata

/**
 * One listen as a remote feed reports it: the track, its artist and album, how long it lasted and
 * when it happened.
 *
 * A YouTube history section names the day of a whole shelf and nothing finer, so [hasClockTime] is
 * false there and the hour chart is left empty rather than filled with a midnight no play claimed.
 */
@Immutable
internal data class StatsPlay(
    val trackId: String,
    val title: String,
    val thumbnailUrl: String?,
    val artistName: String,
    val album: StatsPlayAlbum?,
    val durationMs: Long,
    val playedAt: Instant?,
    val hasClockTime: Boolean = true,
)

/** An album as a feed reports it: what to group its plays by, and what an album row shows. */
@Immutable
internal data class StatsPlayAlbum(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
)

/**
 * The YouTube history page as a play feed.
 *
 * Its sections carry the songs and date the whole shelf to a day, which is all the day chart needs
 * and all the hour chart must not use.
 */
internal fun HistoryPage.historyPlays(
    unknownArtistLabel: String,
    overrides: Map<String, SongMetadataOverrideEntity>,
): List<StatsPlay> =
    sections.orEmpty().flatMap { section ->
        val playedAt = section.playedAt()
        section.songs.map { song ->
            val names = resolveEffectiveSongMetadata(
                song.title, song.artists.map { it.name }, overrides[song.id],
            )
            StatsPlay(
                trackId = song.id,
                title = names.title,
                thumbnailUrl = song.thumbnail,
                artistName = names.artistNames.joinToString().ifBlank { unknownArtistLabel },
                album =
                    song.album
                        ?.takeIf { it.name.isNotBlank() }
                        ?.let { album ->
                            // A song's own artwork is its album cover on YouTube Music; the album
                            // node nested in the item carries no artwork of its own.
                            StatsPlayAlbum(
                                id = album.id.ifBlank { album.name },
                                title = album.name,
                                thumbnailUrl = song.thumbnail,
                            )
                        },
                durationMs = (song.duration ?: 0).toLong() * 1_000L,
                playedAt = playedAt,
                hasClockTime = false,
            )
        }
    }

/** The day a history section's title names, or null when it names one this code cannot read. */
private fun HistoryPage.HistorySection.sectionDate(): LocalDate? {
    val title = title.trim().lowercase(Locale.getDefault())
    return when {
        title == "today" -> LocalDate.now()
        title == "yesterday" -> LocalDate.now().minusDays(1)
        title.contains("this week") -> LocalDate.now()
        title.contains("last week") -> LocalDate.now().minusDays(7)
        else -> parseHistoryDate(title)
    }
}

/** A section dates its songs to the day it names, which is what midnight of that day marks. */
private fun HistoryPage.HistorySection.playedAt(): Instant? =
    sectionDate()?.atStartOfDay(ZoneId.systemDefault())?.toInstant()

private fun parseHistoryDate(title: String): LocalDate? {
    val year = LocalDate.now().year
    val fullDateFormatters =
        listOf(
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH),
        )
    fullDateFormatters.firstNotNullOfOrNull { formatter ->
        runCatching { LocalDate.parse(title.replaceFirstChar { it.uppercase() }, formatter) }.getOrNull()
    }?.let { return it }
    return listOf("MMM d", "MMMM d").firstNotNullOfOrNull { pattern ->
        runCatching {
            LocalDate.parse(
                "${title.replaceFirstChar { it.uppercase() }}, $year",
                DateTimeFormatter.ofPattern("$pattern, uuuu", Locale.ENGLISH),
            )
        }.getOrNull()
    }?.let { parsed -> if (parsed.isAfter(LocalDate.now())) parsed.minusYears(1) else parsed }
}

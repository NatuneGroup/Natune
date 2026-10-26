/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.screens.artistalbum

import moe.rukamori.archivetune.shared.domain.repository.ContentDownload
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.DownloadPhase

/**
 * Compact count with at most one decimal, using the baseline thresholds. Integer math keeps this in commonMain;
 * java.text.DecimalFormat is JVM-only.
 */
internal fun formatCompactCount(count: Long): String {
    val magnitude = if (count < 0) -count else count
    val sign = if (count < 0) "-" else ""
    return when {
        magnitude < 1_000L -> count.toString()
        magnitude < 10_000L -> sign + compactTenths(magnitude, 1_000L) + "K"
        magnitude < 1_000_000L -> sign + (magnitude / 1_000L).toString() + "K"
        magnitude < 10_000_000L -> sign + compactTenths(magnitude, 1_000_000L) + "M"
        magnitude < 1_000_000_000L -> sign + (magnitude / 1_000_000L).toString() + "M"
        magnitude < 10_000_000_000L -> sign + compactTenths(magnitude, 1_000_000_000L) + "B"
        else -> sign + (magnitude / 1_000_000_000L).toString() + "B"
    }
}

/** Floors to one decimal of [unit], matching the baseline floor-then-format behaviour. */
private fun compactTenths(magnitude: Long, unit: Long): String {
    val tenths = magnitude / (unit / 10L)
    val whole = tenths / 10L
    val fraction = tenths % 10L
    return if (fraction == 0L) whole.toString() else "$whole.$fraction"
}

private val CompactProviderCount = Regex("""\d+(?:[.,]\d+)?[\s\u00A0\u202F\u2007]*[KMB]""", RegexOption.IGNORE_CASE)
private val PlainProviderCount = Regex("""\d+(?:[.,]\d+)*""")

/**
 * Normalizes provider count text such as "53,2 M" or "532,000" into the compact header form. Providers group digits
 * with locale separators, so a plain pattern alone would read "53,2 M" as "532".
 */
internal fun compactProviderCount(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    CompactProviderCount.find(raw)?.value?.let { compact ->
        return compact
            .filterNot { it.isWhitespace() || it == '\u00A0' || it == '\u202F' || it == '\u2007' }
            .replace(',', '.')
            .uppercase()
    }
    val digits = PlainProviderCount.find(raw)?.value?.filter { it.isDigit() }?.toLongOrNull() ?: return null
    return formatCompactCount(digits)
}

/**
 * Track and album duration text as the baseline prints it. The baseline helper's epoch-date branch never applies to a
 * duration, so it is not ported.
 */
internal fun formatTrackDuration(durationMs: Long?): String {
    if (durationMs == null || durationMs < 0L) return ""
    var seconds = durationMs / 1_000L
    val days = seconds / 86_400L
    seconds %= 86_400L
    val hours = seconds / 3_600L
    seconds %= 3_600L
    val minutes = seconds / 60L
    seconds %= 60L
    val secondText = seconds.toString().padStart(2, '0')
    return when {
        days > 0L -> "${days}d ${hours}h ${minutes}m ${seconds}s"
        hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
        else -> "$minutes:$secondText"
    }
}

/** Audience and catalog numbers. Remote counts come from the loaded shelves; a missing page falls back to the library. */
internal data class ArtistStatValues(
    val monthlyListeners: String?,
    val subscribers: String?,
    val songCount: Int,
    val songsHaveMore: Boolean,
    val albumCount: Int,
    val albumsHaveMore: Boolean,
)

internal fun artistStatValues(showLocal: Boolean, page: ArtistPageUi?, library: ArtistLibraryUi?): ArtistStatValues {
    val shelves = page?.shelves.orEmpty()
    val songShelves = shelves.filter { shelf -> shelf.shelf.page.items.any { it.item.ref.kind == ContentKind.SONG } }
    val albumShelves = shelves.filter { shelf -> shelf.shelf.page.items.any { it.item.ref.kind == ContentKind.ALBUM } }
    val remoteSongs = songShelves.asSequence()
        .flatMap { it.shelf.page.items.asSequence() }
        .filter { it.item.ref.kind == ContentKind.SONG }
        .distinctBy { it.item.ref }
        .count()
    val remoteAlbums = albumShelves.asSequence()
        .flatMap { it.shelf.page.items.asSequence() }
        .filter { it.item.ref.kind == ContentKind.ALBUM }
        .distinctBy { it.item.ref }
        .count()
    val useRemote = !showLocal && page != null
    return ArtistStatValues(
        monthlyListeners = compactProviderCount(page?.monthlyListenersText),
        subscribers = compactProviderCount(page?.subscribersText),
        songCount = if (useRemote) remoteSongs else library?.songs.orEmpty().size,
        songsHaveMore = useRemote && songShelves.any { it.more != null },
        albumCount = if (useRemote) remoteAlbums else library?.albums.orEmpty().size,
        albumsHaveMore = useRemote && albumShelves.any { it.more != null },
    )
}

/** A shelf whose every loaded item is a song, shown as a track list rather than a card row. */
internal fun ArtistShelfUi.isSongList(): Boolean =
    layout == ArtistShelfLayout.LIST &&
        shelf.page.items.isNotEmpty() &&
        shelf.page.items.all { it.item.ref.kind == ContentKind.SONG }

/** The first song list moves above the other shelves, matching the baseline top-songs placement. */
internal fun orderedShelves(shelves: List<ArtistShelfUi>): List<ArtistShelfUi> {
    val topSongs = shelves.firstOrNull { it.isSongList() } ?: return shelves
    return listOf(topSongs) + shelves.filterNot { it === topSongs }
}

/** Newest release by year; releases without a year rank lowest, and the first of equal years wins. */
internal fun latestRelease(releases: List<ArtistReleaseUi>): ArtistReleaseUi? =
    releases.maxByOrNull { it.year ?: Int.MIN_VALUE }

internal fun ArtistReleaseUi.asContentItem(): ContentItem =
    ContentItem(ref = album, title = title, artworkUri = artworkUri)

/** Header download state for one album. Completed means every distinct song is complete; nothing is claimed otherwise. */
internal sealed interface AlbumDownloadState {
    data object None : AlbumDownloadState
    data class Partial(val progress: Float, val paused: Boolean) : AlbumDownloadState
    data object Completed : AlbumDownloadState
}

internal fun albumDownloadState(songs: List<ContentRef>, downloads: List<ContentDownload>): AlbumDownloadState {
    val wanted = songs.distinct()
    if (wanted.isEmpty()) return AlbumDownloadState.None
    val relevant = downloads.filter { it.ref in wanted && it.phase != DownloadPhase.REMOVING }
    if (relevant.isEmpty()) return AlbumDownloadState.None
    val byRef = relevant.groupBy { it.ref }
    val allCompleted = wanted.all { ref -> byRef[ref].orEmpty().any { it.phase == DownloadPhase.COMPLETED } }
    if (allCompleted) return AlbumDownloadState.Completed
    var fractionSum = 0f
    for (ref in wanted) {
        val rows = byRef[ref].orEmpty()
        fractionSum += if (rows.any { it.phase == DownloadPhase.COMPLETED }) {
            1f
        } else {
            rows.maxOfOrNull { row ->
                val total = row.totalBytes
                if (total != null && total > 0L) row.bytesDownloaded.toFloat() / total.toFloat() else 0f
            } ?: 0f
        }
    }
    val progress = (fractionSum / wanted.size).coerceIn(0f, 1f)
    val active = relevant.any { it.phase == DownloadPhase.QUEUED || it.phase == DownloadPhase.DOWNLOADING }
    val stopped = relevant.any { it.phase == DownloadPhase.STOPPED }
    return AlbumDownloadState.Partial(progress = progress, paused = !active && stopped)
}

package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.File
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import javax.imageio.ImageIO
import androidx.compose.ui.graphics.toAwtImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ListeningRank
import moe.rukamori.archivetune.shared.domain.repository.ListeningStatistics
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.screens.StatisticsActions
import moe.rukamori.archivetune.shared.ui.screens.StatisticsCoverage
import moe.rukamori.archivetune.shared.ui.screens.StatisticsSource
import moe.rukamori.archivetune.shared.ui.screens.StatisticsUi
import moe.rukamori.archivetune.shared.ui.screens.YearInMusicActions
import moe.rukamori.archivetune.shared.ui.screens.YearInMusicUi
import moe.rukamori.archivetune.shared.ui.screens.YearRecapShare
import org.jetbrains.compose.resources.stringResource
import moe.rukamori.archivetune.shared.ui.resources.m4StatAllRes
import moe.rukamori.archivetune.shared.ui.resources.m4StatOneMonthRes
import moe.rukamori.archivetune.shared.ui.resources.m4StatOneWeekRes
import moe.rukamori.archivetune.shared.ui.resources.m4StatOneYearRes
import moe.rukamori.archivetune.shared.ui.resources.m4StatSixMonthsRes
import moe.rukamori.archivetune.shared.ui.resources.m4StatThreeMonthsRes

/**
 * `stats` and `year_in_music` on this host. The owner holds the Android-parity range math; these
 * functions bind it to the page models and route the row, menu, recap and share actions.
 */
@Composable
internal fun desktopStatisticsPage(
    graph: DesktopSessionGraph,
    menu: DesktopItemMenuState,
    openRoute: (String) -> Unit,
): ArchiveGraphPage {
    val owner = graph.statistics
    LaunchedEffect(owner) { owner.ensureFirstPlay() }
    owner.continuousLabels = listOf(
        stringResource(m4StatOneWeekRes),
        stringResource(m4StatOneMonthRes),
        stringResource(m4StatThreeMonthsRes),
        stringResource(m4StatSixMonthsRes),
        stringResource(m4StatOneYearRes),
        stringResource(m4StatAllRes),
    )
    // Every owner write bumps this counter, so the page recomposes on each completed load.
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(owner) { owner.changes.collect { version += 1 } }
    version // keep the read inside composition
    val ranges = owner.ranges()
    val stats = owner.statistics
    val state = StatisticsUi(
        source = StatisticsSource.LOCAL,
        availableSources = setOf(StatisticsSource.LOCAL),
        period = owner.period,
        ranges = ranges,
        selectedRange = ranges.getOrNull(owner.rangeIndex)?.key,
        statistics = stats,
        coverage = StatisticsCoverage.COMPLETE,
        hasClockTimes = stats?.let { it.byHour.any { s -> s.playTimeMs > 0 } || it.byWeekday.any { s -> s.playTimeMs > 0 } }
            ?: false,
        recapYears = owner.recapYears,
        loading = owner.loading,
        failed = owner.failed,
        uniqueSongs = stats?.songs?.size?.toLong(),
        uniqueArtists = stats?.artists?.size?.toLong(),
        uniqueAlbums = stats?.albums?.size?.toLong(),
    )
    val actions = StatisticsActions(
        back = {}, // the shell supplies the real back; the page never calls it
        source = owner::selectSource,
        period = owner::selectPeriod,
        range = owner::selectRange,
        play = { rank -> stats?.let { playRanked(graph, it, rank) } },
        open = { ref -> if (ref.kind != moe.rukamori.archivetune.shared.domain.repository.ContentKind.SONG &&
            ref.kind != moe.rukamori.archivetune.shared.domain.repository.ContentKind.EPISODE) {
            openRoute(ArchiveRoutes.content(ref))
        } },
        more = { ref ->
            stats?.songs?.firstOrNull { it.item.ref == ref }?.item?.let { item ->
                menu.open(ContentOccurrence("stats:${ref.provider}:${ref.id}", item))
            }
        },
        shuffle = stats?.takeIf { it.songs.size > 1 }?.let { { shuffleRanked(graph, it) } },
        recap = { year -> owner.requestedRecapYear = year; openRoute(RECAP_ROUTE) },
        retry = owner::reload,
    )
    return ArchiveGraphPage.Statistics(
        state = state,
        actions = actions,
        formatDuration = ::desktopStatsDuration,
        formatWeekday = ::desktopWeekday,
    )
}

@Composable
internal fun desktopRecapPage(
    graph: DesktopSessionGraph,
    menu: DesktopItemMenuState,
): ArchiveGraphPage {
    val owner = graph.statistics
    LaunchedEffect(owner) { owner.ensureFirstPlay() }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(owner) { owner.changes.collect { version += 1 } }
    version
    val requested = owner.requestedRecapYear
    var year by remember(requested) {
        mutableStateOf(requested ?: owner.recapYears.firstOrNull() ?: java.time.LocalDate.now().year)
    }
    var recapStats by remember { mutableStateOf<ListeningStatistics?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var shareFailed by remember { mutableStateOf(false) }
    LaunchedEffect(year) {
        loading = true
        failed = false
        try {
            recapStats = graph.history.load(owner.yearWindow(year))
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            recapStats = null
            failed = true
        } finally {
            loading = false
        }
    }
    val state = YearInMusicUi(
        year = year,
        availableYears = owner.recapYears.ifEmpty { listOf(year) },
        statistics = recapStats,
        loading = loading,
        failed = failed,
        sharing = sharing,
        shareFailed = shareFailed,
    )
    val actions = YearInMusicActions(
        back = {},
        year = { year = it },
        retry = {
            failed = false
            graph.scope.launch {
                loading = true
                try {
                    recapStats = graph.history.load(owner.yearWindow(year))
                } finally {
                    loading = false
                }
            }
        },
        share = { card ->
            graph.scope.launch {
                sharing = true
                shareFailed = false
                try {
                    if (!saveRecapCard(card)) shareFailed = true
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    shareFailed = true
                } finally {
                    sharing = false
                }
            }
        },
        songMenu = { ref ->
            recapStats?.songs?.firstOrNull { it.item.ref == ref }?.item?.let { item ->
                menu.open(ContentOccurrence("recap:${ref.provider}:${ref.id}", item))
            }
        },
    )
    return ArchiveGraphPage.YearInMusic(state, actions, ::desktopStatsDuration)
}

/** Plays the tapped ranked song inside the ranked list the screen shows, in its displayed order. */
private fun playRanked(graph: DesktopSessionGraph, stats: ListeningStatistics, rank: ListeningRank) {
    val entries = stats.songs.map { ContentOccurrence("stats:${it.item.ref.provider}:${it.item.ref.id}", it.item) }
    val start = entries.firstOrNull { it.item.ref == rank.item.ref } ?: return
    graph.scope.launch {
        try {
            graph.queue.replace(entries, start.occurrenceId, playWhenReady = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("the ranked list was not played: ${failure.javaClass.simpleName}")
        }
    }
}

/** The shuffle action: the same ranked collection, played from a random start in shuffled order. */
private fun shuffleRanked(graph: DesktopSessionGraph, stats: ListeningStatistics) {
    val entries = stats.songs
        .map { ContentOccurrence("stats:${it.item.ref.provider}:${it.item.ref.id}", it.item) }
        .shuffled()
    val start = entries.firstOrNull() ?: return
    graph.scope.launch {
        try {
            graph.queue.replace(entries, start.occurrenceId, playWhenReady = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("the ranked list was not shuffled: ${failure.javaClass.simpleName}")
        }
    }
}

/** Listening-time text the statistics and recap cards share, e.g. "12h 34m" or "45m 20s". */
internal fun desktopStatsDuration(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

/** The baseline's weekday labels (Sunday first, as ListeningSlot indexes them). */
internal fun desktopWeekday(index: Int): String =
    DayOfWeek.SUNDAY.plus(index.toLong().mod(7L)).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

private const val RECAP_ROUTE = "year_in_music"

/**
 * Writes one recap card PNG where the user picks it. The desktop has no share sheet, so the real
 * equivalent is a native save dialog; a cancelled dialog counts as not shared, not as a failure.
 */
private suspend fun saveRecapCard(share: YearRecapShare): Boolean = withContext(Dispatchers.Default) {
    val target = chooseRecapTarget(share) ?: return@withContext false
    val image: BufferedImage = share.image.toAwtImage()
    // The baseline fits each card to 1080x1920 before sharing.
    val targetWidth = 1080
    val targetHeight = 1920
    val scaled = if (image.width == targetWidth && image.height == targetHeight) {
        image
    } else {
        BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB).also { scaled ->
            val g = scaled.createGraphics()
            g.drawImage(image, 0, 0, targetWidth, targetHeight, null)
            g.dispose()
        }
    }
    ImageIO.write(scaled, "PNG", target)
    true
}

private suspend fun chooseRecapTarget(share: YearRecapShare): File? = suspendCancellableCoroutine { continuation ->
    java.awt.EventQueue.invokeLater {
        val dialog = FileDialog(null as Frame?, share.fileName.removeSuffix(".png"), FileDialog.SAVE)
        dialog.file = share.fileName
        dialog.isVisible = true
        val name = dialog.file
        val directory = dialog.directory
        dialog.dispose()
        continuation.resume(if (name != null && directory != null) File(directory, name) else null) { }
    }
}

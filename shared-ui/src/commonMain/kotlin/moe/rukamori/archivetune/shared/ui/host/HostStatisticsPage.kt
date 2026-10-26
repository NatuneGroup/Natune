package moe.rukamori.archivetune.shared.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.coroutines.cancellation.CancellationException
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ListeningRank
import moe.rukamori.archivetune.shared.domain.repository.ListeningStatistics
import moe.rukamori.archivetune.shared.domain.repository.ListeningWindow
import moe.rukamori.archivetune.shared.domain.repository.StatisticsRepository
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.screens.StatisticsActions
import moe.rukamori.archivetune.shared.ui.screens.StatisticsCoverage
import moe.rukamori.archivetune.shared.ui.screens.StatisticsPeriod
import moe.rukamori.archivetune.shared.ui.screens.StatisticsRangeUi
import moe.rukamori.archivetune.shared.ui.screens.StatisticsSource
import moe.rukamori.archivetune.shared.ui.screens.StatisticsUi
import moe.rukamori.archivetune.shared.ui.screens.YearInMusicActions
import moe.rukamori.archivetune.shared.ui.screens.YearInMusicUi
import moe.rukamori.archivetune.shared.ui.screens.YearRecapShare

private const val DAY_MS = 86_400_000L

/**
 * One selectable statistics window. The bounds are in the same civil-clock space the baseline used
 * (LocalDateTime walls read as epoch values), so a local or remote window covers exactly what the
 * baseline's stats queries were asked for.
 */
data class StatisticsRangeWindow(
    val key: String,
    val fromMs: Long,
    val untilMs: Long,
    /** Civil start (year, month, day) of the calendar chip; null for a fixed continuous window. */
    val startCivil: Triple<Int, Int, Int>?,
    /** Civil end date a label describes (inclusive); null for a fixed continuous window. */
    val endCivil: Triple<Int, Int, Int>?,
    /** The fixed continuous window (0 = 1 week … 5 = all time); null for a calendar chip. */
    val continuousIndex: Int?,
)

/** The windows of one period, newest first, bounded by the oldest recorded listen exactly as the baseline did. */
fun statisticsRanges(
    period: StatisticsPeriod,
    earliestMs: Long?,
    nowMs: Long,
    offsetMsAt: (Long) -> Long,
): List<StatisticsRangeWindow> {
    val civilNow = nowMs + offsetMsAt(nowMs)
    val civilFirst = earliestMs?.let { it + offsetMsAt(it) }
    // The baseline queried civil-clock bounds against civil-clock values; the repository stores real epoch
    // timestamps, so the same instant window is a civil bound shifted back by the offset there.
    fun epoch(civilMs: Long): Long = civilMs - offsetMsAt(civilMs)
    return when (period) {
        StatisticsPeriod.CONTINUOUS -> {
            val (y, m, d) = civilFromDays(civilNow.floorDiv(DAY_MS))
            val dayStart = daysFromCivil(y, m, d)
            val timeOfDay = civilNow - dayStart * DAY_MS
            listOf(
                StatisticsRangeWindow("c0", epoch(civilNow - 7 * DAY_MS), nowMs, null, null, 0),
                monthWindow("c1", y, m, d, 1, timeOfDay, offsetMsAt, nowMs),
                monthWindow("c2", y, m, d, 3, timeOfDay, offsetMsAt, nowMs),
                monthWindow("c3", y, m, d, 6, timeOfDay, offsetMsAt, nowMs),
                yearWindow("c4", y, m, d, 1, timeOfDay, offsetMsAt, nowMs),
                StatisticsRangeWindow("c5", 0L, nowMs, null, null, 5),
            )
        }
        StatisticsPeriod.WEEKS -> {
            val first = civilFirst ?: return emptyList()
            buildList {
                var i = 0
                // A chip exists while its civil week anchor sits after the week the first listen fell in.
                while (civilNow - i * 7 * DAY_MS > first - 7 * DAY_MS) {
                    val from = civilNow - i * 7 * DAY_MS - DAY_MS
                    val until = if (i == 0) nowMs else epoch(civilNow - (i - 1) * 7 * DAY_MS - DAY_MS)
                    val anchor = civilFromDays((civilNow - i * 7 * DAY_MS).floorDiv(DAY_MS))
                    val end = civilFromDays(minOf(civilNow - i * 7 * DAY_MS + 6 * DAY_MS, civilNow).floorDiv(DAY_MS))
                    add(StatisticsRangeWindow("w$i", epoch(from), until, anchor, end, null))
                    i++
                }
            }
        }
        StatisticsPeriod.MONTHS -> {
            val first = civilFirst ?: return emptyList()
            val (fy, fm) = civilFromDays(first.floorDiv(DAY_MS)).let { it.first to it.second }
            val (cy, cm, cd) = civilFromDays(civilNow.floorDiv(DAY_MS))
            val timeOfDay = civilNow - daysFromCivil(cy, cm, cd) * DAY_MS
            buildList {
                var i = 0
                while (true) {
                    val (sy, sm) = shiftMonths(cy, cm, -i)
                    // The month chip exists while the end of that month is after the first listen's month start.
                    val endDay = daysFromCivil(if (sm == 12) sy + 1 else sy, if (sm == 12) 1 else sm + 1, 1) - 1
                    if (endDay * DAY_MS <= daysFromCivil(fy, fm, 1) * DAY_MS) break
                    val from = daysFromCivil(sy, sm, 1) * DAY_MS + timeOfDay
                    val until = if (i == 0) nowMs else epoch(daysFromCivil(if (sm == 12) sy + 1 else sy, if (sm == 12) 1 else sm + 1, 1) * DAY_MS + timeOfDay)
                    add(StatisticsRangeWindow("m$i", epoch(from), until, Triple(sy, sm, 1), civilFromDays(endDay), null))
                    i++
                }
            }
        }
        StatisticsPeriod.YEARS -> {
            val first = civilFirst ?: return emptyList()
            val (fy, _, _) = civilFromDays(first.floorDiv(DAY_MS))
            val (cy, cm, cd) = civilFromDays(civilNow.floorDiv(DAY_MS))
            val timeOfDay = civilNow - daysFromCivil(cy, cm, cd) * DAY_MS
            (0..cy - fy).map { i ->
                val year = cy - i
                val from = daysFromCivil(year, 1, 1) * DAY_MS + timeOfDay
                val until = if (i == 0) nowMs else epoch(daysFromCivil(year + 1, 1, 1) * DAY_MS + timeOfDay)
                StatisticsRangeWindow("y$i", epoch(from), until, Triple(year, 1, 1), Triple(year, 12, 31), null)
            }
        }
    }
}

/** The years a recap picker offers: from the year of the first listen to the current year, newest first. */
fun statisticsRecapYears(earliestMs: Long?, nowMs: Long, offsetMsAt: (Long) -> Long): List<Int> {
    val first = earliestMs ?: return emptyList()
    val (fy, _, _) = civilFromDays((first + offsetMsAt(first)).floorDiv(DAY_MS))
    val (cy, _, _) = civilFromDays((nowMs + offsetMsAt(nowMs)).floorDiv(DAY_MS))
    return (cy downTo fy).toList()
}

/**
 * The year window a recap covers: the full local year, `of(year,1,1,0,0)` to `of(year,12,31,23,59,59)`
 * in the baseline's civil clock, shifted onto the epoch space the repository stores.
 */
fun statisticsYearWindow(year: Int, offsetMsAt: (Long) -> Long): ListeningWindow {
    val from = daysFromCivil(year, 1, 1) * DAY_MS
    val until = daysFromCivil(year, 12, 31) * DAY_MS + (DAY_MS - 1000L)
    return ListeningWindow(from - offsetMsAt(from), until - offsetMsAt(until))
}

/**
 * The shared Statistics page over the local listening repository. Selection, ranges and the recap-year list match the
 * baseline StatsViewModel: continuous chips are the six fixed windows, calendar chips stop at the first recorded
 * listen, and a source that cannot answer is never offered. [rangeLabel] formats the chip text on the host.
 */
@Composable
fun hostStatisticsPage(
    statistics: StatisticsRepository,
    earliestListenMs: suspend () -> Long?,
    play: (ListeningRank) -> Unit,
    open: (ContentRef) -> Unit,
    recap: (Int) -> Unit,
    more: ((ContentRef) -> Unit)?,
    nowMs: () -> Long,
    offsetMsAt: (Long) -> Long,
    rangeLabel: (StatisticsRangeWindow) -> String,
    formatDuration: (Long) -> String,
    formatWeekday: (Int) -> String,
    report: (String) -> Unit,
): ArchiveGraphPage {
    var source by remember { mutableStateOf(StatisticsSource.LOCAL) }
    var period by remember { mutableStateOf(StatisticsPeriod.CONTINUOUS) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var earliest by remember { mutableStateOf<Long?>(null) }
    var earliestLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        earliest = try {
            earliestListenMs()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            report("statistics range bounds could not be read")
            null
        }
        earliestLoaded = true
    }
    val ranges = remember(period, earliest, earliestLoaded) {
        statisticsRanges(period, if (earliestLoaded) earliest else null, nowMs(), offsetMsAt)
    }
    val selected = ranges.firstOrNull { it.key == selectedKey } ?: ranges.firstOrNull()
    var stats by remember { mutableStateOf<ListeningStatistics?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(selected?.key, period, attempt) {
        val window = selected
        if (window == null) {
            stats = null; loading = false; failed = false
            return@LaunchedEffect
        }
        loading = true; failed = false
        stats = try {
            statistics.load(ListeningWindow(window.fromMs, window.untilMs.coerceAtLeast(window.fromMs + 1)))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            report("statistics could not be loaded")
            failed = true
            null
        }
        loading = false
    }
    val recapYears = remember(earliest, earliestLoaded) {
        if (earliestLoaded) statisticsRecapYears(earliest, nowMs(), offsetMsAt) else emptyList()
    }
    val state = StatisticsUi(
        source = source,
        availableSources = setOf(StatisticsSource.LOCAL),
        period = period,
        ranges = ranges.map { StatisticsRangeUi(it.key, rangeLabel(it), ListeningWindow(it.fromMs, it.untilMs.coerceAtLeast(it.fromMs + 1))) },
        selectedRange = selected?.key,
        statistics = stats,
        coverage = StatisticsCoverage.COMPLETE,
        hasClockTimes = true,
        recapYears = recapYears,
        loading = loading,
        failed = failed,
        uniqueSongs = stats?.songs?.size?.toLong(),
        uniqueArtists = stats?.artists?.size?.toLong(),
        uniqueAlbums = stats?.albums?.size?.toLong(),
    )
    val actions = StatisticsActions(
        back = {},
        source = { source = it },
        period = { period = it; selectedKey = null },
        range = { selectedKey = it.key },
        play = play,
        open = open,
        more = more,
        shuffle = null,
        recap = recap,
        retry = { attempt += 1 },
    )
    return ArchiveGraphPage.Statistics(state, actions, formatDuration, formatWeekday)
}

/**
 * The shared Year-in-Music page over the local listening repository: the statistics of the full local year, the year
 * list the picker offers, and a share action only where the host can really write and share the card.
 */
@Composable
fun hostYearInMusicPage(
    statistics: StatisticsRepository,
    earliestListenMs: suspend () -> Long?,
    yearArg: Int?,
    share: ((YearRecapShare) -> Unit)?,
    songMenu: ((ContentRef) -> Unit)?,
    openRecap: (Int) -> Unit,
    nowMs: () -> Long,
    offsetMsAt: (Long) -> Long,
    formatDuration: (Long) -> String,
    report: (String) -> Unit,
): ArchiveGraphPage {
    val civilNow = nowMs() + offsetMsAt(nowMs())
    val currentYear = civilFromDays(civilNow.floorDiv(DAY_MS)).first
    var attempt by remember { mutableIntStateOf(0) }
    var earliest by remember { mutableStateOf<Long?>(null) }
    var earliestLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        earliest = try {
            earliestListenMs()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            report("year in music bounds could not be read")
            null
        }
        earliestLoaded = true
    }
    val availableYears = remember(earliest, earliestLoaded) {
        if (earliestLoaded) statisticsRecapYears(earliest, nowMs(), offsetMsAt) else emptyList()
    }
    val year = yearArg ?: availableYears.firstOrNull() ?: currentYear
    var stats by remember { mutableStateOf<ListeningStatistics?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(year, attempt) {
        loading = true; failed = false
        stats = try {
            statistics.load(statisticsYearWindow(year, offsetMsAt))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            report("year in music could not be loaded")
            failed = true
            null
        }
        loading = false
    }
    val state = YearInMusicUi(
        year = year,
        availableYears = availableYears,
        statistics = stats,
        loading = loading,
        failed = failed,
    )
    val actions = YearInMusicActions(
        back = {},
        year = openRecap,
        retry = { attempt += 1 },
        share = { card -> share?.invoke(card) ?: report("year in music share is not connected on this host") },
        songMenu = songMenu,
    )
    return ArchiveGraphPage.YearInMusic(state, actions, formatDuration)
}

/** [dayMillis] is the civil day index; returns (year, month, day). Howard Hinnant's civil calendar conversion. */
fun civilFromDays(z: Long): Triple<Int, Int, Int> {
    val zz = z + 719468
    val era = zz.floorDiv(146097)
    val doe = zz - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return Triple((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
}

fun daysFromCivil(yIn: Int, m: Int, d: Int): Long {
    val y = if (m <= 2) yIn - 1L else yIn.toLong()
    val era = y.floorDiv(400)
    val yoe = y - era * 400
    val mp = (m + 9) % 12
    val doy = (153 * mp + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

private fun shiftMonths(year: Int, month: Int, by: Int): Pair<Int, Int> {
    val total = year * 12 + (month - 1) + by
    return total.floorDiv(12) to (total % 12 + 1)
}

private fun monthWindow(key: String, y: Int, m: Int, d: Int, monthsBack: Int, timeOfDay: Long,
    offsetMsAt: (Long) -> Long, nowMs: Long): StatisticsRangeWindow {
    val (sy, sm) = shiftMonths(y, m, -monthsBack)
    val day = d.coerceAtMost(daysInMonth(sy, sm))
    val from = daysFromCivil(sy, sm, day) * DAY_MS + timeOfDay
    return StatisticsRangeWindow(key, from - offsetMsAt(from), nowMs, null, null, monthsBack)
}

private fun yearWindow(key: String, y: Int, m: Int, d: Int, yearsBack: Int, timeOfDay: Long,
    offsetMsAt: (Long) -> Long, nowMs: Long): StatisticsRangeWindow {
    val sy = y - yearsBack
    val day = d.coerceAtMost(daysInMonth(sy, m))
    val from = daysFromCivil(sy, m, day) * DAY_MS + timeOfDay
    return StatisticsRangeWindow(key, from - offsetMsAt(from), nowMs, null, null, 4)
}

private fun daysInMonth(year: Int, month: Int): Int =
    (daysFromCivil(if (month == 12) year + 1 else year, if (month == 12) 1 else month + 1, 1) - daysFromCivil(year, month, 1)).toInt()

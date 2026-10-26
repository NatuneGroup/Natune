package moe.rukamori.archivetune.desktop

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.HistoryOrigin
import moe.rukamori.archivetune.shared.domain.repository.HistoryRepository
import moe.rukamori.archivetune.shared.domain.repository.ListeningStatistics
import moe.rukamori.archivetune.shared.domain.repository.ListeningWindow
import moe.rukamori.archivetune.shared.domain.repository.StatisticsRepository
import moe.rukamori.archivetune.shared.ui.screens.StatisticsCoverage
import moe.rukamori.archivetune.shared.ui.screens.StatisticsPeriod
import moe.rukamori.archivetune.shared.ui.screens.StatisticsRangeUi
import moe.rukamori.archivetune.shared.ui.screens.StatisticsSource

/**
 * The statistics owner behind `stats` and `year_in_music` on this host. It ports the baseline's
 * `OptionStats`/`StatPeriod` windows exactly: every bound is computed on the UTC clock, and every window
 * is the half-open `[from, until)` pair the same two functions produced on Android.
 *
 * Only the LOCAL source exists here: the desktop history is the local listen database, so coverage is
 * always [StatisticsCoverage.COMPLETE] (`m4_stats_loaded`/`m4_stats_recent` describe remote feeds this
 * host has none of). Recap years are the UTC calendar years that hold at least one listen.
 */
internal class DesktopStatisticsOwner(
    private val statisticsRepository: StatisticsRepository,
    private val history: HistoryRepository,
    private val scope: CoroutineScope,
    private val nowUtc: () -> LocalDateTime = { LocalDateTime.now(ZoneOffset.UTC) },
) {
    @Volatile var period = StatisticsPeriod.CONTINUOUS
        private set
    @Volatile var rangeIndex = 0
        private set
    @Volatile var source = StatisticsSource.LOCAL
        private set
    @Volatile var statistics: ListeningStatistics? = null
        private set
    @Volatile var loading = true
        private set
    @Volatile var failed = false
        private set
    /** Timestamp of the earliest listen, or null when history is empty or not yet walked. */
    @Volatile var earliestEventMs: Long? = null
        private set
    /** UTC years that hold at least one listen, newest first. */
    @Volatile var recapYears: List<Int> = emptyList()
        private set
    /** The year the Statistics page's recap action asked for; the recap page opens on it. */
    @Volatile var requestedRecapYear: Int? = null

    /**
     * The six CONTINUOUS chip labels, in StatPeriod order (1 week, 1 month, 3 months, 6 months,
     * 1 year, All). The page writes them from the compose resources each composition, so no UI
     * string is owned by this owner.
     */
    @Volatile var continuousLabels: List<String> = emptyList()

    /** Emits after every published state change so the page recomposes on real data only. */
    private val changeFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val changes: SharedFlow<Unit> get() = changeFlow

    private fun notifyChanged() { changeFlow.tryEmit(Unit) }

    private var loadJob: Job? = null
    private var firstPlayJob: Job? = null

    /** The range chips of [option] for the earliest known play, as the baseline builds them. */
    fun ranges(option: StatisticsPeriod = period): List<StatisticsRangeUi> {
        val now = nowUtc()
        return when (option) {
            StatisticsPeriod.CONTINUOUS -> CONTINUOUS_PERIODS.mapIndexed { index, _ ->
                StatisticsRangeUi(index.toString(), continuousLabels.getOrElse(index) { "${index}" },
                    continuousWindow(index, now))
            }
            StatisticsPeriod.WEEKS -> weeklyRanges(earliestEventMs, now)
            StatisticsPeriod.MONTHS -> monthlyRanges(earliestEventMs, now)
            StatisticsPeriod.YEARS -> yearlyRanges(earliestEventMs, now)
        }
    }

    fun selectPeriod(option: StatisticsPeriod) {
        if (period == option) return
        period = option
        rangeIndex = 0
        notifyChanged()
        reload()
    }

    fun selectRange(range: StatisticsRangeUi) {
        val index = range.key.toIntOrNull() ?: return
        if (rangeIndex == index) return
        rangeIndex = index
        notifyChanged()
        reload()
    }

    /** No remote source exists on this host; a foreign selection is ignored rather than faked. */
    fun selectSource(next: StatisticsSource) {
        if (next != StatisticsSource.LOCAL) return
        source = next
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = scope.launch {
            loading = true
            failed = false
            notifyChanged()
            try {
                val window = ranges().getOrNull(rangeIndex)?.window
                statistics = if (window != null) statisticsRepository.load(window) else null
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                failed = true
                statistics = null
            } finally {
                loading = false
                notifyChanged()
            }
        }
    }

    /** Walks the local history once to find the first listen and the recap years, then reloads. */
    fun ensureFirstPlay() {
        if (firstPlayJob != null) return
        firstPlayJob = scope.launch {
            try {
                val years = linkedSetOf<Int>()
                var earliest: Long? = null
                var continuation: String? = null
                var pages = 0
                do {
                    val page = history.page(HistoryOrigin.LOCAL, continuation)
                    page.events.forEach { event ->
                        years += LocalDateTime.ofEpochSecond(event.timestampMs / 1_000L, 0, ZoneOffset.UTC).year
                        earliest = minOf(earliest ?: Long.MAX_VALUE, event.timestampMs)
                    }
                    continuation = page.continuation
                } while (continuation != null && ++pages < MAX_HISTORY_PAGES)
                earliestEventMs = earliest
                recapYears = years.sortedDescending()
                notifyChanged()
                reload()
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
    }

    /** The window one year of listening covers, the same bound the YEARS chips produce. */
    fun yearWindow(year: Int): ListeningWindow = ListeningWindow(
        LocalDateTime.of(year, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        LocalDateTime.of(year + 1, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
    )

    /** [statToPeriod] of the baseline: the inclusive lower bound of chip [index] of [option]. */
    private fun statToPeriod(option: StatisticsPeriod, index: Int, now: LocalDateTime): Long = when (option) {
        StatisticsPeriod.WEEKS -> now.minusWeeks(index.toLong()).minusDays(1)
        StatisticsPeriod.MONTHS -> now.withDayOfMonth(1).minusMonths(index.toLong())
        StatisticsPeriod.YEARS -> now.withDayOfMonth(1).withMonth(1).minusYears(index.toLong())
        StatisticsPeriod.CONTINUOUS ->
            // The baseline clamps an out-of-range chip to index 0.
            now.let { CONTINUOUS_PERIODS[if (index > CONTINUOUS_PERIODS.lastIndex) 0 else index] }
                .let { period -> continuousFrom(period, now) }
    }.toInstant(ZoneOffset.UTC).toEpochMilli()

    /** [statsUntilPeriod] of the baseline: the exclusive upper bound of chip [index] of [option]. */
    private fun statsUntilPeriod(option: StatisticsPeriod, index: Int, now: LocalDateTime): Long =
        if (option == StatisticsPeriod.CONTINUOUS || index == 0) {
            now.toInstant(ZoneOffset.UTC).toEpochMilli()
        } else {
            statToPeriod(option, index - 1, now)
        }

    private fun continuousFrom(period: ContinuousPeriod, now: LocalDateTime): LocalDateTime = when (period) {
        ContinuousPeriod.WEEK_1 -> now.minusWeeks(1)
        ContinuousPeriod.MONTH_1 -> now.minusMonths(1)
        ContinuousPeriod.MONTH_3 -> now.minusMonths(3)
        ContinuousPeriod.MONTH_6 -> now.minusMonths(6)
        ContinuousPeriod.YEAR_1 -> now.minusMonths(12)
        ContinuousPeriod.ALL -> EPOCH
    }

    private fun continuousWindow(index: Int, now: LocalDateTime): ListeningWindow = ListeningWindow(
        statToPeriod(StatisticsPeriod.CONTINUOUS, index, now),
        statsUntilPeriod(StatisticsPeriod.CONTINUOUS, index, now),
    )

    private fun window(option: StatisticsPeriod, index: Int, now: LocalDateTime): ListeningWindow =
        ListeningWindow(statToPeriod(option, index, now), statsUntilPeriod(option, index, now))

    /** The baseline `weeklyRangeChips`: week windows newest first, back to the week of the first play. */
    private fun weeklyRanges(firstPlayMs: Long?, now: LocalDateTime): List<StatisticsRangeUi> {
        val first = firstPlayMs?.let { LocalDateTime.ofEpochSecond(it / 1_000L, 0, ZoneOffset.UTC) } ?: return emptyList()
        val day = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)
        return generateSequence(now) { it.minusWeeks(1) }
            .takeWhile { it.isAfter(first.minusWeeks(1)) }
            .mapIndexed { index, date ->
                val endDate = date.plusWeeks(1).minusDays(1).coerceAtMost(now)
                val label = when {
                    date.year != now.year -> "${day.format(date)}, ${date.year} - ${day.format(endDate)}, ${endDate.year}"
                    date.month != endDate.month -> "${day.format(date)} - ${day.format(endDate)}"
                    else -> "${date.dayOfMonth} - ${day.format(endDate)}"
                }
                StatisticsRangeUi(index.toString(), label, window(StatisticsPeriod.WEEKS, index, now))
            }.toList()
    }

    /** The baseline `monthlyRangeChips`: calendar months newest first, back to the month of the first play. */
    private fun monthlyRanges(firstPlayMs: Long?, now: LocalDateTime): List<StatisticsRangeUi> {
        val first = firstPlayMs?.let { LocalDateTime.ofEpochSecond(it / 1_000L, 0, ZoneOffset.UTC) } ?: return emptyList()
        val month = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
        return generateSequence(now.plusMonths(1).withDayOfMonth(1).minusDays(1)) { it.minusMonths(1) }
            .takeWhile { it.isAfter(first.withDayOfMonth(1)) }
            .mapIndexed { index, date ->
                val label = if (date.year != now.year) "${month.format(date)} ${date.year}" else month.format(date)
                StatisticsRangeUi(index.toString(), label, window(StatisticsPeriod.MONTHS, index, now))
            }.toList()
    }

    /** The baseline `yearlyRangeChips`: calendar years newest first, back to the year of the first play. */
    private fun yearlyRanges(firstPlayMs: Long?, now: LocalDateTime): List<StatisticsRangeUi> {
        val first = firstPlayMs?.let { LocalDateTime.ofEpochSecond(it / 1_000L, 0, ZoneOffset.UTC) } ?: return emptyList()
        return generateSequence(now.plusYears(1).withDayOfYear(1).minusDays(1)) { it.minusYears(1) }
            .takeWhile { it.isAfter(first) }
            .mapIndexed { index, date ->
                StatisticsRangeUi(index.toString(), "${date.year}", window(StatisticsPeriod.YEARS, index, now))
            }.toList()
    }

    private enum class ContinuousPeriod { WEEK_1, MONTH_1, MONTH_3, MONTH_6, YEAR_1, ALL }

    private companion object {
        private val EPOCH: LocalDateTime = LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC)
        private val CONTINUOUS_PERIODS = listOf(
            ContinuousPeriod.WEEK_1, ContinuousPeriod.MONTH_1, ContinuousPeriod.MONTH_3,
            ContinuousPeriod.MONTH_6, ContinuousPeriod.YEAR_1, ContinuousPeriod.ALL,
        )
        /** The same page bound [moe.rukamori.archivetune.shared.ui.host.LocalHistoryPager] uses. */
        private const val MAX_HISTORY_PAGES = 40
    }
}


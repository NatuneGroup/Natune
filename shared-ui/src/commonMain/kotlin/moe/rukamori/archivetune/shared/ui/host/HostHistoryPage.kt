package moe.rukamori.archivetune.shared.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.HistoryOrigin
import moe.rukamori.archivetune.shared.domain.repository.ListeningEvent
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m4_last_week
import moe.rukamori.archivetune.shared.ui.resources.m4_this_week
import moe.rukamori.archivetune.shared.ui.resources.m4_today
import moe.rukamori.archivetune.shared.ui.resources.m4_yesterday
import moe.rukamori.archivetune.shared.ui.screens.HistoryActions
import moe.rukamori.archivetune.shared.ui.screens.HistoryEventUi
import moe.rukamori.archivetune.shared.ui.screens.HistorySectionUi
import moe.rukamori.archivetune.shared.ui.screens.HistorySource
import moe.rukamori.archivetune.shared.ui.screens.HistoryUi
import org.jetbrains.compose.resources.stringResource

/**
 * The local listening history, grouped as the baseline groups it: Today, Yesterday, This week, Last week, then one
 * section per month. Only the local origin is offered, because no account history source is connected to this page.
 * [offsetMsAt] is the device zone's offset at an instant, so a listen lands on the user's calendar day. [formatTime]
 * writes the time line of a row. [more] opens the host's row menu.
 */
@Composable
fun hostHistoryPage(
    owners: LibraryPageOwners,
    offsetMsAt: (epochMs: Long) -> Long,
    nowMs: () -> Long,
    formatTime: (epochMs: Long) -> String,
    more: (ContentOccurrence) -> Unit,
): ArchiveGraphPage {
    val scope = rememberCoroutineScope()
    val pager = remember(owners) { LocalHistoryPager(owners, scope, offsetMsAt) }
    LaunchedEffect(pager) { pager.reload() }
    val now = nowMs()
    val today = localDay(now, offsetMsAt)
    pager.today = today
    val labels = mapOf(
        TODAY to stringResource(Res.string.m4_today),
        YESTERDAY to stringResource(Res.string.m4_yesterday),
        THIS_WEEK to stringResource(Res.string.m4_this_week),
        LAST_WEEK to stringResource(Res.string.m4_last_week),
    )
    val sections = pager.events.groupConsecutiveBy { pager.keyOf(it, today) }.map { (key, events) ->
        HistorySectionUi(
            key = key,
            dateLabel = labels[key] ?: key.removePrefix(MONTH_PREFIX).replace('-', '/'),
            events = events.map { event ->
                // A queue occurrence can be listened to more than once, so each row takes its listen's own identity.
                HistoryEventUi(pager.numericId(event.id), event.historyRow(), formatTime(event.timestampMs))
            },
        )
    }
    val state = HistoryUi(
        source = HistorySource.LOCAL,
        availableSources = setOf(HistorySource.LOCAL),
        sections = sections,
        query = pager.query,
        selectedEventIds = pager.selected,
        loading = pager.loading,
        failed = pager.failed,
        canLoadMore = pager.continuation != null,
        busy = pager.busy,
    )
    val actions = HistoryActions(
        source = {},
        query = { pager.query = it },
        // The host supplies back for this page.
        back = {},
        play = { event, sectionKey -> pager.play(event, sectionKey) },
        more = more,
        toggleSelection = { id -> pager.selected = if (id in pager.selected) pager.selected - id else pager.selected + id },
        selectAllVisible = { ids -> pager.selected = pager.selected + ids },
        clearSelection = { pager.selected = emptySet() },
        removeEvents = pager::remove,
        retry = pager::reload,
        loadMore = pager::loadMore,
    )
    return ArchiveGraphPage.History(state, actions)
}

/**
 * Pages the local history. Every read and write runs under one lock, and a reload starts a new generation, so a page
 * from an older read never lands on a newer list.
 */
private class LocalHistoryPager(
    private val owners: LibraryPageOwners,
    private val scope: CoroutineScope,
    private val offsetMsAt: (epochMs: Long) -> Long,
) {
    var events by mutableStateOf<List<ListeningEvent>>(emptyList())
        private set
    var continuation by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var selected by mutableStateOf<Set<Long>>(emptySet())
    var query by mutableStateOf("")
    var today = 0L

    private val lock = Mutex()
    private var generation = 0
    private val numericIds = HashMap<String, Long>()
    private val eventIds = HashMap<Long, String>()

    /** The screen selects rows by number, while the store keys each listen by text. The mapping holds for the page. */
    fun numericId(id: String): Long = numericIds.getOrPut(id) { (numericIds.size + 1L).also { eventIds[it] = id } }

    fun keyOf(event: ListeningEvent, today: Long): String = historySectionKey(localDay(event.timestampMs, offsetMsAt), today)

    fun reload() {
        val gen = ++generation
        loading = true
        failed = false
        scope.launch {
            lock.withLock {
                read(gen) {
                    val page = owners.history.page(HistoryOrigin.LOCAL)
                    if (gen != generation) return@read
                    events = page.events
                    continuation = page.continuation
                    val held = page.events.mapNotNullTo(HashSet()) { numericIds[it.id] }
                    selected = selected intersect held
                }
            }
        }
    }

    fun loadMore() {
        if (continuation == null || loading) return
        val gen = generation
        loading = true
        scope.launch { lock.withLock { read(gen) { fetchNext(gen) } } }
    }

    /**
     * Plays the section the row belongs to, from that row, as the baseline does. The section is read to its end first,
     * so the queue is never a loaded prefix; a section longer than the read bound is refused with one line.
     */
    fun play(event: HistoryEventUi, sectionKey: String) {
        val gen = generation
        val day = today
        val filter = query.trim()
        scope.launch {
            val section = lock.withLock {
                try {
                    var pages = 0
                    while (continuation != null && events.lastOrNull()?.let { keyOf(it, day) == sectionKey } == true &&
                        pages < MAX_SECTION_PAGES
                    ) {
                        if (!fetchNext(gen)) break
                        pages++
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    owners.report("history section not read: ${failure::class.simpleName}")
                    return@withLock null
                }
                if (gen != generation) return@withLock null
                if (continuation != null && events.lastOrNull()?.let { keyOf(it, day) == sectionKey } == true) {
                    owners.report("history section is too long to play completely")
                    return@withLock null
                }
                events.filter { keyOf(it, day) == sectionKey && it.matches(filter) }
            } ?: return@launch
            val entries = section.map { it.id to it.item.queuedEntry() }
            val listenId = eventIds[event.eventId ?: return@launch]
            val start = entries.firstOrNull { it.first == listenId }?.second
                ?: return@launch owners.report("the history section does not hold this listen")
            owners.queue.replace(entries.map { it.second }, start.occurrenceId, playWhenReady = true)
        }
    }

    fun remove(ids: Set<Long>) {
        val listens = ids.mapNotNullTo(HashSet()) { eventIds[it] }
        if (listens.isEmpty() || busy) return
        busy = true
        scope.launch {
            try {
                lock.withLock {
                    owners.history.remove(HistoryOrigin.LOCAL, listens)
                    // The continuation is keyed on the oldest listen's time, so dropping rows keeps it valid.
                    events = events.filterNot { it.id in listens }
                    selected = selected - ids
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                owners.report("history removal failed: ${failure::class.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    private suspend fun fetchNext(gen: Int): Boolean {
        val token = continuation ?: return false
        val page = owners.history.page(HistoryOrigin.LOCAL, token)
        if (gen != generation) return false
        val held = events.mapTo(HashSet()) { it.id }
        events = events + page.events.filterNot { it.id in held }
        continuation = page.continuation
        return true
    }

    private suspend fun read(gen: Int, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (gen == generation) failed = true
            owners.report("history not read: ${failure::class.simpleName}")
        } finally {
            if (gen == generation) loading = false
        }
    }
}

private fun ListeningEvent.historyRow(): ContentOccurrence = ContentOccurrence("history:$id", item.item, item.setVideoId)

private fun ListeningEvent.matches(query: String): Boolean =
    query.isEmpty() || item.item.title.contains(query, true) || item.item.subtitle.contains(query, true)

private const val TODAY = "today"
private const val YESTERDAY = "yesterday"
private const val THIS_WEEK = "this_week"
private const val LAST_WEEK = "last_week"
private const val MONTH_PREFIX = "month-"
private const val DAY_MS = 86_400_000L

/** Pages of 50 listens the player reads to finish one section; past that the section is refused, not cut. */
private const val MAX_SECTION_PAGES = 40

private fun localDay(epochMs: Long, offsetMsAt: (Long) -> Long): Long = (epochMs + offsetMsAt(epochMs)).floorDiv(DAY_MS)

/** The baseline's DateAgo buckets. Weeks start on Monday; 1970-01-01, epoch day 0, was a Thursday. */
internal fun historySectionKey(day: Long, today: Long): String {
    val thisMonday = today - (today + 3).mod(7L)
    return when {
        today - day <= 0 -> TODAY
        today - day == 1L -> YESTERDAY
        day >= thisMonday -> THIS_WEEK
        day >= thisMonday - 7 -> LAST_WEEK
        else -> civilYearMonth(day).let { (year, month) -> "$MONTH_PREFIX$year-${month.toString().padStart(2, '0')}" }
    }
}

/** The proleptic Gregorian year and month of an epoch day (Howard Hinnant's civil_from_days). */
private fun civilYearMonth(epochDay: Long): Pair<Long, Int> {
    val z = epochDay + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    return (yoe + era * 400 + if (month <= 2) 1 else 0) to month
}

private inline fun <T, K> List<T>.groupConsecutiveBy(key: (T) -> K): List<Pair<K, List<T>>> {
    val groups = ArrayList<Pair<K, MutableList<T>>>()
    for (element in this) {
        val k = key(element)
        val last = groups.lastOrNull()
        if (last != null && last.first == k) last.second.add(element) else groups.add(k to mutableListOf(element))
    }
    return groups
}

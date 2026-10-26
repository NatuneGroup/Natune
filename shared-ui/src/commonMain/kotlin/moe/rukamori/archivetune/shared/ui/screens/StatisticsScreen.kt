package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

enum class StatisticsSource { LOCAL, YOUTUBE, SPOTIFY }
enum class StatisticsPeriod { CONTINUOUS, WEEKS, MONTHS, YEARS }
enum class StatisticsCoverage { COMPLETE, LOADED_HISTORY, RECENT_HISTORY }
/** The owner computes these half-open windows using the SAME timezone/calendar as its queries. */
data class StatisticsRangeUi(val key: String, val label: String, val window: ListeningWindow)
data class StatisticsUi(
    val source: StatisticsSource,
    val availableSources: Set<StatisticsSource>,
    val period: StatisticsPeriod,
    val ranges: List<StatisticsRangeUi>,
    val selectedRange: String?,
    val statistics: ListeningStatistics?,
    val coverage: StatisticsCoverage,
    val hasClockTimes: Boolean,
    val recapYears: List<Int>,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val rateLimited: Boolean = false,
    /** Actual aggregate counts; truncated ranked rows cannot prove unique totals. */
    val uniqueSongs: Long? = null,
    val uniqueArtists: Long? = null,
    val uniqueAlbums: Long? = null,
)
class StatisticsActions(
    val back: () -> Unit,
    val source: (StatisticsSource) -> Unit,
    val period: (StatisticsPeriod) -> Unit,
    val range: (StatisticsRangeUi) -> Unit,
    val play: (ListeningRank) -> Unit,
    val open: (ContentRef) -> Unit,
    val more: ((ContentRef) -> Unit)?,
    /** Only publish this operation when the queue owner can complete the real ranked collection. */
    val shuffle: (() -> Unit)?,
    val recap: (Int) -> Unit,
    val retry: () -> Unit,
)

@Composable
fun StatisticsScreen(
    state: StatisticsUi,
    actions: StatisticsActions,
    artwork: ContentArtwork,
    formatDuration: (Long) -> String,
    formatWeekday: (Int) -> String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var yearPicker by rememberSaveable { mutableStateOf(false) }
    val stats = state.statistics
    val sources = StatisticsSource.entries.filter { it == StatisticsSource.LOCAL || it in state.availableSources }
    LaunchedEffect(state.source, sources) { if (state.source !in sources) actions.source(StatisticsSource.LOCAL) }
    Column(modifier.fillMaxSize()) {
        ManagementHeader(stringResource(Res.string.m4_statistics), actions.back)
        LazyColumn(Modifier.weight(1f), contentPadding = contentPadding) {
            item("controls") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sources, key = { it.name }) { source ->
                            FilterChip(state.source == source, { actions.source(source) }, label = {
                                Text(stringResource(when (source) {
                                    StatisticsSource.LOCAL -> Res.string.m4_local_history
                                    StatisticsSource.YOUTUBE -> Res.string.m4_youtube
                                    StatisticsSource.SPOTIFY -> Res.string.m4_spotify
                                }))
                            })
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(StatisticsPeriod.entries, key = { it.name }) { period ->
                            FilterChip(state.period == period, { actions.period(period) }, label = {
                                Text(stringResource(when (period) {
                                    StatisticsPeriod.CONTINUOUS -> Res.string.m4_continuous
                                    StatisticsPeriod.WEEKS -> Res.string.m4_weeks
                                    StatisticsPeriod.MONTHS -> Res.string.m4_months
                                    StatisticsPeriod.YEARS -> Res.string.m4_years
                                }))
                            })
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.ranges, key = { it.key }) { range ->
                            FilterChip(state.selectedRange == range.key, { actions.range(range) }, label = { Text(range.label) })
                        }
                    }
                    Text(stringResource(when (state.coverage) {
                        StatisticsCoverage.COMPLETE -> Res.string.m4_stats_complete
                        StatisticsCoverage.LOADED_HISTORY -> Res.string.m4_stats_loaded
                        StatisticsCoverage.RECENT_HISTORY -> Res.string.m4_stats_recent
                    }), style = MaterialTheme.typography.bodySmall)
                    TextButton({ yearPicker = true }, enabled = state.recapYears.isNotEmpty()) {
                        Text(stringResource(Res.string.m4_year_in_music))
                    }
                    if (state.rateLimited) Text(stringResource(Res.string.m4_stats_rate_limited), color = MaterialTheme.colorScheme.error)
                    LoadFeedback(state.loading, state.failed, stats == null || stats.playCount == 0L, actions.retry)
                }
            }
            if (stats != null && !state.loading && !state.failed) {
                item("summary") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(Res.string.m4_stats_plays, stats.playCount), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(Res.string.m4_stats_listening, formatDuration(stats.playTimeMs)))
                        state.uniqueSongs?.let { Text(stringResource(Res.string.m4_stats_unique_songs, it)) }
                        state.uniqueArtists?.let { Text(stringResource(Res.string.m4_stats_unique_artists, it)) }
                        state.uniqueAlbums?.let { Text(stringResource(Res.string.m4_stats_unique_albums, it)) }
                    }
                }
                item("artist-distribution") {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(Res.string.m4_artist_distribution), style = MaterialTheme.typography.titleLarge)
                        val denominator = stats.playTimeMs.takeIf { it > 0 }?.toDouble()
                        stats.artists.take(5).forEach { rank ->
                            Text(rank.item.title)
                            if (denominator != null) LinearProgressIndicator(
                                progress = { (rank.playTimeMs.toDouble() / denominator).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth())
                            Text(formatDuration(rank.playTimeMs), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item("patterns") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(Res.string.m4_listening_patterns), style = MaterialTheme.typography.titleLarge)
                        SlotChart(stats.byWeekday, { formatWeekday(it) }, formatDuration)
                        if (state.hasClockTimes) SlotChart(stats.byHour, { stringResource(Res.string.m4_stats_hour, it) }, formatDuration)
                        else Text(stringResource(Res.string.m4_stats_no_clock_times), style = MaterialTheme.typography.bodySmall)
                    }
                }
                item("songs-title") {
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(stringResource(Res.string.m4_top_songs), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        actions.shuffle?.let { shuffle ->
                            TextButton(shuffle, enabled = stats.songs.isNotEmpty()) { Text(stringResource(Res.string.m4_shuffle)) }
                        }
                    }
                }
                itemsIndexed(if (expanded) stats.songs else stats.songs.take(5),
                    key = { index, rank -> "song-$index-${rank.item.ref.collectionUiKey()}" }) { index, rank ->
                    StatisticsRankRow(rank, index, { actions.play(rank) }, actions.more, artwork, formatDuration)
                }
                if (stats.songs.size > 5) item("expand-songs") {
                    TextButton({ expanded = !expanded }) {
                        Text(stringResource(if (expanded) Res.string.m4_show_less else Res.string.m4_see_all))
                    }
                }
                item("artists-title") { Text(stringResource(Res.string.m4_artists), Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge) }
                itemsIndexed(stats.artists, key = { i, rank -> "artist-$i-${rank.item.ref.collectionUiKey()}" }) { index, rank ->
                    StatisticsRankRow(rank, index, { actions.open(rank.item.ref) }, actions.more, artwork, formatDuration)
                }
                item("albums-title") { Text(stringResource(Res.string.m4_albums), Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge) }
                itemsIndexed(stats.albums, key = { i, rank -> "album-$i-${rank.item.ref.collectionUiKey()}" }) { index, rank ->
                    StatisticsRankRow(rank, index, { actions.open(rank.item.ref) }, actions.more, artwork, formatDuration)
                }
            }
        }
    }
    if (yearPicker) AlertDialog(onDismissRequest = { yearPicker = false },
        title = { Text(stringResource(Res.string.m4_year_in_music)) },
        text = { LazyColumn { items(state.recapYears.distinct(), key = { it }) { year ->
            TextButton({ yearPicker = false; actions.recap(year) }) { Text(year.toString()) }
        } } },
        confirmButton = { TextButton({ yearPicker = false }) { Text(stringResource(Res.string.m4_close)) } })
}

@Composable
internal fun StatisticsRankRow(
    rank: ListeningRank,
    index: Int,
    open: (() -> Unit)?,
    more: ((ContentRef) -> Unit)?,
    artwork: ContentArtwork,
    formatDuration: (Long) -> String,
) {
    ListItem(headlineContent = { Text(rank.item.title) },
        supportingContent = { Text(rank.item.subtitle + "\n" + stringResource(Res.string.m4_stats_rank_detail,
            rank.playCount, formatDuration(rank.playTimeMs))) },
        leadingContent = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text((index + 1).toString()); artwork(rank.item, Modifier.size(48.dp))
        } },
        trailingContent = more?.let { menu -> {
            TextButton({ menu(rank.item.ref) }) { Text(stringResource(Res.string.m4_more)) }
        } },
        modifier = if (open == null) Modifier else Modifier.clickable(onClick = open))
}

@Composable
private fun SlotChart(slots: List<ListeningSlot>, label: @Composable (Int) -> String, formatDuration: (Long) -> String) {
    val max = slots.maxOfOrNull { it.playTimeMs }?.takeIf { it > 0 }?.toDouble()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(slots.sortedBy { it.index }, key = { it.index }) { slot ->
            Column(Modifier.width(80.dp)) {
                Text(label(slot.index), style = MaterialTheme.typography.labelMedium)
                if (max != null) LinearProgressIndicator(
                    progress = { (slot.playTimeMs.toDouble() / max).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(formatDuration(slot.playTimeMs), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.ui.glass.LocalOpticalPolicy
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

enum class YearRecapCard { EMPTY, INTRO, TOTALS, TOP_SONG, ARTISTS, ALBUMS, SUMMARY }
data class YearInMusicUi(
    val year: Int,
    val availableYears: List<Int>,
    val statistics: ListeningStatistics?,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val sharing: Boolean = false,
    val shareFailed: Boolean = false,
)
/**
 * Actual captured card pixels, not a successful placeholder URI. The native share owner fits these
 * to 1080×1920, writes PNG, and shares with the platform's real temporary-file grant/lifecycle.
 */
data class YearRecapShare(val year: Int, val card: YearRecapCard, val pageIndex: Int, val image: ImageBitmap) {
    val fileName: String get() = "ArchiveTune_YearInMusic_${year}_${pageIndex + 1}.png"
    val targetWidth: Int get() = 1080
    val targetHeight: Int get() = 1920
}
class YearInMusicActions(
    val back: () -> Unit,
    val year: (Int) -> Unit,
    val retry: () -> Unit,
    val share: (YearRecapShare) -> Unit,
    val songMenu: ((ContentRef) -> Unit)?,
)

fun yearRecapCards(statistics: ListeningStatistics?): List<YearRecapCard> {
    if (statistics == null || (statistics.playCount == 0L && statistics.playTimeMs == 0L &&
            statistics.songs.isEmpty() && statistics.artists.isEmpty() && statistics.albums.isEmpty())) {
        return listOf(YearRecapCard.EMPTY)
    }
    return buildList {
        add(YearRecapCard.INTRO); add(YearRecapCard.TOTALS)
        if (statistics.songs.isNotEmpty()) add(YearRecapCard.TOP_SONG)
        if (statistics.artists.isNotEmpty()) add(YearRecapCard.ARTISTS)
        if (statistics.albums.isNotEmpty()) add(YearRecapCard.ALBUMS)
        add(YearRecapCard.SUMMARY)
    }
}

@Composable
fun YearInMusicScreen(
    state: YearInMusicUi,
    actions: YearInMusicActions,
    artwork: ContentArtwork,
    formatDuration: (Long) -> String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color.Black)) {
        Column(modifier.fillMaxSize().background(Color.Black).padding(contentPadding)) {
            ManagementHeader(stringResource(Res.string.m4_year_in_music), actions.back)
            var yearsExpanded by rememberSaveable { mutableStateOf(false) }
            Box(Modifier.padding(horizontal = 16.dp)) {
                TextButton({ yearsExpanded = true }, enabled = !state.sharing) { Text(state.year.toString()) }
                DropdownMenu(yearsExpanded, { yearsExpanded = false }) {
                    state.availableYears.distinct().forEach { year ->
                        DropdownMenuItem(text = { Text(year.toString()) }, onClick = { yearsExpanded = false; actions.year(year) })
                    }
                }
            }
            if (state.loading || state.failed) LoadFeedback(state.loading, state.failed, false, actions.retry)
            else key(state.year) {
                YearRecapPager(state, actions, artwork, formatDuration, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun YearRecapPager(
    state: YearInMusicUi,
    actions: YearInMusicActions,
    artwork: ContentArtwork,
    formatDuration: (Long) -> String,
    modifier: Modifier,
) {
    val cards = yearRecapCards(state.statistics)
    val pager = rememberPagerState { cards.size }
    val scope = rememberCoroutineScope()
    val motion = LocalOpticalPolicy.current.motion
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    val layer = rememberGraphicsLayer()
    var capturing by remember { mutableStateOf(false) }
    var captureFailed by remember { mutableStateOf(false) }
    LaunchedEffect(cards) {
        if (pager.currentPage >= cards.size) pager.scrollToPage(cards.lastIndex)
    }
    val busy = capturing || state.sharing
    fun move(delta: Int) {
        if (!busy) scope.launch {
            val target = (pager.currentPage + delta).coerceIn(cards.indices)
            if (motion) pager.animateScrollToPage(target) else pager.scrollToPage(target)
        }
    }
    Column(modifier.fillMaxSize()) {
        // Chrome is outside this recording. No screenshot of the whole app or a synthetic card is shared.
        HorizontalPager(pager, userScrollEnabled = !busy,
            key = { cards[it].name },
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds().drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }) { page ->
            YearRecapCardContent(state.year, cards[page], state.statistics, artwork, formatDuration,
                next = if (page < cards.lastIndex && !busy) ({ move(1) }) else null,
                songMenu = if (busy) null else actions.songMenu)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ move(-1) }, enabled = !busy && pager.currentPage > 0) { Text(stringResource(Res.string.m4_recap_previous)) }
            Text(stringResource(Res.string.m4_recap_card_count, pager.currentPage + 1, cards.size))
            TextButton({ move(1) }, enabled = !busy && pager.currentPage < cards.lastIndex) { Text(stringResource(Res.string.m4_recap_next)) }
        }
        if (state.shareFailed || captureFailed) Text(stringResource(Res.string.m4_recap_share_failed), Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            val year = state.year
            val page = pager.currentPage
            val card = cards[page]
            capturing = true
            captureFailed = false
            scope.launch {
                try {
                    withFrameNanos { }
                    withFrameNanos { }
                    val image = layer.toImageBitmap()
                    // A refreshed/changed data set must never label old pixels as the new card.
                    if (currentState.year == year && currentState.statistics == state.statistics &&
                        yearRecapCards(currentState.statistics).getOrNull(page) == card &&
                        pager.currentPage == page && !pager.isScrollInProgress) {
                        currentActions.share(YearRecapShare(year, card, page, image))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    captureFailed = true
                } finally {
                    capturing = false
                }
            }
        }, enabled = !busy && !pager.isScrollInProgress && cards[pager.currentPage] != YearRecapCard.EMPTY,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(if (busy) Res.string.m4_loading else Res.string.m4_recap_share))
        }
    }
}

@Composable
private fun YearRecapCardContent(
    year: Int,
    card: YearRecapCard,
    statistics: ListeningStatistics?,
    artwork: ContentArtwork,
    formatDuration: (Long) -> String,
    next: (() -> Unit)?,
    songMenu: ((ContentRef) -> Unit)?,
) {
    val topSong = statistics?.songs?.firstOrNull()
    val cardGesture = when {
        next != null && card == YearRecapCard.TOP_SONG && songMenu != null && topSong != null ->
            Modifier.combinedClickable(onClick = next, onLongClick = { songMenu(topSong.item.ref) })
        next != null -> Modifier.clickable(onClick = next)
        else -> Modifier
    }
    Column(Modifier.fillMaxSize().background(Color.Black).then(
        cardGesture
    ).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(year.toString(), style = MaterialTheme.typography.displayMedium)
        Text(stringResource(when (card) {
            YearRecapCard.EMPTY -> Res.string.m4_recap_empty
            YearRecapCard.INTRO -> Res.string.m4_recap_intro
            YearRecapCard.TOTALS -> Res.string.m4_recap_totals
            YearRecapCard.TOP_SONG -> Res.string.m4_recap_top_song
            YearRecapCard.ARTISTS -> Res.string.m4_recap_artists
            YearRecapCard.ALBUMS -> Res.string.m4_recap_albums
            YearRecapCard.SUMMARY -> Res.string.m4_recap_summary
        }, year), style = MaterialTheme.typography.headlineLarge)
        if (statistics != null && card != YearRecapCard.EMPTY) {
            when (card) {
                YearRecapCard.TOP_SONG -> statistics.songs.firstOrNull()?.let { top ->
                    artwork(top.item, Modifier.size(160.dp))
                    Text(top.item.title, style = MaterialTheme.typography.headlineMedium)
                    Text(top.item.subtitle)
                    Text(stringResource(Res.string.m4_stats_plays, top.playCount))
                    songMenu?.let { menu ->
                        TextButton({ menu(top.item.ref) }) { Text(stringResource(Res.string.m4_more)) }
                    }
                }
                YearRecapCard.ARTISTS, YearRecapCard.ALBUMS -> {
                    val ranks = if (card == YearRecapCard.ARTISTS) statistics.artists else statistics.albums
                    ranks.take(5).forEachIndexed { index, rank ->
                        StatisticsRankRow(rank, index, null, null, artwork, formatDuration)
                    }
                }
                else -> {
                    Text(stringResource(Res.string.m4_stats_plays, statistics.playCount), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(Res.string.m4_stats_listening, formatDuration(statistics.playTimeMs)))
                    if (card == YearRecapCard.SUMMARY) {
                        statistics.songs.firstOrNull()?.let { Text(it.item.title) }
                        statistics.artists.firstOrNull()?.let { Text(it.item.title) }
                        statistics.albums.firstOrNull()?.let { Text(it.item.title) }
                    }
                }
            }
        }
    }
}

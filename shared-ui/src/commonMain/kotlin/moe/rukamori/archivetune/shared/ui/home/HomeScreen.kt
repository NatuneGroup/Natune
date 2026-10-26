package moe.rukamori.archivetune.shared.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState

/** A presentation choice only, never serialized as a new replacement for HomeScreenStyle. */
enum class HomeFeedDesign { Classic, Rukamori, BitChord, Muzo }

/** Titles are provider metadata; action availability is retained from the actual section endpoint. */
data class HomeShelf(
    val key: String,
    val title: String,
    val items: List<ContentOccurrence>,
    val more: (() -> Unit)? = null,
)

@Composable
fun HomeSourceSwitcher(current: HomeSource, available: List<HomeSource>, onSelect: (HomeSource) -> Unit) {
    if (available.size <= 1) return
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(available.distinct(), key = { it.name }) { source ->
            FilterChip(source == current, { onSelect(source) }, modifier = Modifier.testTag("home-source-${source.name}"),
                label = { Text(stringResource(when (source) {
                    HomeSource.YOUTUBE -> Res.string.m4_youtube
                    HomeSource.SPOTIFY -> Res.string.m4_spotify
                    HomeSource.QQ -> Res.string.m4_qq
                })) })
        }
    }
}

/**
 * The title, navigation links and source switcher above every Home page. Variant pages draw it above their own
 * scrolling body, so it lives outside the generic feed's lazy list and is shared rather than repeated per page.
 */
@Composable
fun HomePageHeader(
    source: HomeSource,
    availableSources: List<HomeSource>,
    onSource: (HomeSource) -> Unit,
    headerActions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(Res.string.m4_home), style = MaterialTheme.typography.headlineLarge)
            headerActions()
        }
        HomeSourceSwitcher(source, availableSources, onSource)
    }
}

/**
 * Hoisted shelves and list state survive look changes. Source changes replace data through the host,
 * not by combining Spotify/QQ into the YouTube feed or changing playback provider priority.
 *
 * [accountName] feeds the greeting (blank shows the anonymous line). [blurEffects] is the host's blur policy: when on,
 * the atmosphere wash is drawn behind the feed, as the baseline home screen does.
 *
 * [activeIds] and [playing] drive the active overlay on shelf cards; [thumbnailAspectRatio] is the host hook for a
 * non-square frame. [onRefresh] enables the pull gesture, whose line is driven by [refreshing]. Each new parameter
 * defaults to the previous behaviour, so existing callers keep their current presentation.
 */
@Composable
fun HomeScreen(
    source: HomeSource,
    availableSources: List<HomeSource>,
    onSource: (HomeSource) -> Unit,
    design: HomeFeedDesign,
    shelves: List<HomeShelf>,
    pageState: PagedContentState,
    actions: ContentActions,
    artwork: ContentArtwork,
    headerActions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = LocalShellContentPadding.current,
    accountName: String = "",
    blurEffects: Boolean = true,
    activeIds: Set<String> = emptySet(),
    playing: Boolean = false,
    thumbnailAspectRatio: (ContentItem) -> Float = { HomeSquareCardRatio },
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    val pullState = rememberPullToRefreshState()
    // Before the first shelves arrive the skeleton stands in for them, and the status line would only repeat its loading.
    val initialLoad = shelves.isEmpty() && pageState.loading
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (blurEffects) {
            // Muzo keeps its own quieter wash; every other Home look draws the baseline backdrop, which is always on.
            if (design == HomeFeedDesign.Muzo) HomeAtmosphereBackground() else HomeClassicAtmosphereBackground()
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { onRefresh?.invoke() },
            state = pullState,
            enabled = onRefresh != null,
            indicator = {},
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize().testTag("home-feed"), state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(if (design == HomeFeedDesign.BitChord) 26.dp else 18.dp)) {
                item(key = "m4-home-header") { HomePageHeader(source, availableSources, onSource, headerActions) }
                item(key = "m4-home-greeting") { HomeWelcomeHeader(accountName) }
                items(shelves, key = { "m4-home-shelf-${it.key}" }) { shelf ->
                    Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(shelf.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            shelf.more?.let { more ->
                                TextButton(more, Modifier.testTag("shelf-more-${shelf.key}")) {
                                    Text(stringResource(Res.string.m4_more))
                                }
                            }
                        }
                        if (source == HomeSource.SPOTIFY && design == HomeFeedDesign.Classic) {
                            // Spotify's compact rows carry playable selections rather than remote navigation text.
                            shelf.items.chunked(2).forEach { pair ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pair.forEach { row ->
                                        Card(Modifier.weight(1f).clickable { actions.open(row) }) {
                                            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                artwork(row.item, Modifier.size(48.dp))
                                                Text(row.item.title, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                actions.more?.let { more ->
                                                    TextButton({ more(row) }) { Text(stringResource(Res.string.m4_more)) }
                                                }
                                            }
                                        }
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        } else {
                            LazyRow(contentPadding = PaddingValues(horizontal = if (design == HomeFeedDesign.BitChord) 10.dp else 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(if (design == HomeFeedDesign.BitChord) 14.dp else 12.dp)) {
                                items(shelf.items, key = { row -> row.occurrenceId }) { row ->
                                    HomeShelfOccurrenceCard(
                                        occurrence = row,
                                        artwork = artwork,
                                        actions = actions,
                                        activeIds = activeIds,
                                        playing = playing,
                                        thumbnailAspectRatio = thumbnailAspectRatio,
                                        modifier = Modifier.testTag("home-${shelf.key}-${row.occurrenceId}"),
                                    )
                                }
                            }
                        }
                    }
                }
                if (initialLoad) {
                    homeFeedSkeleton()
                } else {
                    item(key = "m4-home-status") { PageStatus(pageState, actions) }
                }
            }
        }
        HomePullRefreshLine(
            refreshing = refreshing,
            distanceFraction = { pullState.distanceFraction },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

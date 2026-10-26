package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

data class PodcastSubscriptionUi(val subscribed: Boolean, val busy: Boolean, val failed: Boolean)
data class PodcastEpisodeUi(
    val row: ContentOccurrence,
    val dateText: String?,
    val durationText: String?,
    val description: String?,
)
data class PodcastUi(
    val collection: ContentRequest,
    val header: ContentItem?,
    val description: String?,
    val episodes: List<PodcastEpisodeUi>,
    val page: PagedContentState,
    /** Null means the provider/host has no supported subscription operation, not "unsubscribed". */
    val subscription: PodcastSubscriptionUi? = null,
) {
    init {
        require(collection.surface == ContentSurface.PODCAST && collection.parent?.kind == ContentKind.PODCAST)
        require(episodes.map { it.row.occurrenceId } == page.items.map { it.occurrenceId })
    }
}
class PodcastScreenActions(
    val back: () -> Unit,
    val play: (QueueLoadRequest) -> Unit,
    val subscribe: ((Boolean) -> Unit)?,
    val retry: () -> Unit,
    val loadMore: () -> Unit,
)

/** No shuffle, overflow or add-to-library hero: those controls do not exist in the original. */
@Composable
fun PodcastScreen(
    state: PodcastUi,
    actions: PodcastScreenActions,
    artwork: ContentArtwork,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    val pageActions = ContentActions({ occurrence ->
        val index = state.episodes.indexOfFirst { it.row.occurrenceId == occurrence.occurrenceId }
        if (index >= 0) actions.play(QueueLoadRequest(state.collection, QueueCollectionKind.FINITE,
            QueueStart(occurrence.item.ref, occurrence.occurrenceId, occurrence.setVideoId, index)))
    }, null, actions.loadMore, actions.retry)
    ContinuationLoadEffect(listState, state.page, actions.loadMore, threshold = 4)
    Column(modifier.fillMaxSize()) {
        ManagementHeader(state.header?.title ?: stringResource(Res.string.m4_podcasts), actions.back)
        LazyColumn(Modifier.weight(1f).testTag("podcast-episodes"), state = listState, contentPadding = contentPadding) {
            item("hero") {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.header?.let { header ->
                        artwork(header, Modifier.sizeIn(maxWidth = 240.dp, maxHeight = 240.dp).fillMaxWidth().aspectRatio(1f))
                        Text(header.title, style = MaterialTheme.typography.headlineLarge)
                        Text(header.subtitle, style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ actions.play(QueueLoadRequest(state.collection, QueueCollectionKind.FINITE)) },
                            enabled = state.episodes.isNotEmpty(), modifier = Modifier.testTag("podcast-play-all")) {
                            Text(stringResource(Res.string.m4_play))
                        }
                        state.subscription?.let { subscription ->
                            SubscriptionButton(subscription, requireNotNull(actions.subscribe), "podcast-subscribe")
                        }
                    }
                    state.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(stringResource(Res.string.m4_episodes), style = MaterialTheme.typography.titleLarge)
                }
            }
            items(state.episodes, key = { "episode-${it.row.occurrenceId}" }) { episode ->
                ListItem(
                    headlineContent = { Text(episode.row.item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(episode.row.item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            listOfNotNull(episode.dateText, episode.durationText).filter { it.isNotBlank() }
                                .takeIf { it.isNotEmpty() }?.let { Text(it.joinToString("  •  ")) }
                            episode.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    leadingContent = { artwork(episode.row.item, Modifier.size(88.dp)) },
                    trailingContent = { Text(stringResource(Res.string.m4_play), style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.heightIn(min = 112.dp).clickable { pageActions.open(episode.row) }
                        .testTag("podcast-episode-${episode.row.occurrenceId}"),
                )
                HorizontalDivider(Modifier.padding(start = 112.dp))
            }
            item("status") { PageStatus(state.page, pageActions) }
        }
    }
}

@Composable
private fun SubscriptionButton(state: PodcastSubscriptionUi, submit: (Boolean) -> Unit, tag: String) {
    Column {
        OutlinedButton({ submit(!state.subscribed) }, enabled = !state.busy, modifier = Modifier.testTag(tag)) {
            Text(stringResource(if (state.subscribed) Res.string.m4_unsubscribe else Res.string.m4_subscribe))
        }
        if (state.failed) Text(stringResource(Res.string.m4_subscription_failed), color = MaterialTheme.colorScheme.error)
    }
}

data class PodcastBrowseUi(
    val page: PagedContentState,
    val query: String = "",
    val subscriptionsOnly: Boolean = false,
    val subscriptionsAvailable: Boolean = false,
    val subscriptions: Map<ContentRef, PodcastSubscriptionUi> = emptyMap(),
)
class PodcastBrowseActions(
    val back: () -> Unit,
    val query: (String) -> Unit,
    val subscriptionsOnly: (Boolean) -> Unit,
    val open: (ContentRef) -> Unit,
    val subscribe: ((ContentRef, Boolean) -> Unit)?,
    val retry: () -> Unit,
    val loadMore: () -> Unit,
)

@Composable
fun PodcastBrowseScreen(
    state: PodcastBrowseUi,
    actions: PodcastBrowseActions,
    artwork: ContentArtwork,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    val list = rememberLazyListState()
    ContinuationLoadEffect(list, state.page, actions.loadMore, threshold = 4)
    val rows = state.page.items.filter { row ->
        (!state.subscriptionsOnly || state.subscriptions[row.item.ref]?.subscribed == true) &&
            (state.query.isBlank() || row.item.title.contains(state.query.trim(), true) ||
                row.item.subtitle.contains(state.query.trim(), true))
    }
    Column(modifier.fillMaxSize()) {
        ManagementHeader(stringResource(Res.string.m4_podcasts), actions.back)
        OutlinedTextField(state.query, actions.query, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true, label = { Text(stringResource(Res.string.m4_search)) })
        if (state.subscriptionsAvailable) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!state.subscriptionsOnly, { actions.subscriptionsOnly(false) }, label = { Text(stringResource(Res.string.m4_all)) })
            FilterChip(state.subscriptionsOnly, { actions.subscriptionsOnly(true) },
                modifier = Modifier.testTag("podcast-subscriptions"), label = { Text(stringResource(Res.string.m4_subscriptions)) })
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = contentPadding) {
            items(rows, key = { it.occurrenceId }) { row ->
                ListItem(headlineContent = { Text(row.item.title) }, supportingContent = { Text(row.item.subtitle) },
                    leadingContent = { artwork(row.item, Modifier.size(72.dp)) },
                    trailingContent = state.subscriptions[row.item.ref]?.let { subscription -> {
                        SubscriptionButton(subscription, { requireNotNull(actions.subscribe)(row.item.ref, it) },
                            "podcast-subscribe-${row.item.ref.id}")
                    } },
                    modifier = Modifier.clickable { actions.open(row.item.ref) }.testTag("podcast-open-${row.item.ref.id}"))
            }
            item("status") {
                PageStatus(state.page.copy(items = rows), ContentActions({ actions.open(it.item.ref) }, null, actions.loadMore, actions.retry))
            }
        }
    }
}

/** Token changes re-arm near-tail loading, including a legitimate empty continuation page. */
@Composable
internal fun ContinuationLoadEffect(list: LazyListState, state: PagedContentState, load: () -> Unit, threshold: Int) {
    val currentLoad by rememberUpdatedState(load)
    LaunchedEffect(list, state.continuation, state.loading, state.failed) {
        if (state.continuation == null || state.loading || state.failed) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.let { it.totalItemsCount > 0 &&
            (it.visibleItemsInfo.lastOrNull()?.index ?: -1) >= it.totalItemsCount - threshold } }
            .distinctUntilChanged().collect { if (it) currentLoad() }
    }
}

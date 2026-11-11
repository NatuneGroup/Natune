package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.ui.glass.BusyLinearProgress
import moe.rukamori.archivetune.shared.ui.songmetadata.effectiveSong
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The injected host renders real resolved artwork. No URL loader, credential or decoder owner is created here. */
typealias ContentArtwork = @Composable (ContentItem, Modifier) -> Unit

class ContentActions(
    val open: (ContentOccurrence) -> Unit,
    val more: ((ContentOccurrence) -> Unit)?,
    val loadMore: () -> Unit,
    val retry: () -> Unit,
)

/**
 * The per-item overflow control. An icon rather than a text label so it does not take width from the item's title.
 */
@Composable
internal fun ItemMenuButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(painterResource(Res.drawable.m4hv_icon_more_vert), stringResource(Res.string.m4_more))
    }
}

@Composable
fun ContentRow(
    occurrence: ContentOccurrence,
    actions: ContentActions,
    artwork: ContentArtwork,
    modifier: Modifier = Modifier,
    ordinal: Int? = null,
    /** Extra row actions, such as the playlist drag handle and move buttons, drawn before the overflow button. */
    trailingControls: (@Composable () -> Unit)? = null,
    showDuration: Boolean = false,
) {
    if (moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation.current) {
        PhoneContentRow(occurrence, actions, artwork, modifier, ordinal, trailingControls, showDuration)
        return
    }
    val display = effectiveSong(occurrence.item)
    val duration = contentTrackDuration(occurrence.item.durationMs).takeIf { showDuration }
    ListItem(
        headlineContent = { Text(display.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(display.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                PodcastEpisodeProgress(occurrence.item.ref)
            }
        },
        leadingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ordinal?.let { Text(it.toString(), style = MaterialTheme.typography.labelMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.width(28.dp).testTag("track-number-${occurrence.occurrenceId}")) }
                artwork(occurrence.item, Modifier.size(48.dp))
            }
        },
        trailingContent = if (trailingControls == null && actions.more == null && duration == null) {
            null
        } else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    duration?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp).testTag("track-duration-${occurrence.occurrenceId}")) }
                    trailingControls?.invoke()
                    actions.more?.let { more ->
                        ItemMenuButton(onClick = { more(occurrence) }, modifier = Modifier.testTag("more-${occurrence.occurrenceId}"))
                    }
                }
            }
        },
        modifier = modifier.fillMaxWidth().clickable { actions.open(occurrence) }
            .testTag("occurrence-${occurrence.occurrenceId}"),
    )
}

internal fun LazyListScope.contentRows(
    state: PagedContentState,
    actions: ContentActions,
    artwork: ContentArtwork,
    numbered: Boolean = false,
    /** Non-null only for an unlocked custom order of a complete list; each row then offers its neighbour moves. */
    move: ((ContentOccurrence, ContentOccurrence, Boolean) -> Unit)? = null,
    /** The drag handle of the same admission as [move]. A held drag reorders the rows on screen through its preview. */
    drag: PlaylistRowDrag? = null,
    showDuration: Boolean = false,
) {
    val rows = drag?.displayRows(state.items) ?: state.items
    itemsIndexed(rows, key = { _, row -> playlistRowKey(row.occurrenceId) }) { index, occurrence ->
        // The move callbacks name the owner's complete order, so a neighbour is always looked up there, never in the preview.
        val ownerIndex =
            if (rows === state.items) index else state.items.indexOfFirst { it.occurrenceId == occurrence.occurrenceId }
        val reorder: (@Composable () -> Unit)? =
            if (move == null || ownerIndex < 0) {
                null
            } else {
                {
                    if (drag != null) PlaylistDragHandle(occurrence.occurrenceId, drag)
                    PlaylistMoveControls(state.items, ownerIndex, move)
                }
            }
        ContentRow(
            occurrence,
            actions,
            artwork,
            modifier = drag?.rowModifier(occurrence.occurrenceId, MaterialTheme.colorScheme.surfaceContainerHigh) ?: Modifier,
            ordinal = if (numbered) index + 1 else null,
            trailingControls = reorder,
            showDuration = showDuration,
        )
    }
    item(key = "m4-pagination-status") { PageStatus(state, actions) }
}

internal fun contentTrackDuration(durationMs: Long?): String? {
    if (durationMs == null || durationMs <= 0L) return null
    val seconds = durationMs / 1000L
    val minutes = seconds / 60L
    val secondText = (seconds % 60L).toString().padStart(2, '0')
    return if (minutes < 60L) "$minutes:$secondText"
        else "${minutes / 60L}:${(minutes % 60L).toString().padStart(2, '0')}:$secondText"
}

@Composable
internal fun PageStatus(state: PagedContentState, actions: ContentActions) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        when {
            state.loading -> {
                BusyLinearProgress(Modifier.fillMaxWidth())
                Text(stringResource(Res.string.m4_loading))
            }
            state.failed -> {
                Text(stringResource(Res.string.m4_load_failed))
                Button(onClick = actions.retry, modifier = Modifier.testTag("content-retry")) {
                    Text(stringResource(Res.string.m4_retry))
                }
            }
            state.initialized && state.items.isEmpty() && state.continuation == null -> Text(stringResource(Res.string.m4_empty))
            state.continuation != null -> Button(onClick = actions.loadMore, modifier = Modifier.testTag("content-load-more")) {
                Text(stringResource(Res.string.m4_load_more))
            }
        }
    }
}

@Composable
fun rememberPagedContent(repository: ContentRepository, query: ContentQuery): PagedContentPresenter {
    val scope = rememberCoroutineScope()
    val presenter = remember(repository) { PagedContentPresenter(repository, scope) }
    LaunchedEffect(presenter, query) { presenter.open(query) }
    DisposableEffect(presenter) { onDispose { presenter.close() } }
    return presenter
}

@Composable
fun rememberPagedContent(repository: TypedContentRepository, request: ContentRequest): PagedContentPresenter {
    val scope = rememberCoroutineScope()
    val presenter = remember(repository) { PagedContentPresenter(repository, scope) }
    LaunchedEffect(presenter, request) { presenter.open(request) }
    DisposableEffect(presenter) { onDispose { presenter.close() } }
    return presenter
}

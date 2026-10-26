/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
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
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** The injected host renders real resolved artwork. No URL loader, credential or decoder owner is created here. */
typealias ContentArtwork = @Composable (ContentItem, Modifier) -> Unit

class ContentActions(
    val open: (ContentOccurrence) -> Unit,
    val more: ((ContentOccurrence) -> Unit)?,
    val loadMore: () -> Unit,
    val retry: () -> Unit,
)

@Composable
fun ContentRow(
    occurrence: ContentOccurrence,
    actions: ContentActions,
    artwork: ContentArtwork,
    modifier: Modifier = Modifier,
    ordinal: Int? = null,
    /** Extra row actions, such as the playlist drag handle and move buttons, drawn before the overflow button. */
    trailingControls: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(occurrence.item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(occurrence.item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ordinal?.let { Text(it.toString(), style = MaterialTheme.typography.labelMedium) }
                artwork(occurrence.item, Modifier.size(48.dp))
            }
        },
        trailingContent = if (trailingControls == null && actions.more == null) {
            null
        } else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    trailingControls?.invoke()
                    actions.more?.let { more ->
                        TextButton(onClick = { more(occurrence) }, modifier = Modifier.testTag("more-${occurrence.occurrenceId}")) {
                            Text(stringResource(Res.string.m4_more))
                        }
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
        )
    }
    item(key = "m4-pagination-status") { PageStatus(state, actions) }
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

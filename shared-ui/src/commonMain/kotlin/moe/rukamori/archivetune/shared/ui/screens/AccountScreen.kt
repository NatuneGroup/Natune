/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.ui.glass.BusyLinearProgress
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** The three chips of the signed-in account library, in baseline order. */
enum class AccountContentType { PLAYLISTS, ALBUMS, ARTISTS }

/**
 * Null lists are still loading and are never shown as an empty library. The owner keeps the selected chip,
 * the library snapshots and the long-press menu entry; this screen only renders and routes them.
 */
data class AccountUi(
    val selected: AccountContentType,
    val playlists: List<ContentItem>?,
    val albums: List<ContentItem>?,
    val artists: List<ContentItem>?,
)

/** [open] is the routed content page for the item; [more] is the owner's menu, null when no menu exists. */
data class AccountActions(
    val back: () -> Unit,
    val select: (AccountContentType) -> Unit,
    val open: (ContentItem) -> Unit,
    val more: ((ContentItem) -> Unit)?,
)

@Composable
fun AccountScreen(
    state: AccountUi,
    actions: AccountActions,
    artwork: ContentArtwork,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    val rows = when (state.selected) {
        AccountContentType.PLAYLISTS -> state.playlists
        AccountContentType.ALBUMS -> state.albums
        AccountContentType.ARTISTS -> state.artists
    }
    Column(modifier.fillMaxSize()) {
        ManagementHeader(stringResource(Res.string.m4_account), actions.back)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 152.dp),
            modifier = Modifier.weight(1f).testTag("account-grid"),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountContentType.entries.forEach { type ->
                        FilterChip(state.selected == type, { actions.select(type) },
                            label = { Text(stringResource(type.label())) },
                            modifier = Modifier.testTag("account-chip-${type.name}"))
                    }
                }
            }
            when {
                rows == null -> item(span = { GridItemSpan(maxLineSpan) }) {
                    BusyLinearProgress(Modifier.fillMaxWidth())
                }
                rows.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(Res.string.m4_empty), Modifier.padding(16.dp))
                }
                else -> items(rows.distinctBy { it.ref }, key = { "${it.ref.provider}/${it.ref.kind}/${it.ref.id}" }) { item ->
                    AccountTile(item, artwork, Modifier.testTag("account-item-${item.ref.id}").combinedClickable(
                        onClick = { actions.open(item) },
                        onLongClick = { actions.more?.invoke(item) },
                    ))
                }
            }
        }
    }
}

@Composable
private fun AccountTile(item: ContentItem, artwork: ContentArtwork, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        artwork(item, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}

private fun AccountContentType.label() = when (this) {
    AccountContentType.PLAYLISTS -> Res.string.m4_playlists
    AccountContentType.ALBUMS -> Res.string.m4_albums
    AccountContentType.ARTISTS -> Res.string.m4_artists
}

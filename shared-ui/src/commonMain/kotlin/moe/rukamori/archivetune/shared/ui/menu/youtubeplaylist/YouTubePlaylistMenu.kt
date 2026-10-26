@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package moe.rukamori.archivetune.shared.ui.menu.youtubeplaylist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.ui.menu.MenuShareHandler
import moe.rukamori.archivetune.shared.ui.menu.OverflowAction
import moe.rukamori.archivetune.shared.ui.resources.Res
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The YouTube playlist sheet, in baseline order: header with bookmark, primary playback, list commands
 * with auto sync, download, then share and select. Every command runs through [youTubePlaylistOverflowActions]
 * and [host]. The sheet holds no Android service, database or Intent.
 *
 * [songs] is the caller's known list. It drives only the download state and download start, and an empty
 * list makes the download command load the full list first. [addToPlaylistChooser] is required, because the
 * chooser's data source belongs to the host. It receives the song loader, the completion callback and the
 * dismiss callback.
 */
@Composable
fun YouTubePlaylistMenu(
    facts: YouTubePlaylistMenuFacts,
    songs: List<ContentItem>,
    host: YouTubePlaylistMenuHost,
    queue: QueueController,
    sharing: MenuShareHandler,
    canSelect: Boolean,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
    artwork: @Composable () -> Unit,
    addToPlaylistChooser: @Composable (
        loadSongs: suspend () -> List<ContentOccurrence>,
        onAdded: (songCount: Int, playlistNames: List<String>) -> Unit,
        onDismiss: () -> Unit,
    ) -> Unit,
) {
    val ref = facts.item.ref
    val observedLocal = remember(host, ref) { host.observeLocal(ref) }
    val localState by observedLocal.collectAsState(initial = YouTubePlaylistLocalState.Initial)
    val downloads by host.downloads.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<YouTubePlaylistNotice?>(null) }
    var choosingPlaylist by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var confirmRemoveDownloads by remember { mutableStateOf(false) }

    val failedText = stringResource(Res.string.m6yp_playlist_action_failed)
    val importFailedText = stringResource(Res.string.m6yp_import_failed)
    val unknownErrorText = stringResource(Res.string.m6yp_unknown_error)
    val syncedText = stringResource(Res.string.m6yp_playlist_synced)

    val downloadState = youTubePlaylistDownloadState(songs.map { it.ref.id }, downloads)
    val actions = youTubePlaylistOverflowActions(
        facts = facts,
        local = localState,
        songs = songs,
        downloadState = downloadState,
        host = host,
        queue = queue,
        sharing = sharing,
        canSelect = canSelect,
        onAddToPlaylist = { choosingPlaylist = true },
        onImport = { showImport = true },
        onConfirmRemoveDownloads = { confirmRemoveDownloads = true },
        onSelect = onSelect,
    )
    val byId = actions.associateBy { it.id }

    // One command runs at a time. A failure keeps the sheet open and shows a notice instead.
    fun launchAction(dismissOnSuccess: Boolean, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            try {
                block()
                if (dismissOnSuccess) onDismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice = YouTubePlaylistNotice.Plain(failedText, error = true)
            } finally {
                busy = false
            }
        }
    }

    fun runAction(action: OverflowAction) = launchAction(action.dismissOnSuccess) { action.run() }

    // A bookmark creates the record from the full list when none exists, and otherwise only flips the flag.
    fun toggleBookmark() = launchAction(dismissOnSuccess = false) {
        if (localState.recordExists) {
            host.setBookmarked(facts, !localState.bookmarked)
        } else {
            host.bookmark(facts, host.loadComplete(ref))
        }
    }

    // Without a record the switch is off, so only enabling creates one, from the full list. The list is proven
    // before anything is written, so an empty or failed load commits nothing.
    fun toggleAutoSync(enabled: Boolean) = launchAction(dismissOnSuccess = false) {
        try {
            if (localState.recordExists) {
                host.setAutoSync(facts, enabled, null)
            } else {
                if (!enabled) return@launchAction
                val complete = host.loadComplete(ref)
                if (complete.songs.isEmpty()) {
                    notice = YouTubePlaylistNotice.Plain(importFailedText, error = true)
                    return@launchAction
                }
                host.setAutoSync(facts, true, complete)
            }
            notice = if (enabled) YouTubePlaylistNotice.Plain(syncedText, error = false) else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            notice = YouTubePlaylistNotice.Plain("$importFailedText: ${error.message ?: unknownErrorText}", error = true)
        }
    }

    fun removeDownloads() = launchAction(dismissOnSuccess = false) {
        host.removeDownloads(songs.map { it.ref.id })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) {
            YouTubePlaylistHeader(
                facts = facts,
                localState = localState,
                busy = busy,
                artwork = artwork,
                onToggleBookmark = { toggleBookmark() },
            )
            YouTubePlaylistRecoveryActions(host)
            val currentNotice = notice
            if (currentNotice != null) YouTubePlaylistNoticeText(currentNotice)

            YouTubePlaylistPrimaryRow(
                commands = listOfNotNull(byId["play"], byId["shuffle"], byId["start_radio"]),
                busy = busy,
                onRun = { runAction(it) },
            )

            YouTubePlaylistSection {
                val listCommands = listOfNotNull(
                    byId["play_next"],
                    byId["add_queue"],
                    byId["add_playlist"],
                    byId["speed_dial"],
                    byId["import"],
                )
                listCommands.forEachIndexed { index, action ->
                    if (index > 0) YouTubePlaylistDivider()
                    YouTubePlaylistCommandRow(
                        action = action,
                        icon = youTubePlaylistIcon(action.id, localState.speedDialPinned),
                        error = false,
                        busy = busy,
                        onRun = { runAction(it) },
                    )
                }
                YouTubePlaylistDivider()
                YouTubePlaylistSyncRow(
                    checked = localState.autoSync,
                    enabled = !busy,
                    onCheckedChange = { toggleAutoSync(it) },
                )
            }

            YouTubePlaylistSection {
                val downloadAction = actions.first {
                    it.id == "remove_download" || it.id == "downloading" || it.id == "download"
                }
                YouTubePlaylistCommandRow(
                    action = downloadAction,
                    icon = youTubePlaylistIcon(downloadAction.id, localState.speedDialPinned),
                    error = downloadAction.id == "remove_download",
                    busy = busy,
                    showProgress = downloadAction.id == "downloading",
                    onRun = { runAction(it) },
                )
            }

            YouTubePlaylistSection {
                byId["share"]?.let { action ->
                    YouTubePlaylistCommandRow(
                        action = action,
                        icon = youTubePlaylistIcon(action.id, localState.speedDialPinned),
                        error = false,
                        busy = busy,
                        onRun = { runAction(it) },
                    )
                }
                byId["select"]?.let { action ->
                    YouTubePlaylistDivider()
                    YouTubePlaylistCommandRow(
                        action = action,
                        icon = youTubePlaylistIcon(action.id, localState.speedDialPinned),
                        error = false,
                        busy = busy,
                        onRun = { runAction(it) },
                    )
                }
            }
        }
    }

    if (choosingPlaylist) {
        addToPlaylistChooser(
            { host.loadComplete(ref).songs.map { it.item } },
            { songCount, playlistNames -> notice = YouTubePlaylistNotice.Added(songCount, playlistNames) },
            { choosingPlaylist = false },
        )
    }
    if (showImport) {
        YouTubePlaylistImportDialog(
            facts = facts,
            host = host,
            onNotice = { message -> notice = YouTubePlaylistNotice.Plain(message, error = false) },
            onDismiss = { showImport = false },
        )
    }
    if (confirmRemoveDownloads) {
        AlertDialog(
            onDismissRequest = { confirmRemoveDownloads = false },
            text = { Text(stringResource(Res.string.m6yp_remove_download_playlist_confirm, facts.item.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveDownloads = false
                    removeDownloads()
                }) {
                    Text(stringResource(Res.string.m6yp_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveDownloads = false }) {
                    Text(stringResource(Res.string.m6_cancel))
                }
            },
        )
    }
    YouTubePlaylistLoadDialog(host)
}

/** The sheet's notices. A count-bearing success is formatted at composition, where string resources resolve. */
private sealed interface YouTubePlaylistNotice {
    data class Plain(val text: String, val error: Boolean) : YouTubePlaylistNotice
    data class Added(val songCount: Int, val playlistNames: List<String>) : YouTubePlaylistNotice
}

@Composable
private fun YouTubePlaylistHeader(
    facts: YouTubePlaylistMenuFacts,
    localState: YouTubePlaylistLocalState,
    busy: Boolean,
    artwork: @Composable () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    val supporting = listOfNotNull(
        facts.item.subtitle.takeIf { it.isNotBlank() },
        facts.songCountText?.takeIf { it.isNotBlank() },
    ).joinToString(" • ")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(56.dp)) { artwork() }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = facts.item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting.isNotEmpty()) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (facts.bookmarkable) {
            IconButton(onClick = onToggleBookmark, enabled = !busy) {
                Icon(
                    painter = painterResource(
                        if (localState.bookmarked) Res.drawable.m6yp_icon_favorite else Res.drawable.m6yp_icon_favorite_border,
                    ),
                    contentDescription = null,
                    tint = if (localState.bookmarked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun YouTubePlaylistNoticeText(notice: YouTubePlaylistNotice) {
    val message = when (notice) {
        is YouTubePlaylistNotice.Plain -> notice.text
        is YouTubePlaylistNotice.Added -> addedMessage(notice.songCount, notice.playlistNames)
    }
    val failed = notice is YouTubePlaylistNotice.Plain && notice.error
    Text(
        text = message,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** The four completion messages of the baseline chooser, resolved from the m6yp resources. */
@Composable
private fun addedMessage(songCount: Int, playlistNames: List<String>): String = when {
    songCount == 1 && playlistNames.size == 1 ->
        stringResource(Res.string.m6yp_added_to_playlist, playlistNames.first())
    songCount > 1 && playlistNames.size == 1 ->
        stringResource(Res.string.m6yp_added_n_songs_to_playlist, songCount, playlistNames.first())
    songCount == 1 -> stringResource(Res.string.m6yp_added_to_n_playlists, playlistNames.size)
    else -> stringResource(Res.string.m6yp_added_n_songs_to_n_playlists, songCount, playlistNames.size)
}

@Composable
private fun YouTubePlaylistPrimaryRow(
    commands: List<OverflowAction>,
    busy: Boolean,
    onRun: (OverflowAction) -> Unit,
) {
    if (commands.isEmpty()) return
    YouTubePlaylistSection {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            commands.forEach { action ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = action.enabled && !busy) { onRun(action) }
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        painter = painterResource(youTubePlaylistIcon(action.id, speedDialPinned = false)),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(action.label),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun YouTubePlaylistCommandRow(
    action: OverflowAction,
    icon: DrawableResource,
    error: Boolean,
    busy: Boolean,
    onRun: (OverflowAction) -> Unit,
    showProgress: Boolean = false,
) {
    ListItem(
        headlineContent = {
            Text(
                text = stringResource(action.label),
                color = if (error) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
        leadingContent = {
            if (showProgress) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else if (error) {
                Icon(painter = painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.error)
            } else {
                Icon(painter = painterResource(icon), contentDescription = null)
            }
        },
        modifier = Modifier.clickable(enabled = action.enabled && !busy) { onRun(action) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun YouTubePlaylistSyncRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(Res.string.m6yp_yt_sync)) },
        leadingContent = { Icon(painter = painterResource(Res.drawable.m6yp_icon_sync), contentDescription = null) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun YouTubePlaylistDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun YouTubePlaylistSection(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(content = content)
    }
}

/** Icon per command id. The speed dial icon follows the pinned state, and the download id picks the state's icon. */
private fun youTubePlaylistIcon(id: String, speedDialPinned: Boolean): DrawableResource = when (id) {
    "play" -> Res.drawable.m6yp_icon_play
    "shuffle" -> Res.drawable.m6yp_icon_shuffle
    "start_radio" -> Res.drawable.m6yp_icon_radio
    "play_next" -> Res.drawable.m6yp_icon_playlist_play
    "add_queue" -> Res.drawable.m6yp_icon_queue_music
    "add_playlist" -> Res.drawable.m6yp_icon_playlist_add
    "speed_dial" -> if (speedDialPinned) Res.drawable.m6yp_icon_bookmark_filled else Res.drawable.m6yp_icon_bookmark
    "import" -> Res.drawable.m6yp_icon_add
    "sync" -> Res.drawable.m6yp_icon_sync
    "remove_download" -> Res.drawable.m6yp_icon_offline
    "share" -> Res.drawable.m6yp_icon_share
    "select" -> Res.drawable.m6yp_icon_select_all
    else -> Res.drawable.m6yp_icon_download
}

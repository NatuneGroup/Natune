package moe.rukamori.archivetune.shared.ui.menu.playerqueue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.LibraryRepository
import moe.rukamori.archivetune.shared.ui.menu.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The shared Android player-menu body, sheet and playlist picker; hosts supply state and operations only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerQueueMenuSheet(
    selected: ContentOccurrence,
    track: PlayerQueueTrack,
    state: PlayerQueueMenuState,
    actions: PlayerQueueMenuActions,
    clock: PlayerQueueClock,
    artwork: PlayerQueueArtworkSlot,
    extraActions: List<OverflowAction>,
    library: LibraryRepository,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var choosingPlaylist by remember(selected.occurrenceId) { mutableStateOf(false) }
    var confirming by remember(selected.occurrenceId) { mutableStateOf<OverflowAction?>(null) }
    val boundActions = object : PlayerQueueMenuActions by actions {
        override fun addToPlaylist(track: PlayerQueueTrack) { choosingPlaylist = true }
    }
    val base = setOf("play_from", "start_radio", "play_next", "add_queue", "share", "copy_link", "view_artist", "view_album",
        "download", "downloading", "remove_download", "open_downloader", "speed_dial", "sleep_timer", "tempo_pitch", "equalizer", "acoustic_quality", "media_info", "track_info")
    val extras = extraActions.filterNot { it.id in base }
    fun perform(action: OverflowAction) {
        if (!action.enabled) return
        scope.launch {
            try { action.run(); if (action.dismissOnSuccess) onDismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure() }
        }
    }
    if (choosingPlaylist) {
        LocalPlaylistPickerContext.current?.let { context -> PlaylistPickerDialog(listOf(selected), context, library,
            onFinished = { choosingPlaylist = false; onDismiss() }) }
    } else ModalBottomSheet(onDismissRequest = onDismiss) {
        PlayerQueueMenu(PlayerQueueMenuTrigger.Player, track, state, boundActions, clock, ::SharedPlayerQueueIcon, artwork, onDismiss,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), songSection = {
                items(extras, key = { it.id }) { action ->
                    ListItem(headlineContent = { Text(stringResource(action.label)) },
                        supportingContent = action.description?.let { label -> { Text(stringResource(label)) } },
                        leadingContent = { Icon(painterResource(phoneMenuIcon(action.id)), null) },
                        modifier = Modifier.clickable(enabled = action.enabled) { if (action.confirm != null) confirming = action else perform(action) })
                }
            })
    }
    confirming?.let { action -> AlertDialog(onDismissRequest = { confirming = null },
        text = { action.confirm?.let { Text(stringResource(it, *action.confirmArgs.toTypedArray())) } },
        confirmButton = { TextButton(onClick = { confirming = null; perform(action) }) { Text(stringResource(Res.string.m6_confirm)) } },
        dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(Res.string.m6_cancel)) } }) }
}

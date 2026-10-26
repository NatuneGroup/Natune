/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueArtworkSlot
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueClock
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueDivider
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueMenu
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueMenuActions
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueMenuIcon
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueMenuIconSlot
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueMenuState
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueMenuTrigger
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueSurface
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueTrack
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.session.LyricsSessions
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Song-menu rows for the player and queue menu's song section. [facts] and [host] are the values the single-song menu
 * receives, so library, export, recommendation, cover and refetch commands run through the same host calls.
 */
data class PlayerQueueSongSection(val facts: SongMenuFacts, val host: SongMenuHost)

/**
 * Custom artwork rows for the player and queue menu's artwork section. [artworkUrl] and [songId] name the override
 * targets, and [hasCover] and [hasCanvas] come from the override snapshot the owner observes.
 */
data class PlayerQueueArtworkSection(
    val artworkUrl: String?,
    val songId: String,
    val hasCover: Boolean,
    val hasCanvas: Boolean,
    val host: CustomArtworkMenuHost,
)

/**
 * Lyric translation and romanisation rows for the player and queue menu's lyrics section. The inputs are the ones
 * [lyricsOverflowActions] takes; the owner keeps the lyric sessions and the edit store.
 */
data class PlayerQueueLyricsSection(
    val before: LyricsEditSnapshot,
    val sessions: LyricsSessions,
    val edits: LyricsMenuEdits,
    val isCurrent: () -> Boolean,
    val translateOrdinary: suspend (String) -> Map<Int, String>,
)

/**
 * One section row: the shared command, plus the semantic icon the host binds to it. The command keeps its own label,
 * enabled state, destructive tint and dismissal policy.
 */
internal class PlayerQueueOwnedRow(val action: OverflowAction, val icon: PlayerQueueMenuIcon)

/**
 * Song rows the player menu already draws from its own sections: start radio and the grid, speed dial and add to
 * playlist, the download rows, the external downloader, the album row, the sleep timer and the details row. The
 * shared builder also returns these, so they are dropped here and the menu keeps one copy of each.
 */
private val PLAYER_QUEUE_RENDERED_SONG_ROWS: Set<String> =
    setOf(
        "start_radio",
        "speed_dial",
        "download",
        "remove_download",
        "downloading",
        "open_downloader",
        "view_album",
        "sleep_timer",
        "track_info",
    )

/** The song section rows, in the shared builder's order, without the rows the menu already draws. */
internal fun playerQueueSongRows(section: PlayerQueueSongSection): List<PlayerQueueOwnedRow> =
    songLibraryAndDownloadActions(section.facts, section.host)
        .filterNot { it.id in PLAYER_QUEUE_RENDERED_SONG_ROWS }
        .map { PlayerQueueOwnedRow(it, songRowIcon(it.id, section.facts.inLibrary)) }

/** The artwork section rows: cover and canvas overrides, with removal only where the override exists. */
internal fun playerQueueArtworkRows(section: PlayerQueueArtworkSection): List<PlayerQueueOwnedRow> =
    customArtworkOverflowActions(
        artworkUrl = section.artworkUrl,
        songId = section.songId,
        hasCover = section.hasCover,
        hasCanvas = section.hasCanvas,
        host = section.host,
    ).map { PlayerQueueOwnedRow(it, artworkRowIcon(it.id)) }

private fun songRowIcon(id: String, inLibrary: Boolean): PlayerQueueMenuIcon =
    when (id) {
        "library" -> if (inLibrary) PlayerQueueMenuIcon.REMOVE else PlayerQueueMenuIcon.ADD
        "export_download", "download_cover" -> PlayerQueueMenuIcon.DOWNLOAD
        "recommendation_block" -> PlayerQueueMenuIcon.CLOSE
        "refetch" -> PlayerQueueMenuIcon.SYNC
        "remove_history", "remove_cache" -> PlayerQueueMenuIcon.DELETE
        else -> PlayerQueueMenuIcon.MUSIC_NOTE
    }

private fun artworkRowIcon(id: String): PlayerQueueMenuIcon =
    when (id) {
        "set_cover" -> PlayerQueueMenuIcon.ALBUM
        "set_canvas" -> PlayerQueueMenuIcon.MOTION_PHOTOS
        "remove_cover", "remove_canvas" -> PlayerQueueMenuIcon.DELETE
        else -> PlayerQueueMenuIcon.MUSIC_NOTE
    }

private fun lyricsRowIcon(id: String): PlayerQueueMenuIcon =
    when (id) {
        "cancel_translation" -> PlayerQueueMenuIcon.CLOSE
        "undo_translation" -> PlayerQueueMenuIcon.SYNC
        else -> PlayerQueueMenuIcon.MUSIC_NOTE
    }

/**
 * The shared player and queue menu with the M6 sections. It renders [PlayerQueueMenu] and fills its song, artwork and
 * lyrics slots from the M6 builders. A null section omits its rows. The baseline player menu draws none of the song,
 * artwork or lyric rows, so the owner passes those facts only where the surface should show them.
 *
 * Save Canvas is always wired: the menu's own row calls [PlayerQueueMenuActions.saveCanvas], which this composable
 * answers with the M6 canvas source picker. [canvasHost] supplies the sources and the save, and [onCanvasMessage]
 * receives the outcome text. The menu keeps its own Refetch canvas and Save Canvas rows, so no section repeats them.
 *
 * [actions] carries every other command unchanged. [onFailure] receives a failed M6 row, and [onDismiss] runs after
 * a row that dismisses the menu. Only one M6 row runs at a time, and a row with a confirmation asks first.
 */
@Composable
fun SharedPlayerQueueMenu(
    trigger: PlayerQueueMenuTrigger,
    track: PlayerQueueTrack,
    state: PlayerQueueMenuState,
    actions: PlayerQueueMenuActions,
    clock: PlayerQueueClock,
    icon: PlayerQueueMenuIconSlot,
    artwork: PlayerQueueArtworkSlot,
    canvasHost: CanvasSaveMenuHost,
    onCanvasMessage: (StringResource) -> Unit,
    onFailure: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    song: PlayerQueueSongSection? = null,
    customArtwork: PlayerQueueArtworkSection? = null,
    lyrics: PlayerQueueLyricsSection? = null,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<OverflowAction?>(null) }

    val canvasState = rememberCanvasSaveMenuState(track.mediaId, canvasHost, onCanvasMessage)
    val menuActions: PlayerQueueMenuActions =
        remember(actions, canvasState) {
            object : PlayerQueueMenuActions by actions {
                override fun saveCanvas(track: PlayerQueueTrack) {
                    canvasState.request()
                }
            }
        }

    val songRows = remember(song) { song?.let { playerQueueSongRows(it) } ?: emptyList() }
    val artworkRows = remember(customArtwork) { customArtwork?.let { playerQueueArtworkRows(it) } ?: emptyList() }

    fun perform(action: OverflowAction) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action.run()
                if (action.dismissOnSuccess) onDismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
            } finally {
                busy = false
            }
        }
    }

    val runRow: (OverflowAction) -> Unit = { action ->
        if (action.confirm != null) {
            confirming = action
        } else {
            perform(action)
        }
    }

    PlayerQueueMenu(
        trigger = trigger,
        track = track,
        state = state,
        actions = menuActions,
        clock = clock,
        icon = icon,
        artwork = artwork,
        onDismiss = onDismiss,
        modifier = modifier,
        songSection = { playerQueueOwnedSection(songRows, icon, runRow) },
        artworkSection = { playerQueueOwnedSection(artworkRows, icon, runRow) },
        lyricsSection = {
            if (lyrics != null) {
                item {
                    // Subscribing here recomposes the row list when a translation starts, stops or is undone.
                    lyrics.sessions.translation.state.collectAsState()
                    lyrics.edits.undo.collectAsState()
                    val rows =
                        lyricsOverflowActions(
                            before = lyrics.before,
                            sessions = lyrics.sessions,
                            edits = lyrics.edits,
                            isCurrent = lyrics.isCurrent,
                            translateOrdinary = lyrics.translateOrdinary,
                        ).map { PlayerQueueOwnedRow(it, lyricsRowIcon(it.id)) }
                    if (rows.isNotEmpty()) {
                        PlayerQueueOwnedSurface(rows = rows, icon = icon, onRun = runRow)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        },
    )

    CanvasSourcePicker(canvasState)

    confirming?.let { action ->
        val message = action.confirm
        AlertDialog(
            onDismissRequest = { confirming = null },
            text = { if (message != null) Text(stringResource(message, *action.confirmArgs.toTypedArray())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = null
                        perform(action)
                    },
                ) { Text(stringResource(Res.string.m6_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(Res.string.m6_cancel)) } },
        )
    }
}

/** Emits one surface of [rows] and the spacing the block owes the menu. An empty list emits nothing. */
private fun LazyListScope.playerQueueOwnedSection(
    rows: List<PlayerQueueOwnedRow>,
    icon: PlayerQueueMenuIconSlot,
    onRun: (OverflowAction) -> Unit,
) {
    if (rows.isEmpty()) return
    item { PlayerQueueOwnedSurface(rows = rows, icon = icon, onRun = onRun) }
    item { Spacer(modifier = Modifier.height(12.dp)) }
}

@Composable
private fun PlayerQueueOwnedSurface(
    rows: List<PlayerQueueOwnedRow>,
    icon: PlayerQueueMenuIconSlot,
    onRun: (OverflowAction) -> Unit,
) {
    PlayerQueueSurface(modifier = Modifier.padding(vertical = 6.dp)) {
        rows.forEachIndexed { index, row ->
            if (index > 0) PlayerQueueDivider()
            PlayerQueueOwnedActionRow(row = row, icon = icon, onRun = onRun)
        }
    }
}

/**
 * One shared command as a menu row. Unlike the menu's own rows it can be disabled: a disabled command keeps its label
 * dimmed and ignores taps, as the shared overflow menu does, instead of disappearing.
 */
@Composable
private fun PlayerQueueOwnedActionRow(
    row: PlayerQueueOwnedRow,
    icon: PlayerQueueMenuIconSlot,
    onRun: (OverflowAction) -> Unit,
) {
    val action = row.action
    val headlineColor =
        when {
            !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            action.destructive -> MaterialTheme.colorScheme.error
            else -> Color.Unspecified
        }
    val tint =
        when {
            !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            action.destructive -> MaterialTheme.colorScheme.error
            else -> LocalContentColor.current
        }
    ListItem(
        headlineContent = { Text(text = stringResource(action.label), color = headlineColor) },
        modifier = Modifier.fillMaxWidth().clickable(enabled = action.enabled) { onRun(action) },
        leadingContent = { icon(row.icon, tint, Modifier.size(24.dp)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

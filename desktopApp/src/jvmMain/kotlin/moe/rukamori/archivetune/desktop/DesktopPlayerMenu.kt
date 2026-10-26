package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyleKey
import moe.rukamori.archivetune.shared.domain.settings.toEnum

private const val YOUTUBE_MUSIC_WATCH_URL = "https://music.youtube.com/watch?v="

/**
 * The player's overflow menu on the desktop host. It keeps the baseline player menu rows that have an owner here: Copy
 * link and Sleep timer. Radio, source choice, playlists, speed dial, artist and album pages, downloads, the acoustic
 * graph, details, equalizer and tempo need owners this host does not connect, so those rows are left out instead of
 * appearing as no-ops.
 */
internal class DesktopPlayerMenuState {
    /** The song the menu is open for, or null while it is closed. */
    var target: ContentItem? by mutableStateOf(null)
        private set

    fun open(item: ContentItem) {
        target = item
    }

    fun dismiss() {
        target = null
    }
}

@Composable
internal fun DesktopPlayerMenuDialog(
    menu: DesktopPlayerMenuState,
    sleepSheet: DesktopSleepTimerSheetState,
    graph: DesktopSessionGraph,
) {
    val saved by graph.settings.snapshots.collectAsState()
    val item = menu.target ?: return
    val link = desktopPlayerMenuLink(item)
    // Apple Music shows the sleep timer as a pill in its queue sheet, so the baseline menu omits the row there.
    val sleepRowShown = saved?.get(PlayerDesignStyleKey).toEnum(PlayerDesignStyle.Default) != PlayerDesignStyle.APPLE_MUSIC
    AlertDialog(
        onDismissRequest = menu::dismiss,
        title = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (link != null) {
                    TextButton(
                        onClick = {
                            menu.dismiss()
                            copyToDesktopClipboard(link)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(DesktopStrings["player_menu_copy_link"]) }
                }
                if (sleepRowShown) {
                    TextButton(
                        onClick = {
                            menu.dismiss()
                            sleepSheet.open = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(DesktopStrings["player_menu_sleep_timer"]) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = menu::dismiss) { Text(DesktopStrings["cancel"]) } },
    )
}

/**
 * The baseline copies a YouTube Music watch link built from the media id. Only a YouTube song's id names such a page,
 * so songs of other providers and local files get no link row.
 */
internal fun desktopPlayerMenuLink(item: ContentItem): String? =
    item.ref.id
        .takeIf { item.ref.provider == "youtube" && item.ref.kind == ContentKind.SONG && it.isNotBlank() }
        ?.let { "$YOUTUBE_MUSIC_WATCH_URL$it" }

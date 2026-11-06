package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The two docked side panes. They hold Natune's own queue and lyrics, not a second copy of either. */
enum class DesktopPane { Queue, Lyrics }

/**
 * The desktop shell's own view state. It is UI state only: the queue, the lyrics document and the player stay with
 * their existing owners, and this class only remembers which columns the user asked for and how wide they are.
 *
 * It lives in the root composition so the shell, the player bar, the keyboard layer and the tray all toggle the same
 * panes, and it is never persisted: a new launch opens with the rail expanded and no pane covering the page.
 */
@Stable
class DesktopShellState(
    railExpanded: Boolean = true,
) {
    var railExpanded by mutableStateOf(railExpanded)

    var queueOpen by mutableStateOf(false)
        private set
    var lyricsOpen by mutableStateOf(false)
        private set

    /** The pane the user opened last; a narrow window keeps only this one docked. */
    var activePane by mutableStateOf<DesktopPane?>(null)
        private set

    var queueWidth by mutableStateOf(DesktopShellMetrics.paneDefaultWidth)
        private set
    var lyricsWidth by mutableStateOf(DesktopShellMetrics.paneDefaultWidth)
        private set

    var expandedPlayer by mutableStateOf(false)

    /** Close buttons and the shortcuts call this; the same pane toggles closed when it is already the active one. */
    fun toggle(pane: DesktopPane) {
        if (isOpen(pane) && activePane == pane) close(pane) else open(pane)
    }

    fun open(pane: DesktopPane) {
        when (pane) {
            DesktopPane.Queue -> queueOpen = true
            DesktopPane.Lyrics -> lyricsOpen = true
        }
        activePane = pane
    }

    fun close(pane: DesktopPane) {
        when (pane) {
            DesktopPane.Queue -> queueOpen = false
            DesktopPane.Lyrics -> lyricsOpen = false
        }
        if (activePane == pane) {
            activePane = when (pane) {
                DesktopPane.Queue -> if (lyricsOpen) DesktopPane.Lyrics else null
                DesktopPane.Lyrics -> if (queueOpen) DesktopPane.Queue else null
            }
        }
    }

    fun closePanes() {
        queueOpen = false
        lyricsOpen = false
        activePane = null
    }

    fun isOpen(pane: DesktopPane): Boolean = when (pane) {
        DesktopPane.Queue -> queueOpen
        DesktopPane.Lyrics -> lyricsOpen
    }

    fun width(pane: DesktopPane): Dp = when (pane) {
        DesktopPane.Queue -> queueWidth
        DesktopPane.Lyrics -> lyricsWidth
    }

    fun resize(pane: DesktopPane, width: Dp) {
        val clamped = width.coerceAtLeast(DesktopShellMetrics.paneMinWidth)
        when (pane) {
            DesktopPane.Queue -> queueWidth = clamped
            DesktopPane.Lyrics -> lyricsWidth = clamped
        }
    }

    /** A double click on the divider returns the pane to the 360 dp it opened with. */
    fun resetWidth(pane: DesktopPane) {
        resize(pane, DesktopShellMetrics.paneDefaultWidth)
    }

    fun toggleRail() {
        railExpanded = !railExpanded
    }
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.lyrics

/**
 * The lines a listener has picked for sharing. Selection is capped, and toggling a line that is already
 * picked removes it. A full selection ignores new picks rather than dropping old ones.
 */
data class LyricsSelectionState(val indices: Set<Int> = emptySet()) {
    val isActive: Boolean
        get() = indices.isNotEmpty()

    fun toggled(index: Int): LyricsSelectionState =
        when {
            index in indices -> LyricsSelectionState(indices - index)
            indices.size >= MAX_SELECTED_LINES -> this
            else -> LyricsSelectionState(indices + index)
        }

    /** Selected row indices in display order, which is the order the share card prints them in. */
    fun sortedIndices(): List<Int> = indices.sorted()

    companion object {
        const val MAX_SELECTED_LINES = 5
    }
}

/** What a tap on a lyric row does. */
sealed interface LyricsTapAction {
    data class Seek(val positionMs: Long) : LyricsTapAction

    data object ToggleSelection : LyricsTapAction

    data object None : LyricsTapAction
}

/**
 * Tap rules. While lines are being selected a tap only toggles selection. Otherwise a tap seeks to a row that has a
 * real time, and only when tap-to-seek is on. Unsynced text and untimed rows never seek.
 */
object LyricsTapPolicy {
    fun onTap(
        row: LyricsRenderRow,
        selectionActive: Boolean,
        tapToSeek: Boolean,
    ): LyricsTapAction =
        when {
            selectionActive -> LyricsTapAction.ToggleSelection
            tapToSeek && row.isSynced && row.timeMs > 0L -> LyricsTapAction.Seek(row.timeMs)
            else -> LyricsTapAction.None
        }
}

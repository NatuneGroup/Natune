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

/**
 * Whether the lyric clock has moved past a row: the feed sits at or beyond [LyricsRenderRow.scanEndMs], so a
 * finished line reads as passed through an instrumental gap and after the last line, whether or not any row
 * is currently active. Only timed rows can pass — unsynced text is static — and a row the playhead currently
 * sings is active, not passed, because its end is still ahead.
 */
internal fun isPassedLyricRow(row: LyricsRenderRow, feedPositionMs: Long): Boolean =
    row.isSynced && feedPositionMs >= row.scanEndMs

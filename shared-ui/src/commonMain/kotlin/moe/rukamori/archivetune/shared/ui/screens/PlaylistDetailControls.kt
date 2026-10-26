package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import moe.rukamori.archivetune.shared.domain.library.AutoPlaylistSongSortType
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.library.PlaylistSongSortType
import moe.rukamori.archivetune.shared.domain.library.SongSortType
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.component.LibrarySortHeader
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import moe.rukamori.archivetune.shared.ui.resources.*

/**
 * The baseline My Top filter. The owner keeps its time bounds (the baseline toTimeMillis mapping); the screen only
 * reports which window was chosen.
 */
enum class MyTopPeriod { ALL_TIME, DAY, WEEK, MONTH, YEAR }

/**
 * The sort, order or period control of one playlist page, retained from the baseline playlist screens. Each variant
 * carries the owner's stored value and the action that persists or re-queries it. The page never sorts or filters the
 * rows it already holds, because a sort computed over one loaded page would not match the owner's complete list.
 */
sealed interface PlaylistDetailControl {
    /**
     * A local playlist. The sort and its direction belong to that playlist alone and are written to its own row. The
     * lock is the shared playlist order lock, shown for an editable custom order. Move actions appear only for an
     * unlocked custom order of a fully loaded list while [reorderEnabled] holds. The owner clears [reorderEnabled]
     * while a selection or a search is active, as the baseline's reorder rule did.
     */
    data class LocalSongs(
        val sortType: PlaylistSongSortType,
        val descending: Boolean,
        val editable: Boolean,
        val locked: Boolean,
        val reorderEnabled: Boolean,
        val onSortChange: (PlaylistSongSortType, Boolean) -> Unit,
        val onLockedChange: (Boolean) -> Unit,
        /** Relative insertion into the owner's complete custom order, never a replacement by a loaded subset. */
        val move: (ContentOccurrence, ContentOccurrence, Boolean) -> Unit,
    ) : PlaylistDetailControl

    /** An auto playlist. The sort is the global auto-playlist preference shared by every auto playlist. */
    data class AutoSongs(
        val sortType: AutoPlaylistSongSortType,
        val descending: Boolean,
        val onSortChange: (AutoPlaylistSongSortType, Boolean) -> Unit,
    ) : PlaylistDetailControl

    /** A cached playlist. The sort is the global song sort that the Songs list uses as well. */
    data class CacheSongs(
        val sortType: SongSortType,
        val descending: Boolean,
        val onSortChange: (SongSortType, Boolean) -> Unit,
    ) : PlaylistDetailControl

    /** My Top. The period chooses the query window. It has no direction and no stored preference of its own. */
    data class TopPeriod(
        val period: MyTopPeriod,
        val onPeriodChange: (MyTopPeriod) -> Unit,
    ) : PlaylistDetailControl
}

/** The baseline cache list draws its sort only once it has songs; every other control is drawn with the header. */
internal fun PlaylistDetailControl.isShownFor(state: PagedContentState): Boolean =
    this !is PlaylistDetailControl.CacheSongs || state.items.isNotEmpty()

/**
 * Move actions exist only for an editable, unlocked custom order of a complete list, while reordering is enabled, so
 * the owner always receives a move against its full order.
 */
internal fun PlaylistDetailControl?.moveAction(state: PagedContentState): ((ContentOccurrence, ContentOccurrence, Boolean) -> Unit)? {
    val local = this as? PlaylistDetailControl.LocalSongs ?: return null
    val customOrder = local.editable && local.sortType == PlaylistSongSortType.CUSTOM && !local.locked
    return if (customOrder && local.reorderEnabled && state.complete) local.move else null
}

/** The control row above a playlist's rows, with the baseline's direction and lock rules. */
@Composable
internal fun PlaylistDetailControlRow(control: PlaylistDetailControl, modifier: Modifier = Modifier) {
    when (control) {
        is PlaylistDetailControl.LocalSongs -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            LibrarySortHeader(
                sortState = LibrarySortState(control.sortType, control.descending),
                sortTypes = PlaylistSongSortType.entries,
                sortTypeLabel = { type -> playlistSongSortLabel(type) },
                onSortStateChange = { next -> control.onSortChange(next.type, next.descending) },
                modifier = Modifier.weight(1f),
            )
            if (control.editable && control.sortType == PlaylistSongSortType.CUSTOM) {
                TextButton(
                    onClick = { control.onLockedChange(!control.locked) },
                    modifier = Modifier.testTag("playlist-order-lock"),
                ) {
                    Text(stringResource(if (control.locked) Res.string.m4_unlock_order else Res.string.m4_lock_order))
                }
            }
        }

        is PlaylistDetailControl.AutoSongs -> LibrarySortHeader(
            sortState = LibrarySortState(control.sortType, control.descending),
            sortTypes = AutoPlaylistSongSortType.entries,
            sortTypeLabel = { type -> autoSongSortLabel(type) },
            onSortStateChange = { next -> control.onSortChange(next.type, next.descending) },
            modifier = modifier,
        )

        is PlaylistDetailControl.CacheSongs -> LibrarySortHeader(
            sortState = LibrarySortState(control.sortType, control.descending),
            sortTypes = SongSortType.entries,
            sortTypeLabel = { type -> songSortLabel(type) },
            onSortStateChange = { next -> control.onSortChange(next.type, next.descending) },
            modifier = modifier,
        )

        is PlaylistDetailControl.TopPeriod -> LibrarySortHeader(
            sortState = LibrarySortState(control.period, descending = false),
            sortTypes = MyTopPeriod.entries,
            sortTypeLabel = { period -> periodLabel(period) },
            onSortStateChange = { next -> control.onPeriodChange(next.type) },
            modifier = modifier,
            showDirection = false,
        )
    }
}

/**
 * The neighbour actions of one row in an unlocked custom order. Each move names an adjacent loaded row, so the owner
 * inserts the song beside that row in its complete order; the first and last loaded rows have one neighbour only.
 */
@Composable
internal fun PlaylistMoveControls(
    rows: List<ContentOccurrence>,
    index: Int,
    move: (ContentOccurrence, ContentOccurrence, Boolean) -> Unit,
) {
    val row = rows[index]
    rows.getOrNull(index - 1)?.let { above ->
        TextButton(
            onClick = { move(row, above, false) },
            modifier = Modifier.testTag("playlist-song-up-${row.occurrenceId}"),
        ) {
            Text(stringResource(Res.string.m4_move_up))
        }
    }
    rows.getOrNull(index + 1)?.let { below ->
        TextButton(
            onClick = { move(row, below, true) },
            modifier = Modifier.testTag("playlist-song-down-${row.occurrenceId}"),
        ) {
            Text(stringResource(Res.string.m4_move_down))
        }
    }
}

private fun playlistSongSortLabel(type: PlaylistSongSortType): StringResource =
    when (type) {
        PlaylistSongSortType.CUSTOM -> Res.string.m4_custom_order
        PlaylistSongSortType.CREATE_DATE -> Res.string.m4_sort_date_added
        PlaylistSongSortType.NAME -> Res.string.m4_name
        PlaylistSongSortType.ARTIST -> Res.string.m4_sort_artist
        PlaylistSongSortType.PLAY_TIME -> Res.string.m4_sort_play_time
    }

private fun autoSongSortLabel(type: AutoPlaylistSongSortType): StringResource =
    when (type) {
        AutoPlaylistSongSortType.CREATE_DATE -> Res.string.m4_sort_date_added
        AutoPlaylistSongSortType.NAME -> Res.string.m4_name
        AutoPlaylistSongSortType.ARTIST -> Res.string.m4_sort_artist
        AutoPlaylistSongSortType.PLAY_TIME -> Res.string.m4_sort_play_time
    }

private fun songSortLabel(type: SongSortType): StringResource =
    when (type) {
        SongSortType.CREATE_DATE -> Res.string.m4_sort_date_added
        SongSortType.NAME -> Res.string.m4_name
        SongSortType.ARTIST -> Res.string.m4_sort_artist
        SongSortType.PLAY_TIME -> Res.string.m4_sort_play_time
    }

private fun periodLabel(period: MyTopPeriod): StringResource =
    when (period) {
        MyTopPeriod.ALL_TIME -> Res.string.m4_period_all_time
        MyTopPeriod.DAY -> Res.string.m4_period_past_24_hours
        MyTopPeriod.WEEK -> Res.string.m4_period_past_week
        MyTopPeriod.MONTH -> Res.string.m4_period_past_month
        MyTopPeriod.YEAR -> Res.string.m4_period_past_year
    }

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m4_drag_handle
import moe.rukamori.archivetune.shared.ui.resources.m4_drag_to_reorder
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** The band at each list edge where a held drag scrolls the list. */
private val DRAG_EDGE_ZONE = 56.dp

/** The most the list moves in one frame of an edge scroll; a row deeper in the band scrolls faster. */
private val DRAG_SCROLL_STEP = 12.dp

private const val MIN_SCROLL_FRACTION = 0.2f

/**
 * The lazy item key of one occurrence. It names the occurrence, not the song, so two copies of the same media in one
 * playlist keep separate rows while one of them is dragged.
 */
internal fun playlistRowKey(occurrenceId: String): String = "m4-row-$occurrenceId"

/**
 * The retained local-playlist drag. A drag starts only from the row's handle, follows the pointer, and reorders the
 * rows on screen live. The owner callback runs once, on a completed drag that changed the position of its row. A
 * cancelled drag, a drag whose owner order changed, and a drag that lost its admission commit nothing.
 *
 * The rows on screen are a preview of the owner's complete order. They return to the owner's order as soon as the
 * drag ends, so the screen never shows a position the owner has not stored.
 */
internal class PlaylistRowDrag(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val density: Density,
    private val rows: State<List<ContentOccurrence>>,
    private val admitted: State<Boolean>,
    private val onMove: State<((ContentOccurrence, ContentOccurrence, Boolean) -> Unit)?>,
    private val padding: State<PaddingValues>,
) {
    private var viewport: LayoutCoordinates? = null
    private var sourceId: String? by mutableStateOf<String?>(null)
    private var preview: List<ContentOccurrence>? by mutableStateOf<List<ContentOccurrence>?>(null)
    private var translation: Float by mutableFloatStateOf(0f)
    private var origin: List<ContentOccurrence> = emptyList()
    private var draggedHeight = 0
    private var grabOffset = 0f
    private var pointerRootY = 0f
    private var scrollStep = 0f
    private var autoScroll: Job? = null

    /**
     * The rows in the order on screen: the owner order, or the live preview while a drag is in progress over that same
     * owner order. A preview that outlived an owner change is never drawn.
     */
    fun displayRows(owner: List<ContentOccurrence>): List<ContentOccurrence> = preview?.takeIf { origin == owner } ?: owner

    /** The dragged row follows the pointer and draws above its neighbours. Every other row keeps its layout slot. */
    fun rowModifier(occurrenceId: String, background: Color): Modifier =
        if (sourceId != occurrenceId) {
            Modifier
        } else {
            Modifier
                .zIndex(1f)
                .offset { IntOffset(0, translation.roundToInt()) }
                .background(background)
        }

    fun attachViewport(coordinates: LayoutCoordinates) {
        viewport = coordinates
    }

    /** Begins a drag from [handle]. It is ignored unless the owner admits reordering and the row is still listed. */
    fun start(occurrenceId: String, handle: LayoutCoordinates?, local: Offset) {
        if (sourceId != null || !admitted.value) return
        val anchor = handle?.takeIf { it.isAttached } ?: return
        val frame = viewport?.takeIf { it.isAttached } ?: return
        val current = rows.value
        if (current.none { it.occurrenceId == occurrenceId }) return
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == playlistRowKey(occurrenceId) } ?: return
        val root = anchor.localToRoot(local)
        origin = current
        preview = current
        draggedHeight = item.size
        grabOffset = root.y - frame.localToRoot(Offset.Zero).y - item.offset
        pointerRootY = root.y
        translation = 0f
        sourceId = occurrenceId
    }

    /** Moves the dragged row to the pointer's new position in [handle]'s coordinates. */
    fun update(handle: LayoutCoordinates?, local: Offset) {
        if (sourceId == null) return
        val anchor = handle?.takeIf { it.isAttached } ?: return
        pointerRootY = anchor.localToRoot(local).y
        place()
    }

    /**
     * Ends the drag. A committed drag that moved its row calls the owner once with the source, the row it now sits
     * beside and whether it sits after that row. Any other end clears the preview and commits nothing.
     */
    fun finish(commit: Boolean) {
        val id = sourceId ?: return
        autoScroll?.cancel()
        autoScroll = null
        val before = origin
        val after = preview
        sourceId = null
        preview = null
        translation = 0f
        scrollStep = 0f
        origin = emptyList()
        if (!commit || after == null || !admitted.value || rows.value != before) return
        val from = before.indexOfFirst { it.occurrenceId == id }
        val to = after.indexOfFirst { it.occurrenceId == id }
        val move = onMove.value ?: return
        if (from < 0 || to < 0 || from == to) return
        // The final index names the owner row now beside the source. Moving down places the source after it.
        move(before[from], before[to], from < to)
    }

    fun cancel() = finish(commit = false)

    fun onRowsChanged(current: List<ContentOccurrence>) {
        if (sourceId != null && current != origin) finish(commit = false)
    }

    fun onAdmissionChanged(isAdmitted: Boolean) {
        if (!isAdmitted) finish(commit = false)
    }

    private fun place() {
        val id = sourceId ?: return
        val order = preview ?: return
        val frame = viewport?.takeIf { it.isAttached } ?: return
        val layout = listState.layoutInfo
        val insets = padding.value
        val viewportHeight = layout.viewportSize.height.toFloat()
        val topInset = with(density) { insets.calculateTopPadding().toPx() }
        val bottomInset = with(density) { insets.calculateBottomPadding().toPx() }
        val edge = with(density) { DRAG_EDGE_ZONE.toPx() }
        val step = with(density) { DRAG_SCROLL_STEP.toPx() }
        val index = order.indexOfFirst { it.occurrenceId == id }
        val top = pointerRootY - frame.localToRoot(Offset.Zero).y - grabOffset
        val upDepth = topInset + edge - top
        val downDepth = top + draggedHeight - (viewportHeight - bottomInset - edge)
        // The list scrolls only while a neighbour lies beyond the band, so a held row never drives the list past its end.
        scrollStep = when {
            index < 0 -> 0f
            upDepth > 0f && index > 0 -> -step * (upDepth / edge).coerceIn(MIN_SCROLL_FRACTION, 1f)
            downDepth > 0f && index < order.lastIndex -> step * (downDepth / edge).coerceIn(MIN_SCROLL_FRACTION, 1f)
            else -> 0f
        }
        val item = layout.visibleItemsInfo.firstOrNull { it.key == playlistRowKey(id) }
        if (item != null && index >= 0) {
            val center = top + item.size / 2f
            var layoutTop = item.offset.toFloat()
            val below = order.getOrNull(index + 1)?.let { layout.rowItem(it) }
            val above = order.getOrNull(index - 1)?.let { layout.rowItem(it) }
            if (below != null && center > below.offset + below.size / 2f) {
                preview = order.swapped(index, index + 1)
                layoutTop += below.size
            } else if (above != null && center < above.offset + above.size / 2f) {
                preview = order.swapped(index, index - 1)
                layoutTop -= above.size
            }
            translation = top - layoutTop
        }
        if (scrollStep != 0f) startAutoScroll()
    }

    /** Scrolls the list one frame at a time while the pointer is held in an edge band. It stops with the drag. */
    private fun startAutoScroll() {
        if (autoScroll?.isActive == true) return
        autoScroll = scope.launch {
            while (sourceId != null && scrollStep != 0f) {
                withFrameNanos { }
                val consumed = listState.scrollBy(scrollStep)
                if (consumed == 0f) break
                place()
            }
        }
    }
}

/**
 * Holds one drag handle's layout. The gesture reads it when a drag starts and when the pointer moves, so the pointer
 * position is taken in screen coordinates and stays correct while the row moves under the finger.
 */
internal class PlaylistDragAnchor {
    var coordinates: LayoutCoordinates? = null
}

@Composable
internal fun rememberPlaylistRowDrag(
    listState: LazyListState,
    rows: List<ContentOccurrence>,
    contentPadding: PaddingValues,
    onMove: ((ContentOccurrence, ContentOccurrence, Boolean) -> Unit)?,
): PlaylistRowDrag {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rowsState = rememberUpdatedState(rows)
    val admitted = rememberUpdatedState(onMove != null)
    val moveState = rememberUpdatedState(onMove)
    val paddingState = rememberUpdatedState(contentPadding)
    val drag = remember(listState, scope, density) {
        PlaylistRowDrag(listState, scope, density, rowsState, admitted, moveState, paddingState)
    }
    LaunchedEffect(drag, rows) { drag.onRowsChanged(rows) }
    LaunchedEffect(drag, onMove != null) { drag.onAdmissionChanged(onMove != null) }
    DisposableEffect(drag) { onDispose { drag.cancel() } }
    return drag
}

/** Records the list viewport, so a pointer position can be expressed relative to the rows on screen. */
internal fun Modifier.playlistRowDragViewport(drag: PlaylistRowDrag): Modifier =
    onGloballyPositioned { drag.attachViewport(it) }

/**
 * The baseline drag handle, drawn in a row's trailing controls only while the owner admits reordering. It is the only
 * gesture source, so list scrolling and row taps keep their own behaviour. Its cancelled gesture commits nothing.
 */
@Composable
internal fun PlaylistDragHandle(occurrenceId: String, drag: PlaylistRowDrag) {
    val anchor = remember(occurrenceId) { PlaylistDragAnchor() }
    IconButton(
        onClick = {},
        modifier = Modifier
            .testTag("playlist-song-drag-$occurrenceId")
            .onGloballyPositioned { anchor.coordinates = it }
            .pointerInput(occurrenceId, drag) {
                detectDragGestures(
                    onDragStart = { local -> drag.start(occurrenceId, anchor.coordinates, local) },
                    onDrag = { change, _ -> drag.update(anchor.coordinates, change.position) },
                    onDragEnd = { drag.finish(commit = true) },
                    onDragCancel = { drag.finish(commit = false) },
                )
            },
    ) {
        Icon(
            painter = painterResource(Res.drawable.m4_drag_handle),
            contentDescription = stringResource(Res.string.m4_drag_to_reorder),
        )
    }
}

private fun LazyListLayoutInfo.rowItem(row: ContentOccurrence): LazyListItemInfo? {
    val key = playlistRowKey(row.occurrenceId)
    return visibleItemsInfo.firstOrNull { it.key == key }
}

private fun List<ContentOccurrence>.swapped(first: Int, second: Int): List<ContentOccurrence> {
    val next = toMutableList()
    next[first] = this[second]
    next[second] = this[first]
    return next
}

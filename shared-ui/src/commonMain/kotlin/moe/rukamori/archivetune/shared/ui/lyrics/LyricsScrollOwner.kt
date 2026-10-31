package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * State behind the single auto-scroll owner. A listener scroll pauses following and the hold timer resumes it.
 * A seek or a new session jumps to the playhead and resumes following at once.
 */
@Stable
internal class LyricsScrollOwner {
    /**
     * True while the list follows the playhead. A listener scroll clears it. It is snapshot state, so the look can
     * unblur its rows while the list is held.
     */
    var following: Boolean by mutableStateOf(true)

    /** Counts listener scrolls. Each change restarts the hold, so the hold runs from the last scroll. */
    var holdGeneration by mutableIntStateOf(0)
        private set

    /** Counts resumes. A change re-runs the follow effect, which returns the list to the playhead. */
    var resumeGeneration by mutableIntStateOf(0)
        private set

    /** The last discontinuity count handled. A different count means a seek or a new session. */
    var handledDiscontinuity: Int = 0

    /** The line the list last followed. A different line is a line change, which may settle rather than animate. */
    var handledPrimary: Int = -1

    fun onListenerScroll() {
        following = false
        holdGeneration += 1
    }

    fun resume() {
        following = true
        resumeGeneration += 1
    }
}

@Composable
internal fun rememberLyricsScrollOwner(): LyricsScrollOwner = remember { LyricsScrollOwner() }

/**
 * The only code that scrolls the lyric list. A listener scroll starts the hold, and when the hold expires the
 * list follows the playhead again. A seek or a session change jumps. Ordinary playhead movement animates, unless
 * reduced motion is on, in which case it also jumps. For a look that holds while dragged, the hold does not
 * start until the drag ends, so a finger resting on the list is not followed out from under it.
 *
 * A line change on a look with [settle] moves the list at once and lets the rows glide from where they were, so the
 * change ripples outward from the sung line. Every other move keeps the single animated scroll.
 */
@Composable
internal fun LyricsAutoFollow(
    owner: LyricsScrollOwner,
    listState: LazyListState,
    primary: Int,
    discontinuity: Int,
    visible: Boolean,
    reduceMotion: Boolean,
    profile: LyricsModeProfile,
    settle: LyricsLineSettle? = null,
) {
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    val holdOpen = dragged && profile.holdsWhileDragged
    LaunchedEffect(owner.holdGeneration, holdOpen) {
        if (owner.holdGeneration == 0 || holdOpen) return@LaunchedEffect
        delay(profile.manualScrollHoldMs)
        owner.resume()
    }
    // A hand on the list or a hidden surface ends a settle at once, so nothing glides under a finger.
    LaunchedEffect(dragged, visible) {
        if (dragged || !visible) settle?.clear()
    }
    val settleGeneration = settle?.generation ?: 0
    LaunchedEffect(settleGeneration) {
        if (settle != null && settleGeneration > 0) settle.runSettle()
    }
    LaunchedEffect(primary, discontinuity, owner.resumeGeneration, visible) {
        val jump = discontinuity != owner.handledDiscontinuity
        val moved = primary != owner.handledPrimary
        owner.handledDiscontinuity = discontinuity
        owner.handledPrimary = primary
        if (jump) owner.following = true
        if (!visible || !owner.following || primary < 0) return@LaunchedEffect
        val viewportHeight = listState.awaitViewportHeight()
        val anchor = -(viewportHeight * profile.anchorFraction).roundToInt()
        // Only a line that is laid out now can settle; a line off screen animates as it always did.
        val fromTop = if (settle != null && moved && !jump && !dragged) listState.itemTopOrNull(primary) else null
        when {
            jump || reduceMotion -> {
                settle?.clear()
                listState.scrollToItem(primary, anchor)
            }
            settle != null && fromTop != null -> {
                settle.start(primary, fromTop)
                listState.scrollToItem(primary, anchor)
            }
            else -> listState.animateScrollToItem(primary, anchor)
        }
    }
}

/** Where the row starts in the viewport now, or null while it is not laid out. */
private fun LazyListState.itemTopOrNull(index: Int): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset

/** The list is not measured on its first frame, so the anchor waits for a real viewport height. */
private suspend fun LazyListState.awaitViewportHeight(): Int {
    val height = layoutInfo.viewportSize.height
    if (height > 0) return height
    return snapshotFlow { layoutInfo.viewportSize.height }.first { it > 0 }
}

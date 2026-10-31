package moe.rukamori.archivetune.shared.ui.playerstyles

import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The text stays still for this long on a new track before its single pass, so the first words can be read before
 * anything moves.
 */
internal const val TRACK_MARQUEE_DELAY_MS = 3000L

/** Gap between the end of the text and the point where the pass rests. Metrolist's single-line marquee spacing. */
private val TrackMarqueeSpacing = 30.dp

/**
 * Scrolls overflowing player text exactly once per [trackKey]. The pass starts after [TRACK_MARQUEE_DELAY_MS] and
 * then rests at the start, so no frame clock keeps running behind the player.
 *
 * [enabled] must already include the visible, resumed and motion gates (see `motionAllowed`). Losing them detaches the
 * marquee, and a later return does not restart a pass that already ran for this track. A pass cut short by a lifecycle
 * change is not retried either, which keeps a paused or hidden player from scrolling in the background.
 */
@Composable
internal fun Modifier.trackMarquee(trackKey: Any?, enabled: Boolean): Modifier {
    var passStarted by remember(trackKey) { mutableStateOf(false) }
    var scrolling by remember(trackKey) { mutableStateOf(false) }
    LaunchedEffect(trackKey, enabled) {
        if (!enabled) {
            scrolling = false
        } else if (!passStarted) {
            delay(TRACK_MARQUEE_DELAY_MS)
            passStarted = true
            scrolling = true
        }
    }
    return if (scrolling) {
        basicMarquee(
            iterations = 1,
            animationMode = MarqueeAnimationMode.Immediately,
            initialDelayMillis = 0,
            spacing = MarqueeSpacing(TrackMarqueeSpacing),
        )
    } else {
        this
    }
}

package moe.rukamori.archivetune.shared.ui.playerstyles

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.player.*

/**
 * Looper look: editorial title block, a shuffle/repeat utility row, then the artwork pager.
 * Double-tapping the artwork's left/right half seeks backward/forward through the player's
 * step actions (layout-direction aware), like the original's tap zones; the scrubber stays
 * the thick slider and the transport is the square native row. Landscape splits artwork and
 * controls side-by-side so the full-width pager never swallows the column's scroll.
 */
@Composable
internal fun LooperPlayer(c: PlayerRenderContext) {
    val direction = LocalLayoutDirection.current
    val actions by rememberUpdatedState(c.actions)
    val canSeek by rememberUpdatedState(c.state.canSeek && c.state.playback?.isSeekable == true)
    val artwork: @Composable (Modifier) -> Unit = { modifier ->
        PlayerArtworkPager(c, modifier.pointerInput(direction) {
            detectTapGestures(onDoubleTap = { offset ->
                if (!canSeek) return@detectTapGestures
                val forward = if (direction == LayoutDirection.Ltr) offset.x >= size.width / 2 else offset.x < size.width / 2
                actions.onAction(if (forward) PlayerAction.SEEK_FORWARD else PlayerAction.SEEK_BACKWARD)
            })
        })
    }
    PlayerColumn(Modifier.testTag("looper-content")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ActionButton(PlayerAction.COLLAPSE, c); ActionButton(PlayerAction.MORE, c)
        }
        if (c.landscape) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                artwork(Modifier.weight(1f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlayerMetadata(c, editorial = true)
                    LooperUtilityRow(c)
                    LooperTransport(c)
                }
            }
        } else {
            PlayerMetadata(c, editorial = true)
            LooperUtilityRow(c)
            artwork(Modifier.fillMaxWidth())
            LooperTransport(c)
        }
    }
}

@Composable
private fun LooperUtilityRow(c: PlayerRenderContext) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ActionButton(PlayerAction.SHUFFLE, c); ActionButton(PlayerAction.REPEAT, c)
    }
}

@Composable
private fun LooperTransport(c: PlayerRenderContext) {
    PlayerSeek(c, native = PlayerSliderStyle.Thick)
    NativeTransport(c, square = true)
    c.slots.compactLyrics(Modifier.fillMaxWidth().height(64.dp))
    BottomControls(c)
}

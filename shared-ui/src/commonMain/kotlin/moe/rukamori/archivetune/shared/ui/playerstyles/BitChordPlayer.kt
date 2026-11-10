package moe.rukamori.archivetune.shared.ui.playerstyles

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.player.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * BitChord look: header chrome, sleeve beside (portrait: stacked) the metadata, the swept
 * lyric strip pinned directly above the scrubber with a chevron into the lyrics panel,
 * square transport, volume row and a segmented pill of bottom controls above a short queue
 * surface — matching the original's pill/segmented chrome and lyric-strip ordering.
 */
@Composable
internal fun BitChordPlayer(c: PlayerRenderContext) {
    val playback = c.state.playback
    val playing = playback?.playWhenReady == true && playback.isEnded != true
    // The sleeve shrinks back while paused, with the Android BitChord player's spring.
    val sleeveScale = animateFloatAsState(
        if (playing) 1f else .86f,
        if (c.motionActive) PlayerMotionSpecs.bounce() else snap(),
        label = "bitChordPauseScale",
    )
    PlayerColumn(Modifier.testTag("bitchord-content")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ActionButton(PlayerAction.COLLAPSE, c); ActionButton(PlayerAction.MORE, c)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayerSleeve(c, Modifier.weight(1f).graphicsLayer {
                scaleX = sleeveScale.value
                scaleY = sleeveScale.value
            })
            Column(Modifier.then(if (c.landscape) Modifier.weight(1f) else Modifier)) {
                if (c.landscape) {
                    PlayerMetadata(c, centered = true)
                    BitChordLyricStrip(c)
                    PlayerSeek(c)
                    NativeTransport(c, square = true)
                }
                Row { ActionButton(PlayerAction.LIKE, c); ActionButton(PlayerAction.SHARE, c) }
            }
        }
        if (!c.landscape) {
            PlayerMetadata(c, centered = true)
            BitChordLyricStrip(c)
            PlayerSeek(c)
            NativeTransport(c, square = true)
        }
        PlayerVolume(c)
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) { BottomControls(c) }
        c.slots.queue(Modifier.fillMaxWidth().height(140.dp))
    }
}

@Composable
private fun BitChordLyricStrip(c: PlayerRenderContext) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        c.slots.compactLyrics(Modifier.weight(1f).height(48.dp))
        PlayerIcon(PlayerSymbol.EXPAND, stringResource(Res.string.m5_lyrics),
            { c.interaction.panel = PlayerPanel.LYRICS })
    }
}

package moe.rukamori.archivetune.shared.ui.playerstyles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.player.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

private val TikTokScrimTop = 0.55f
private val TikTokScrimBottom = 0.9f

/**
 * Feed-style player modelled on the original TIKTOK look: full-bleed vertical artwork pages,
 * a right action rail, a bottom-left caption block and a thin scrubber. The pager is the
 * transport — a settled page selects the occurrence; tapping the feed toggles playback.
 * [immersive] fades all chrome like the original's hidden-UI mode; the shared shell keeps
 * panel/back ownership, so exiting immersive surfaces a back affordance instead of the system.
 */
@Composable
internal fun TikTokPlayer(c: PlayerRenderContext) {
    var immersive by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().testTag("tiktok-content")) {
        PlayerArtworkPager(c, Modifier.fillMaxSize().pointerInput(c.state.canSeek) {
            detectTapGestures(onTap = { c.actions.playPause() })
        }, vertical = true)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = TikTokScrimTop),
                    0.18f to Color.Transparent,
                    0.72f to Color.Transparent,
                    1f to Color.Black.copy(alpha = TikTokScrimBottom),
                )
            )
        )
        AnimatedVisibility(
            !immersive,
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton(PlayerAction.COLLAPSE, c)
                PlayerIcon(PlayerSymbol.EXPAND, stringResource(Res.string.m5_fullscreen), { immersive = true })
            }
        }
        AnimatedVisibility(
            !immersive,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Artist avatar: tapping opens the artist page like the original rail entry.
                Box(Modifier.size(46.dp).clip(CircleShape).testTag("tiktok-artist-avatar")) {
                    c.slots.artwork(c.state.playback?.occurrenceId, Modifier.fillMaxSize())
                    Box(Modifier.matchParentSize().background(Color.Transparent).testTag("tiktok-avatar-hit")
                        .pointerInput(Unit) { detectTapGestures(onTap = { c.actions.onAction(PlayerAction.ARTIST) }) })
                }
                ActionButton(PlayerAction.LIKE, c)
                PlayerIcon(PlayerSymbol.QUEUE, stringResource(Res.string.m5_queue),
                    { c.interaction.panel = PlayerPanel.QUEUE })
                PlayerIcon(PlayerSymbol.LYRICS, stringResource(Res.string.m5_lyrics),
                    { c.interaction.panel = PlayerPanel.LYRICS })
                ActionButton(PlayerAction.SHARE, c)
                ActionButton(PlayerAction.MORE, c)
            }
        }
        AnimatedVisibility(
            !immersive,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 60.dp),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    c.state.title.ifBlank { stringResource(Res.string.m5_no_track) },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { c.actions.onAction(PlayerAction.TITLE) }) },
                )
                Text(
                    c.state.artist,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { c.actions.onAction(PlayerAction.ARTIST) }) },
                )
                c.slots.compactLyrics(Modifier.fillMaxWidth().height(48.dp))
                PlayerSeek(c, native = PlayerSliderStyle.Simple)
            }
        }
        if (immersive) {
            PlayerIcon(
                PlayerSymbol.BACK, stringResource(Res.string.m5_back), { immersive = false },
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

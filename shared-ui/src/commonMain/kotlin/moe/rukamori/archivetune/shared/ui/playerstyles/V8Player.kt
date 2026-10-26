package moe.rukamori.archivetune.shared.ui.playerstyles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.player.PlayerAction
import moe.rukamori.archivetune.shared.ui.player.PlayerRenderContext
import moe.rukamori.archivetune.shared.ui.player.PlayerSymbol

private val v8Text = Color.White

/** V8 look: blurred backdrop under a heavy scrim, flat slider, pill transport. */
@Composable
internal fun V8Player(c: PlayerRenderContext) {
    val id = c.state.playback?.occurrenceId
    Box(Modifier.fillMaxSize()) {
        if (c.optical.artworkBlur) {
            c.slots.artwork(
                id,
                Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = 1.2f; scaleY = 1.2f }
                    .blur(48.dp),
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .52f)))
        if (c.landscape) {
            Row(Modifier.fillMaxSize().padding(36.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(.44f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    V8Artwork(c, Modifier.fillMaxWidth())
                }
                V8Controls(c, Modifier.weight(1f).fillMaxHeight().padding(start = 36.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PlayingFromHeader(c, v8Text, Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    style = MaterialTheme.typography.titleMedium)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    V8Artwork(c, Modifier.fillMaxWidth())
                }
                V8Controls(c, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun V8Artwork(c: PlayerRenderContext, modifier: Modifier) {
    val id = c.state.playback?.occurrenceId
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).playerSeekDoubleTap(c),
    ) {
        if (c.state.canvasAvailable) c.slots.canvas(id, c.surfaces.artwork, Modifier.fillMaxSize())
        else c.slots.artwork(id, Modifier.fillMaxSize())
    }
}

@Composable
private fun V8Controls(c: PlayerRenderContext, modifier: Modifier) {
    val white = v8Text
    Column(modifier.verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TitleLine(c, color = white, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                ArtistLine(c, color = white, style = MaterialTheme.typography.titleMedium, alpha = .7f)
            }
            Spacer(Modifier.width(12.dp))
            StyleIconButton(c, PlayerSymbol.HEART, PlayerAction.LIKE, size = 48.dp, iconSize = 24.dp,
                container = white.copy(alpha = .16f),
                iconColor = if (c.state.liked) MaterialTheme.colorScheme.error else white,
                selected = c.state.liked, shape = CircleShape)
        }
        Spacer(Modifier.height(18.dp))
        FlatSeekBar(c, white)
        SeekTimeRow(c, white, remaining = true, alpha = .6f, center = { FormatChip(c, white) })
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            SkipButton(c, next = false, size = 64.dp, iconSize = 44.dp,
                iconColor = white.copy(alpha = if (c.state.canPrevious) 1f else .4f))
            StylePlayButton(c, size = 72.dp, iconSize = 52.dp, container = white.copy(alpha = .16f),
                iconColor = white, shape = CircleShape, spinnerSize = 44.dp)
            SkipButton(c, next = true, size = 64.dp, iconSize = 44.dp,
                iconColor = white.copy(alpha = if (c.state.canNext) 1f else .4f))
        }
        Spacer(Modifier.height(18.dp))
        VolumeRow(c, white)
        Spacer(Modifier.height(16.dp))
    }
}

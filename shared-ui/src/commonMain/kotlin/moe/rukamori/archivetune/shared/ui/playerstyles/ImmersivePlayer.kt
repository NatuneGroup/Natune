package moe.rukamori.archivetune.shared.ui.playerstyles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.shared.ui.player.PlayerAction
import moe.rukamori.archivetune.shared.ui.player.PlayerBackgroundStyle
import moe.rukamori.archivetune.shared.ui.player.PlayerPanel
import moe.rukamori.archivetune.shared.ui.player.PlayerRenderContext
import moe.rukamori.archivetune.shared.ui.player.PlayerSymbol
import moe.rukamori.archivetune.shared.ui.player.resolveBackground
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m5_lyrics
import moe.rukamori.archivetune.shared.ui.resources.m5_more
import moe.rukamori.archivetune.shared.ui.resources.m5_queue
import org.jetbrains.compose.resources.stringResource

private val v7Text = Color.White

/** Bottom fade so the stage artwork dissolves into the scrim exactly like the original. */
private fun Modifier.bottomFade(heightFraction: Float = .5f): Modifier = drawWithContent {
    drawContent()
    drawRect(
        Brush.verticalGradient(
            listOf(Color.Transparent, Color.Black),
            startY = size.height * (1f - heightFraction),
        ),
        blendMode = BlendMode.DstIn,
    )
}

/** V7 immersive look: monochrome chrome over a blurred stage artwork. */
@Composable
internal fun ImmersivePlayer(c: PlayerRenderContext) {
    val white = v7Text
    // An explicit player background is drawn beneath this look; the black field and blurred cover would hide it.
    val explicitBackground = c.preferences.style.resolveBackground(c.preferences.background) != PlayerBackgroundStyle.DEFAULT
    Box(Modifier.fillMaxSize().then(if (explicitBackground) Modifier else Modifier.background(Color.Black))) {
        val id = c.state.playback?.occurrenceId
        if (c.optical.artworkBlur && !explicitBackground) {
            c.slots.artwork(
                id,
                Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = 1.12f; scaleY = 1.12f }
                    .blur(64.dp),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = .1f), Color.Black.copy(alpha = .92f))),
            ),
        )
        if (c.landscape) {
            Row(Modifier.fillMaxSize().padding(24.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.fillMaxWidth(.8f).clip(RoundedCornerShape(12.dp)),
                    ) {
                        if (c.state.canvasAvailable) c.slots.canvas(id, c.surfaces.artwork, Modifier.fillMaxWidth())
                        else c.slots.artwork(id, Modifier.fillMaxWidth())
                    }
                }
                ImmersiveControls(c, white, Modifier.weight(1f).fillMaxHeight().padding(start = 30.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                PlayingFromHeader(c, white, Modifier.fillMaxWidth().padding(top = 8.dp),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), alpha = .8f)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bottomFade()) {
                        if (c.state.canvasAvailable) c.slots.canvas(id, c.surfaces.artwork, Modifier.fillMaxWidth())
                        else c.slots.artwork(id, Modifier.fillMaxWidth())
                    }
                }
                ImmersiveControls(c, white, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ImmersiveControls(c: PlayerRenderContext, white: Color, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        if (c.state.formatLabel != null) {
            FormatChip(c, white)
            Spacer(Modifier.height(14.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TitleLine(c, color = white,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 27.sp),
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                ArtistLine(c, color = white,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp), alpha = .7f)
            }
            Spacer(Modifier.width(10.dp))
            StyleIconButton(c, PlayerSymbol.HEART, PlayerAction.LIKE, size = 40.dp, iconSize = 28.dp,
                iconColor = if (c.state.liked) MaterialTheme.colorScheme.error else white.copy(alpha = .9f),
                selected = c.state.liked)
            Spacer(Modifier.width(6.dp))
            StyleIconButton(c, PlayerSymbol.MORE, PlayerAction.MORE, size = 40.dp, iconSize = 28.dp,
                iconColor = white.copy(alpha = .9f))
        }
        Spacer(Modifier.height(20.dp))
        ThinSeekBar(c, white, dragTrackHeight = 9.dp)
        SeekTimeRow(c, white, remaining = true, alpha = .7f, center = {
            if (c.state.formatLabel != null) FormatChip(c, white)
        })
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            SkipButton(c, next = false, size = 72.dp, iconSize = 44.dp,
                iconColor = white.copy(alpha = if (c.state.canPrevious) 1f else .35f))
            StylePlayButton(c, size = 88.dp, iconSize = 56.dp, iconColor = white, spinnerSize = 52.dp)
            SkipButton(c, next = true, size = 72.dp, iconSize = 44.dp,
                iconColor = white.copy(alpha = if (c.state.canNext) 1f else .35f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            StyleIconButton(c, PlayerSymbol.LYRICS, null, label = stringResource(Res.string.m5_lyrics),
                onClick = { c.interaction.panel = PlayerPanel.LYRICS },
                size = 44.dp, iconSize = 24.dp, iconColor = white.copy(alpha = .8f))
            Spacer(Modifier.width(24.dp))
            StyleIconButton(c, PlayerSymbol.QUEUE, null, label = stringResource(Res.string.m5_queue),
                onClick = { c.interaction.panel = PlayerPanel.QUEUE },
                size = 44.dp, iconSize = 24.dp, iconColor = white.copy(alpha = .8f))
        }
        VolumeRow(c, white, Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(24.dp))
    }
}

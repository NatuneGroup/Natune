package moe.rukamori.archivetune.shared.ui.playerstyles

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.marquee.MarqueeText
import moe.rukamori.archivetune.shared.ui.player.PlayerAction
import moe.rukamori.archivetune.shared.ui.player.PlayerPanel
import androidx.compose.ui.graphics.graphicsLayer
import moe.rukamori.archivetune.shared.ui.player.PlayerRenderContext
import moe.rukamori.archivetune.shared.ui.player.sleeveHidden
import moe.rukamori.archivetune.shared.ui.player.sleeveRadiusOr
import moe.rukamori.archivetune.shared.ui.player.PlayerSymbol
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m5_lyrics
import moe.rukamori.archivetune.shared.ui.resources.m5_now_playing
import moe.rukamori.archivetune.shared.ui.resources.m5_queue
import org.jetbrains.compose.resources.stringResource

/** V9 look: fully dynamic palette off the artwork dominant color, squiggly seek, centered layout. */
@Composable
internal fun V9Player(c: PlayerRenderContext) {
    val dark = isSystemInDarkTheme()
    val dominant = c.state.palette.firstOrNull() ?: MaterialTheme.colorScheme.primary
    val colors = v9Colors(dominant, dark)
    Box(Modifier.fillMaxSize().background(colors.background)) {
        if (c.landscape) {
            Row(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                BoxWithConstraints(Modifier.weight(.5f).fillMaxHeight()) {
                    val art = minOf(maxHeight * .74f, maxWidth)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        V9Artwork(c, Modifier.width(art).height(art))
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(start = 26.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center) {
                    V9Metadata(c, colors)
                    Spacer(Modifier.height(20.dp))
                    V9Seek(c, colors)
                    Spacer(Modifier.height(16.dp))
                    V9Transport(c, colors)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                V9Header(c, colors)
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center) {
                    V9Artwork(c, Modifier.fillMaxWidth())
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    V9Metadata(c, colors)
                    Spacer(Modifier.height(18.dp))
                    V9Seek(c, colors)
                    Spacer(Modifier.height(14.dp))
                    V9Transport(c, colors)
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun V9Header(c: PlayerRenderContext, colors: V9Colors) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        StyleIconButton(c, PlayerSymbol.DOWN, PlayerAction.COLLAPSE, size = 56.dp, iconSize = 30.dp,
            container = colors.text.copy(alpha = .16f), iconColor = colors.text, shape = CircleShape)
        MarqueeText(
            stringResource(Res.string.m5_now_playing),
            Modifier.weight(1f).padding(horizontal = 8.dp),
            color = colors.text, style = MaterialTheme.typography.titleLarge, centered = true,
        )
        StyleIconButton(c, PlayerSymbol.LYRICS, null, label = stringResource(Res.string.m5_lyrics),
            onClick = { c.interaction.panel = PlayerPanel.LYRICS },
            size = 56.dp, iconSize = 26.dp, container = colors.text.copy(alpha = .16f),
            iconColor = colors.text, shape = RoundedCornerShape(22.dp))
        Spacer(Modifier.width(8.dp))
        StyleIconButton(c, PlayerSymbol.QUEUE, null, label = stringResource(Res.string.m5_queue),
            onClick = { c.interaction.panel = PlayerPanel.QUEUE },
            size = 56.dp, iconSize = 26.dp, container = colors.text.copy(alpha = .16f),
            iconColor = colors.text, shape = RoundedCornerShape(22.dp))
    }
}

@Composable
private fun V9Artwork(c: PlayerRenderContext, modifier: Modifier) {
    val id = c.state.playback?.occurrenceId
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(c.preferences.sleeveRadiusOr(30f).dp))
        .graphicsLayer { alpha = if (c.preferences.sleeveHidden) 0f else 1f }.playerSeekDoubleTap(c)) {
        if (c.state.canvasAvailable) c.slots.canvas(id, c.surfaces.artwork, Modifier.fillMaxSize())
        else c.slots.artwork(id, Modifier.fillMaxSize())
    }
}

@Composable
private fun V9Metadata(c: PlayerRenderContext, colors: V9Colors) {
    TitleLine(c, Modifier.fillMaxWidth(), color = colors.text,
        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, centered = true)
    Spacer(Modifier.height(6.dp))
    ArtistLine(c, Modifier.fillMaxWidth(), color = colors.text,
        style = MaterialTheme.typography.titleLarge, alpha = .72f, centered = true)
}

@Composable
private fun V9Seek(c: PlayerRenderContext, colors: V9Colors) {
    SquigglySeekBar(c, active = colors.accent, inactive = colors.text.copy(alpha = .24f))
    SeekTimeRow(c, colors.text, remaining = true, alpha = .6f, center = { FormatChip(c, colors.text) })
}

@Composable
private fun V9Transport(c: PlayerRenderContext, colors: V9Colors) {
    val playing = c.state.playback?.playWhenReady == true && c.state.playback?.isEnded != true
    val playCorner by animateDpAsState(
        if (playing) 34.dp else 52.dp,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "v9PlayCorner")
    Row(Modifier.fillMaxWidth().height(110.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SkipButton(c, next = false, size = 64.dp, iconSize = 40.dp,
                iconColor = colors.text.copy(alpha = if (c.state.canPrevious) 1f else .4f))
        }
        StylePlayButton(c, size = 80.dp, iconSize = 42.dp, container = colors.accent,
            iconColor = colors.iconOnAccent, shape = RoundedCornerShape(playCorner), spinnerSize = 46.dp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SkipButton(c, next = true, size = 64.dp, iconSize = 40.dp,
                iconColor = colors.text.copy(alpha = if (c.state.canNext) 1f else .4f))
        }
    }
}

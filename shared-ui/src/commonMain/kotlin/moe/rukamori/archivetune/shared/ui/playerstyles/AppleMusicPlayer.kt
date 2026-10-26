package moe.rukamori.archivetune.shared.ui.playerstyles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.player.PlayerAction
import moe.rukamori.archivetune.shared.ui.player.PlayerRenderContext
import moe.rukamori.archivetune.shared.ui.player.PlayerSymbol
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m5_close
import moe.rukamori.archivetune.shared.ui.resources.m5_lyrics
import moe.rukamori.archivetune.shared.ui.resources.m5_more
import moe.rukamori.archivetune.shared.ui.resources.m5_output
import moe.rukamori.archivetune.shared.ui.resources.m5_queue
import org.jetbrains.compose.resources.stringResource

private val amText = Color.White

private enum class AmSurface { COVER, QUEUE, LYRICS }

/** Fade the backdrop's bottom edge into the black scrim (the original seam fade). */
private fun Modifier.seamFade(): Modifier = drawWithContent {
    drawContent()
    drawRect(
        Brush.verticalGradient(
            listOf(Color.Transparent, Color.Black),
            startY = size.height * .62f,
        ),
        blendMode = BlendMode.DstIn,
    )
}

/** Apple Music look: drifting blurred backdrop, cover/queue/lyrics morph area, AM controls column. */
@Composable
internal fun AppleMusicPlayer(c: PlayerRenderContext) {
    val id = c.state.playback?.occurrenceId
    var surface by remember { mutableStateOf(AmSurface.COVER) }
    val white = amText

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (c.optical.artworkBlur) {
            c.slots.artwork(
                id,
                Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }
                    .blur(64.dp)
                    .seamFade(),
            )
            if (c.state.canvasAvailable) {
                c.slots.canvas(
                    id, true,
                    Modifier.fillMaxSize()
                        .graphicsLayer { alpha = .55f; scaleX = 1.15f; scaleY = 1.15f }
                        .blur(32.dp)
                        .seamFade(),
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)))

        if (c.landscape) {
            AmLandscape(c, white, surface, onSurface = { surface = it })
        } else {
            AmPortrait(c, white, surface, onSurface = { surface = it })
        }
    }
}

/** Mini track header shown above the queue/lyrics panes: art thumb, title, star, more, close. */
@Composable
private fun AmPaneHeader(c: PlayerRenderContext, white: Color, onClose: () -> Unit) {
    val id = c.state.playback?.occurrenceId
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(white.copy(alpha = .14f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))) {
            c.slots.artwork(id, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            MarqueeText(c.state.title, color = white, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            MarqueeText(c.state.artist, color = white.copy(alpha = .64f),
                style = MaterialTheme.typography.bodyMedium)
        }
        StyleIconButton(c, PlayerSymbol.STAR, PlayerAction.LIKE, size = 44.dp, iconSize = 24.dp,
            iconColor = white.copy(alpha = if (c.state.liked) 1f else .8f), selected = c.state.liked)
        StyleIconButton(c, PlayerSymbol.MORE, PlayerAction.MORE, size = 44.dp, iconSize = 24.dp,
            iconColor = white.copy(alpha = .8f))
        StyleIconButton(c, PlayerSymbol.CLOSE_X, null, label = stringResource(Res.string.m5_close),
            onClick = onClose, size = 44.dp, iconSize = 24.dp, iconColor = white.copy(alpha = .8f))
    }
}

/** Sharp square artwork for the cover surface, with double-tap seek and the optional canvas. */
@Composable
private fun AmCoverArt(c: PlayerRenderContext, modifier: Modifier) {
    val id = c.state.playback?.occurrenceId
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).playerSeekDoubleTap(c)) {
        c.slots.artwork(id, Modifier.fillMaxSize())
    }
}

@Composable
private fun AmPortrait(
    c: PlayerRenderContext,
    white: Color,
    surface: AmSurface,
    onSurface: (AmSurface) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // Morph area: cover, queue, or lyrics, animating between surfaces.
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp)) {
            when (surface) {
                AmSurface.COVER -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AmCoverArt(c, Modifier.fillMaxWidth())
                }
                AmSurface.QUEUE, AmSurface.LYRICS -> {
                    var dragDismiss by remember { mutableStateOf(0f) }
                    Column(
                        Modifier.fillMaxSize()
                            .pointerInput(c, surface) {
                                detectVerticalDragGestures { change, drag ->
                                    change.consume()
                                    dragDismiss += drag
                                    if (dragDismiss > 96f && surface == AmSurface.LYRICS) {
                                        onSurface(AmSurface.COVER)
                                        dragDismiss = 0f
                                    }
                                }
                            },
                    ) {
                        Spacer(Modifier.height(4.dp))
                        AmPaneHeader(c, white) { onSurface(AmSurface.COVER) }
                        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                            if (surface == AmSurface.QUEUE) {
                                c.slots.queue(Modifier.fillMaxSize())
                            } else {
                                c.slots.compactLyrics(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
        AmControls(c, white, surface, onSurface)
    }
}

@Composable
private fun AmLandscape(
    c: PlayerRenderContext,
    white: Color,
    surface: AmSurface,
    onSurface: (AmSurface) -> Unit,
) {
    Row(Modifier.fillMaxSize().padding(28.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center) {
            Box(Modifier.fillMaxWidth(.8f).align(Alignment.CenterHorizontally)) {
                AmCoverArt(c, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().align(Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MarqueeText(c.state.title, color = white,
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    MarqueeText(c.state.artist, color = white.copy(alpha = .64f),
                        style = MaterialTheme.typography.titleMedium)
                }
                StyleIconButton(c, PlayerSymbol.HEART, PlayerAction.LIKE, size = 44.dp, iconSize = 24.dp,
                    iconColor = if (c.state.liked) MaterialTheme.colorScheme.error else white.copy(alpha = .8f),
                    selected = c.state.liked)
                StyleIconButton(c, PlayerSymbol.MORE, PlayerAction.MORE, size = 44.dp, iconSize = 24.dp,
                    iconColor = white.copy(alpha = .8f))
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 28.dp)) {
            if (surface == AmSurface.LYRICS) {
                Column(Modifier.fillMaxSize()) {
                    AmPaneHeader(c, white) { onSurface(AmSurface.COVER) }
                    Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                        c.slots.compactLyrics(Modifier.fillMaxSize())
                    }
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center) {
                    AmControls(c, white, surface, onSurface, showTitle = false)
                }
            }
        }
    }
}

@Composable
private fun AmControls(
    c: PlayerRenderContext,
    white: Color,
    surface: AmSurface,
    onSurface: (AmSurface) -> Unit,
    showTitle: Boolean = true,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp)
            .pointerInput(c) {
                var accumulated = 0f
                detectVerticalDragGestures(
                    onDragStart = { accumulated = 0f },
                    onVerticalDrag = { change, drag ->
                        accumulated += drag
                        if (accumulated < -60f) {
                            accumulated = 0f
                            change.consume()
                            onSurface(AmSurface.QUEUE)
                        }
                    },
                )
            },
    ) {
        if (showTitle) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    TitleLine(c, color = white,
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    ArtistLine(c, color = white,
                        style = MaterialTheme.typography.titleMedium, alpha = .64f)
                }
                StyleIconButton(c, PlayerSymbol.STAR, PlayerAction.LIKE, size = 44.dp, iconSize = 24.dp,
                    iconColor = white.copy(alpha = .8f), selected = c.state.liked)
                StyleIconButton(c, PlayerSymbol.MORE, PlayerAction.MORE, size = 44.dp, iconSize = 24.dp,
                    iconColor = white.copy(alpha = .8f))
            }
            Spacer(Modifier.height(14.dp))
        }
        ThinSeekBar(c, white, trackHeight = 5.dp, dragTrackHeight = 8.dp)
        SeekTimeRow(c, white, remaining = true, alpha = .6f, center = { FormatChip(c, white) })
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            SkipButton(c, next = false, size = 48.dp, iconSize = 40.dp,
                iconColor = white.copy(alpha = if (c.state.canPrevious) 1f else .35f))
            StylePlayButton(c, size = 80.dp, iconSize = 56.dp, iconColor = white, spinnerSize = 44.dp)
            SkipButton(c, next = true, size = 48.dp, iconSize = 40.dp,
                iconColor = white.copy(alpha = if (c.state.canNext) 1f else .35f))
        }
        VolumeRow(c, white, Modifier.padding(vertical = 6.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            StyleIconButton(c, PlayerSymbol.LYRICS, null, label = stringResource(Res.string.m5_lyrics),
                onClick = { onSurface(if (surface == AmSurface.LYRICS) AmSurface.COVER else AmSurface.LYRICS) },
                size = 48.dp, iconSize = 24.dp,
                iconColor = if (surface == AmSurface.LYRICS) white else white.copy(alpha = .55f))
            StyleIconButton(c, PlayerSymbol.OUTPUT, PlayerAction.OUTPUT, size = 48.dp, iconSize = 24.dp,
                iconColor = white.copy(alpha = .55f))
            StyleIconButton(c, PlayerSymbol.QUEUE, null, label = stringResource(Res.string.m5_queue),
                onClick = { onSurface(if (surface == AmSurface.QUEUE) AmSurface.COVER else AmSurface.QUEUE) },
                size = 48.dp, iconSize = 24.dp,
                iconColor = if (surface == AmSurface.QUEUE) white else white.copy(alpha = .55f))
        }
        Spacer(Modifier.height(10.dp))
    }
}

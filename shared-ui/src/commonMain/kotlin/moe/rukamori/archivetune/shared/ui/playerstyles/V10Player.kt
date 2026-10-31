package moe.rukamori.archivetune.shared.ui.playerstyles

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.abs
import moe.rukamori.archivetune.shared.ui.marquee.MarqueeText
import moe.rukamori.archivetune.shared.ui.player.PlayerAction
import moe.rukamori.archivetune.shared.ui.player.PlayerPanel
import moe.rukamori.archivetune.shared.ui.player.PlayerRenderContext
import moe.rukamori.archivetune.shared.ui.player.sleeveHidden
import moe.rukamori.archivetune.shared.ui.player.PlayerRepeat
import moe.rukamori.archivetune.shared.ui.player.PlayerSymbol
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m5_add_to_playlist
import moe.rukamori.archivetune.shared.ui.resources.m5_lyrics
import moe.rukamori.archivetune.shared.ui.resources.m5_more
import moe.rukamori.archivetune.shared.ui.resources.m5_pause
import moe.rukamori.archivetune.shared.ui.resources.m5_play
import moe.rukamori.archivetune.shared.ui.resources.m5_share
import org.jetbrains.compose.resources.stringResource

private fun RoundedPolygon.toComposePath(): Path {
    val p = Path()
    cubics.forEachIndexed { index, cubic ->
        if (index == 0) p.moveTo(cubic.anchor0X, cubic.anchor0Y)
        p.cubicTo(
            cubic.control0X, cubic.control0Y,
            cubic.control1X, cubic.control1Y,
            cubic.anchor1X, cubic.anchor1Y,
        )
    }
    p.close()
    return p
}

private fun cubicsToPath(cubics: List<androidx.graphics.shapes.Cubic>): Path {
    val p = Path()
    cubics.forEachIndexed { index, cubic ->
        if (index == 0) p.moveTo(cubic.anchor0X, cubic.anchor0Y)
        p.cubicTo(
            cubic.control0X, cubic.control0Y,
            cubic.control1X, cubic.control1Y,
            cubic.anchor1X, cubic.anchor1Y,
        )
    }
    p.close()
    return p
}

/** Clip path whose outline is the polygon scaled to the full bounds, like the original die-cut art. */
private class RoundedPolygonShape(private val path: Path) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val scaled = Path().also { it.addPath(path) }
        val bounds = scaled.getBounds()
        if (bounds.width <= 0f || bounds.height <= 0f) return Outline.Rectangle(Rect(Offset.Zero, size))
        val s = minOf(size.width / bounds.width, size.height / bounds.height)
        scaled.transform(
            Matrix().apply {
                translate(
                    (size.width - bounds.width * s) / 2f - bounds.left * s,
                    (size.height - bounds.height * s) / 2f - bounds.top * s,
                )
                scale(s, s, 1f)
            },
        )
        return Outline.Generic(scaled)
    }
}

private val v10Shapes: List<RoundedPolygon>
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    get() = listOf(
        MaterialShapes.Flower,
        MaterialShapes.Clover4Leaf,
        MaterialShapes.Puffy,
        MaterialShapes.Cookie12Sided,
        MaterialShapes.SoftBurst,
    )

/**
 * V10 editorial look: dynamic field color, die-cut morphing artwork, serif title,
 * text PLAY/PAUSE pill and the icon chip row.
 */
@Composable
internal fun V10Player(c: PlayerRenderContext) {
    val dark = isSystemInDarkTheme()
    val dominant = c.state.palette.firstOrNull() ?: MaterialTheme.colorScheme.primary
    val field by animateColorAsState(v10FieldColor(dominant, dark), tween(800), label = "v10Field")
    val accent by animateColorAsState(v10AccentColor(dominant, dark), tween(800), label = "v10Accent")
    val playback = c.state.playback
    val playing = playback?.playWhenReady == true && playback.isEnded != true

    Box(Modifier.fillMaxSize().background(field)) {
        if (c.landscape) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight().padding(20.dp)) {
                    V10Header(c, field, accent)
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        V10Artwork(c, playing, Modifier.fillMaxWidth(.72f))
                    }
                    V10Metadata(c, field, accent)
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(20.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    V10Controls(c, field, accent)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                V10Header(c, field, accent)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        V10Artwork(c, playing, Modifier.fillMaxWidth(.68f))
                    }
                    V10Metadata(c, field, accent)
                    V10Controls(c, field, accent)
                }
            }
        }
    }
}

@Composable
private fun V10Header(c: PlayerRenderContext, field: Color, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        StyleIconButton(c, PlayerSymbol.DOWN, PlayerAction.COLLAPSE, size = 44.dp, iconSize = 26.dp,
            iconColor = accent)
        Spacer(Modifier.weight(1f))
        SleepHeaderButton(c, accent)
        Spacer(Modifier.width(10.dp))
        StyleIconButton(c, PlayerSymbol.SHARE, PlayerAction.SHARE, size = 44.dp, iconSize = 24.dp,
            iconColor = accent)
        Spacer(Modifier.width(10.dp))
        StyleIconButton(c, PlayerSymbol.LYRICS, null, label = stringResource(Res.string.m5_lyrics),
            onClick = { c.interaction.panel = PlayerPanel.LYRICS }, size = 44.dp, iconSize = 24.dp,
            iconColor = accent)
        Spacer(Modifier.width(10.dp))
        StyleIconButton(c, PlayerSymbol.MORE, PlayerAction.MORE, size = 44.dp, iconSize = 24.dp,
            iconColor = accent)
    }
}

/** Die-cut artwork: polygon picked from the title hash, morphs between tracks, shrinks when paused. */
@Composable
private fun V10Artwork(c: PlayerRenderContext, playing: Boolean, modifier: Modifier) {
    val shapes = v10Shapes
    val index = abs(c.state.title.hashCode()) % shapes.size
    val target = shapes[index]
    val progress = remember { Animatable(1f) }
    var morphStart by remember { mutableStateOf<RoundedPolygon?>(null) }
    LaunchedEffect(target) {
        val start = morphStart ?: target
        if (start !== target) {
            morphStart = start
            progress.snapTo(0f)
            progress.animateTo(1f, tween(700))
            morphStart = target
        }
    }
    val scale by animateFloatAsState(if (playing) 1f else .94f, tween(400), label = "v10PauseScale")
    val id = c.state.playback?.occurrenceId
    val start = morphStart ?: target
    val path = remember(start, target, progress.value) {
        if (progress.value >= .999f || start === target) target.toComposePath()
        else cubicsToPath(Morph(start, target).asCubics(progress.value))
    }
    Box(
        modifier
            .aspectRatio(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (c.preferences.sleeveHidden) 0f else 1f }
            .clip(RoundedPolygonShape(path))
            .playerSeekDoubleTap(c),
    ) {
        c.slots.artwork(id, Modifier.fillMaxSize())
    }
}

@Composable
private fun V10Metadata(c: PlayerRenderContext, field: Color, accent: Color) {
    val titleStyle = when {
        c.state.title.length > 40 -> MaterialTheme.typography.displaySmall
        c.state.title.length > 20 -> MaterialTheme.typography.displayMedium
        else -> MaterialTheme.typography.displayLarge
    }
    MarqueeText(
        c.state.title,
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        color = accent, style = titleStyle, fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif, centered = true,
    )
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        Arrangement.Center, Alignment.CenterVertically,
    ) {
        if (c.state.explicit) {
            ExplicitBadge(accent)
            Spacer(Modifier.width(8.dp))
        }
        ArtistLine(
            c, Modifier.weight(1f, fill = false), color = accent, centered = true,
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.sp),
            alpha = .8f, uppercase = true, onClick = true,
        )
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun V10Controls(c: PlayerRenderContext, field: Color, accent: Color) {
    val playback = c.state.playback
    val playing = playback?.playWhenReady == true && playback.isEnded != true
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
            .pointerInput(c) {
                var accumulated = 0f
                detectVerticalDragGestures(
                    onDragStart = { accumulated = 0f },
                    onVerticalDrag = { change, drag ->
                        accumulated += drag
                        if (accumulated < -40f) {
                            accumulated = 0f
                            change.consume()
                            c.interaction.panel = PlayerPanel.QUEUE
                        }
                    },
                )
            },
    ) {
        Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clip(CircleShape)
                    .background(accent)
                    .clickable(enabled = playback != null) { c.actions.playPause() },
                contentAlignment = Alignment.Center,
            ) {
                if (playback?.isBuffering == true) {
                    androidx.compose.material3.CircularProgressIndicator(
                        Modifier.size(34.dp), color = field,
                    )
                } else {
                    Text(
                        stringResource(
                            when {
                                playback?.isEnded == true -> Res.string.m5_play
                                playing -> Res.string.m5_pause
                                else -> Res.string.m5_play
                            },
                        ),
                        color = field,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Medium, letterSpacing = 3.sp,
                        ),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            SkipButton(c, next = true, size = 80.dp, iconSize = 34.dp,
                iconColor = accent.copy(alpha = if (c.state.canNext) 1f else .4f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SkipButton(c, next = false, size = 80.dp, iconSize = 34.dp,
                iconColor = accent.copy(alpha = if (c.state.canPrevious) 1f else .4f))
            Column(Modifier.weight(1f)) {
                SquigglySeekBar(c, active = accent, inactive = accent.copy(alpha = .3f), stroke = 6.dp)
                SeekTimeRow(c, accent, remaining = true, alpha = .6f)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceAround, Alignment.CenterVertically) {
            StyleIconButton(c, PlayerSymbol.HEART, PlayerAction.LIKE, size = 44.dp, iconSize = 24.dp,
                iconColor = if (c.state.liked) accent else accent.copy(alpha = .5f),
                selected = c.state.liked)
            StyleIconButton(c, PlayerSymbol.SHUFFLE, PlayerAction.SHUFFLE, size = 44.dp, iconSize = 24.dp,
                iconColor = accent.copy(alpha = if (c.state.shuffle) 1f else .5f),
                selected = c.state.shuffle)
            StyleIconButton(c, PlayerSymbol.REPEAT, PlayerAction.REPEAT, size = 44.dp, iconSize = 24.dp,
                iconColor = accent.copy(alpha = if (c.state.repeat != PlayerRepeat.OFF) 1f else .5f),
                selected = c.state.repeat != PlayerRepeat.OFF)
            StyleIconButton(c, PlayerSymbol.ADD, PlayerAction.MORE,
                label = stringResource(Res.string.m5_add_to_playlist), size = 44.dp, iconSize = 24.dp,
                iconColor = accent.copy(alpha = .8f))
        }
    }
}

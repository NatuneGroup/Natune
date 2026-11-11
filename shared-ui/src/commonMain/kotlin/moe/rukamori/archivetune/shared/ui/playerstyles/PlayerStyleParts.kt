package moe.rukamori.archivetune.shared.ui.playerstyles

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import moe.rukamori.archivetune.shared.ui.marquee.MarqueeText
import moe.rukamori.archivetune.shared.ui.player.PlayerAction
import moe.rukamori.archivetune.shared.ui.player.PlayerBufferingFade
import moe.rukamori.archivetune.shared.ui.player.PlayerGlyph
import moe.rukamori.archivetune.shared.ui.player.PlayerIcon
import moe.rukamori.archivetune.shared.ui.player.PlayerRenderContext
import moe.rukamori.archivetune.shared.ui.player.PlayerSymbol
import moe.rukamori.archivetune.shared.ui.player.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.snap
import moe.rukamori.archivetune.shared.ui.player.label
import moe.rukamori.archivetune.shared.ui.player.playerTime
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m5_no_track
import moe.rukamori.archivetune.shared.ui.resources.m5_now_playing
import moe.rukamori.archivetune.shared.ui.resources.m5_pause
import moe.rukamori.archivetune.shared.ui.resources.m5_play
import moe.rukamori.archivetune.shared.ui.resources.m5_playing_from
import moe.rukamori.archivetune.shared.ui.resources.m5_replay
import moe.rukamori.archivetune.shared.ui.resources.m5_seek
import org.jetbrains.compose.resources.stringResource

/** Rounded-square "E" glyph matching the original explicit-content badge. */
@Composable
internal fun ExplicitBadge(color: Color, modifier: Modifier = Modifier, size: Dp = 13.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawRoundRect(
            color.copy(alpha = .22f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .22f),
        )
        val b = w * .30f
        drawRect(color, Offset(b, h * .30f), androidx.compose.ui.geometry.Size(w * .10f, h * .42f))
        drawRect(color, Offset(b, h * .30f), androidx.compose.ui.geometry.Size(w * .40f, h * .09f))
        drawRect(color, Offset(b, h * .465f), androidx.compose.ui.geometry.Size(w * .32f, h * .09f))
        drawRect(color, Offset(b, h * .63f), androidx.compose.ui.geometry.Size(w * .40f, h * .09f))
    }
}

/** Track title with the inline explicit badge and the TITLE action on tap, where the host answers TITLE. */
@Composable
internal fun TitleLine(
    c: PlayerRenderContext,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.titleLarge,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    centered: Boolean = false,
    onClick: Boolean = true,
) {
    val titleModifier = if (onClick && PlayerAction.TITLE in c.state.actionSupport && c.state.title.isNotBlank()) {
        modifier.clickable { c.actions.onAction(PlayerAction.TITLE) }
    } else {
        modifier
    }
    val title = c.state.title.ifBlank { stringResource(Res.string.m5_no_track) }
    PlayerTextSwap(title to c.state.explicit, c.fadeActive, titleModifier,
        if (centered) Alignment.TopCenter else Alignment.TopStart) { (text, explicit) ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
        ) {
            MarqueeText(
                text,
                Modifier.weight(1f, fill = false), color = color, style = style, fontWeight = fontWeight,
                fontFamily = fontFamily, centered = centered,
            )
            if (explicit) {
                Spacer(Modifier.width(6.dp))
                ExplicitBadge(if (color != Color.Unspecified) color else LocalTextStyle.current.color)
            }
        }
    }
}

@Composable
internal fun ArtistLine(
    c: PlayerRenderContext,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    centered: Boolean = false,
    alpha: Float = 1f,
    uppercase: Boolean = false,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    onClick: Boolean = true,
) {
    val artist = if (uppercase) c.state.artist.uppercase() else c.state.artist
    val artistModifier = if (onClick && PlayerAction.ARTIST in c.state.actionSupport && artist.isNotBlank()) {
        modifier.clickable { c.actions.onAction(PlayerAction.ARTIST) }
    } else {
        modifier
    }
    PlayerTextSwap(artist, c.fadeActive, artistModifier, if (centered) Alignment.TopCenter else Alignment.TopStart) {
        MarqueeText(
            it,
            color = color.copy(alpha = color.alpha * alpha),
            style = style, fontWeight = fontWeight, fontFamily = fontFamily, centered = centered,
            letterSpacing = letterSpacing,
        )
    }
}

/** "Playing from X" when the queue or album title is known, otherwise "Now playing". */
@Composable
internal fun PlayingFromHeader(
    c: PlayerRenderContext,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelMedium,
    alpha: Float = .8f,
) {
    val from = c.state.queueTitle ?: c.state.albumTitle
    MarqueeText(
        if (from != null) stringResource(Res.string.m5_playing_from, from) else stringResource(Res.string.m5_now_playing),
        modifier,
        color = color.copy(alpha = color.alpha * alpha),
        style = style,
        centered = true,
    )
}

/**
 * Drag/tap scrubber shared by the thin, flat and squiggly seek bars. The Canvas gets the live
 * fraction (preview draft included); gestures go through the shared interaction draft so seek
 * cancel/commit behaves identically across styles.
 */
@Composable
internal fun SeekScrubber(
    c: PlayerRenderContext,
    modifier: Modifier = Modifier,
    height: Dp = 24.dp,
    content: DrawScope.(fraction: Float, emphasis: Float) -> Unit,
) {
    val playback = c.state.playback
    val duration = playback?.durationMs?.takeIf { it > 0L }
    val progress = rememberPlayerProgress(c)
    val seekable = duration != null && c.state.canSeek && playback?.isSeekable == true
    val label = stringResource(Res.string.m5_seek)
    val thumb = androidx.compose.material3.LocalContentColor.current
    var dragging by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(c)
    val emphasis = animateFloatAsState(if (dragging && seekable) 1f else 0f,
        if (c.motionActive) PlayerMotionSpecs.response() else snap(), label = "playerScrubEmphasis")
    LaunchedEffect(seekable, playback?.occurrenceId, playback?.discontinuityGeneration) {
        dragging = false
        c.interaction.reconcile(playback)
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                contentDescription = label
                if (duration != null) progressBarRangeInfo = ProgressBarRangeInfo(progress.fraction(duration), 0f..1f)
            }
            .pointerInput(seekable, duration) {
                if (!seekable || duration == null) return@pointerInput
                var frac = 0f
                detectDragGestures(
                    onDragStart = { o ->
                        frac = (o.x / size.width).coerceIn(0f, 1f)
                        dragging = true
                        latest.interaction.preview(frac, latest.state)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        frac = (change.position.x / size.width).coerceIn(0f, 1f)
                        latest.interaction.preview(frac, latest.state)
                    },
                    onDragEnd = {
                        latest.interaction.commit(latest.state, latest.actions.seekTo)
                        dragging = false
                    },
                    onDragCancel = {
                        latest.interaction.cancelPreview()
                        dragging = false
                    },
                )
            }
            .pointerInput(seekable, duration) {
                if (!seekable || duration == null) return@pointerInput
                detectTapGestures { off ->
                    latest.actions.seekTo((duration * (off.x / size.width)).toLong().coerceIn(0L, duration))
                }
            },
    ) {
        val fraction = progress.fraction(duration)
        content(fraction, emphasis.value)
        if (emphasis.value > 0f) drawCircle(thumb, 8.dp.toPx() * emphasis.value, Offset(size.width * fraction, size.height / 2))
    }
}

/** V7/AM thin progress slider: flat translucent track, solid fill, thicker while dragging. */
@Composable
internal fun ThinSeekBar(
    c: PlayerRenderContext,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 24.dp,
    trackHeight: Dp = 6.dp,
    dragTrackHeight: Dp = trackHeight,
    trackAlpha: Float = .28f,
    fillAlpha: Float = .85f,
) {
    SeekScrubber(c, modifier, height) { fraction, dragging ->
        val h = size.height
        val cy = h / 2
        val bar = (trackHeight + (dragTrackHeight - trackHeight) * dragging).toPx()
        drawLine(color.copy(alpha = trackAlpha), Offset(0f, cy), Offset(size.width, cy), bar, StrokeCap.Round)
        drawLine(color.copy(alpha = fillAlpha), Offset(0f, cy), Offset(size.width * fraction, cy), bar, StrokeCap.Round)
    }
}

/** V8 flat slider: slightly thicker translucent track, near-opaque progress, no thumb. */
@Composable
internal fun FlatSeekBar(c: PlayerRenderContext, color: Color, modifier: Modifier = Modifier) {
    SeekScrubber(c, modifier, 30.dp) { fraction, _ ->
        val cy = size.height / 2
        val bar = 9.dp.toPx()
        drawLine(color.copy(alpha = .32f), Offset(0f, cy), Offset(size.width, cy), bar, StrokeCap.Round)
        drawLine(color.copy(alpha = .88f), Offset(0f, cy), Offset(size.width * fraction, cy), bar, StrokeCap.Round)
    }
}

/** Squiggly progress line (V9 seek and V10 wavy progress): sine wave over the played part. */
@Composable
internal fun SquigglySeekBar(
    c: PlayerRenderContext,
    active: Color,
    inactive: Color,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    stroke: Dp = 7.dp,
    amplitude: Dp = 3.dp,
    wavelength: Dp = 26.dp,
) {
    val playing = c.state.playback?.playWhenReady == true && c.state.playback?.isEnded != true
    val amp = animateFloatAsState(
        if (playing && c.motionActive) amplitude.value else 0f,
        animationSpec = if (c.motionActive) PlayerMotionSpecs.settle() else snap(), label = "squigglyAmp",
    )
    SeekScrubber(c, modifier, height) { fraction, _ ->
        val h = size.height
        val cy = h / 2
        val w = size.width
        val activeX = w * fraction
        val wavePx = wavelength.toPx()
        val path = Path()
        val step = wavePx / 8f
        var x = 0f
        val ampPx = amp.value.dp.toPx()
        path.moveTo(0f, cy)
        while (x < activeX) {
            x += step
            val px = minOf(x, activeX)
            path.lineTo(px, cy + sin(px / wavePx * PI.toFloat() * 2f) * ampPx)
        }
        drawPath(path, active, style = Stroke(stroke.toPx(), cap = StrokeCap.Round))
        drawLine(inactive, Offset(activeX, cy), Offset(w, cy), stroke.toPx(), StrokeCap.Round)
    }
}

/** Elapsed | optional center content | -remaining, matching the original time label row. */
@Composable
internal fun SeekTimeRow(
    c: PlayerRenderContext,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelMedium,
    alpha: Float = .55f,
    remaining: Boolean = true,
    center: (@Composable () -> Unit)? = null,
) {
    val progress = rememberPlayerProgress(c)
    val seconds by remember(progress) { derivedStateOf { progress.positionMs / 1000L } }
    val position = seconds * 1000L
    val duration = c.state.playback?.durationMs
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(playerTime(position), color = color.copy(alpha = alpha), style = style.tabularFigures())
        if (center != null) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { center() }
        else Spacer(Modifier.weight(1f))
        Text(
            if (duration == null) "—" else if (remaining) "-${playerTime((duration - position).coerceAtLeast(0))}"
            else playerTime(duration),
            color = color.copy(alpha = alpha), style = style,
        )
    }
}

/** Stream-quality chip (V8/V9/AM): bordered pill with a tiny indicator and the format label. */
@Composable
internal fun FormatChip(c: PlayerRenderContext, color: Color, modifier: Modifier = Modifier) {
    val label = c.state.formatLabel ?: return
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = .1f))
            .border(1.dp, color.copy(alpha = .13f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (c.state.formatLossless) {
            Canvas(Modifier.size(10.dp)) {
                val w = size.width
                drawCircle(color, w * .16f, center)
                drawCircle(color, w * .36f, center, style = Stroke(w * .12f))
            }
        }
        Text(label, color = color.copy(alpha = .72f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Volume row (V7/V8/AM): off glyph, thin flat slider, up glyph. Tagged like the shared volume surface. */
@Composable
internal fun VolumeRow(
    c: PlayerRenderContext,
    color: Color,
    modifier: Modifier = Modifier,
    iconColor: Color = color,
) {
    val volume = c.state.volume?.takeIf { it.isFinite() } ?: return
    if (!c.preferences.showVolume) return
    Row(
        modifier.fillMaxWidth().testTag("player-volume"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VolumeGlyph(up = false, color = iconColor.copy(alpha = .8f), size = 20.dp)
        val fraction = volume.coerceIn(0f, 1f)
        Canvas(
            Modifier
                .weight(1f)
                .height(20.dp)
                .pointerInput(c) {
                    detectTapGestures { off -> c.actions.setVolume((off.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(c) {
                    var frac = 0f
                    detectDragGestures(
                        onDragStart = { o -> frac = (o.x / size.width).coerceIn(0f, 1f); c.actions.setVolume(frac) },
                        onDrag = { change, _ ->
                            change.consume()
                            frac = (change.position.x / size.width).coerceIn(0f, 1f)
                            c.actions.setVolume(frac)
                        },
                    )
                },
        ) {
            val cy = size.height / 2
            val bar = 6.dp.toPx()
            drawLine(color.copy(alpha = .28f), Offset(0f, cy), Offset(size.width, cy), bar, StrokeCap.Round)
            drawLine(color.copy(alpha = .9f), Offset(0f, cy), Offset(size.width * fraction, cy), bar, StrokeCap.Round)
        }
        VolumeGlyph(up = true, color = iconColor.copy(alpha = .8f), size = 23.dp)
    }
}

@Composable
private fun VolumeGlyph(up: Boolean, color: Color, size: Dp) {
    PlayerGlyph(if (up) PlayerSymbol.VOLUME_UP else PlayerSymbol.VOLUME_LOW,
        color, true, null, Modifier.size(size))
}

/** Sleep countdown chip (V10 header): remaining time while armed, bedtime glyph otherwise. */
@Composable
internal fun SleepHeaderButton(c: PlayerRenderContext, color: Color, modifier: Modifier = Modifier) {
    val remainingMs = c.state.sleepTimerMs
    if (remainingMs != null) {
        Row(
            modifier
                .clip(CircleShape)
                .clickable { c.actions.onAction(PlayerAction.SLEEP) }
                .background(color.copy(alpha = .16f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SleepGlyph(color)
            Text(playerTime(remainingMs), color = color, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        StyleIconButton(c, PlayerSymbol.BEDTIME, PlayerAction.SLEEP, modifier, iconColor = color)
    }
}

@Composable
private fun SleepGlyph(color: Color) {
    PlayerGlyph(PlayerSymbol.BEDTIME, color, true, null, Modifier.size(14.dp))
}

/** Generic circular/square icon button with the shared glyph vocabulary. An unsupported action draws nothing. */
@Composable
internal fun StyleIconButton(
    c: PlayerRenderContext,
    symbol: PlayerSymbol,
    action: PlayerAction?,
    modifier: Modifier = Modifier,
    label: String? = null,
    onClick: (() -> Unit)? = null,
    size: Dp = 44.dp,
    iconSize: Dp = 24.dp,
    width: Dp? = null,
    container: Color? = null,
    iconColor: Color = Color.Unspecified,
    shape: Shape = CircleShape,
    enabled: Boolean = true,
    selected: Boolean? = null,
) {
    // A host that does not answer the action gets no control, never a button that only reports itself unconnected.
    if (action != null && action !in c.state.actionSupport) return
    val outputButton = c.slots.outputButton
    if (action == PlayerAction.OUTPUT && outputButton != null) {
        outputButton(
            modifier.size(width ?: size, size),
            if (iconColor == Color.Unspecified) LocalContentColor.current else iconColor,
            enabled,
        )
        return
    }
    PlayerIcon(
        symbol = symbol,
        label = label ?: action?.let { stringResource(actionLabel(it)) } ?: "",
        onClick = onClick ?: {
            if (action != null) c.actions.onAction(action)
        },
        modifier = modifier,
        enabled = enabled,
        selected = selected,
        size = size,
        iconSize = iconSize,
        width = width,
        container = container,
        contentColor = iconColor,
        shape = shape,
    )
}

internal fun actionLabel(action: PlayerAction) = action.label()

/** Large play/pause button with the loading ring the shared PlayButton draws. */
@Composable
internal fun StylePlayButton(
    c: PlayerRenderContext,
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    iconSize: Dp = 40.dp,
    width: Dp? = null,
    container: Color? = null,
    iconColor: Color = Color.Unspecified,
    shape: Shape = CircleShape,
    spinnerSize: Dp = 44.dp,
) {
    val playback = c.state.playback
    val loading = playback?.isBuffering == true
    val playing = playback?.playWhenReady == true && playback.isEnded != true
    val label = stringResource(when {
        playback?.isEnded == true -> Res.string.m5_replay
        playing -> Res.string.m5_pause
        else -> Res.string.m5_play
    })
    Box(modifier, contentAlignment = Alignment.Center) {
        PlayerIcon(
            if (playback?.isEnded == true) PlayerSymbol.REPLAY else if (playing) PlayerSymbol.PAUSE else PlayerSymbol.PLAY,
            label, c.actions.playPause,
            enabled = playback != null,
            size = size, iconSize = iconSize, width = width, container = container, contentColor = iconColor, shape = shape,
        )
        PlayerBufferingFade(loading, c.fadeActive) {
            val spinner = Modifier.size(spinnerSize).testTag("player-loading")
            if (c.motionActive) CircularProgressIndicator(spinner, strokeWidth = 3.dp)
            else CircularProgressIndicator(progress = { .7f }, modifier = spinner, strokeWidth = 3.dp)
        }
    }
}

/** Previous/next pair used by every transport row; respects canPrevious/canNext like the original alpha gate. */
@Composable
internal fun SkipButton(
    c: PlayerRenderContext,
    next: Boolean,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    iconColor: Color = Color.Unspecified,
) {
    val action = if (next) PlayerAction.NEXT else PlayerAction.PREVIOUS
    PlayerIcon(
        if (next) PlayerSymbol.NEXT else PlayerSymbol.PREVIOUS,
        stringResource(actionLabel(action)),
        { c.actions.onAction(action) },
        enabled = if (next) c.state.canNext else c.state.canPrevious,
        size = size, iconSize = iconSize, contentColor = iconColor,
    )
}

/** Double-tap artwork seek: right half forward, left half backward (flipped in RTL), like SeekSkip. */
@Composable
internal fun Modifier.playerSeekDoubleTap(c: PlayerRenderContext, enabled: Boolean = true): Modifier {
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    if (!enabled || !c.state.canSeek) return this
    return pointerInput(c, enabled, ltr) {
        detectTapGestures(
            onTap = { },
            onDoubleTap = { off ->
                val forward = if (ltr) off.x >= size.width / 2f else off.x < size.width / 2f
                c.actions.onAction(if (forward) PlayerAction.SEEK_FORWARD else PlayerAction.SEEK_BACKWARD)
            },
        )
    }
}

/** HSV helpers for the V9 dynamic background/text math. */
internal fun hsvOf(color: Color): FloatArray {
    val r = color.red; val g = color.green; val b = color.blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == r -> ((g - b) / d) % 6f
        max == g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    } * 60f
    val s = if (max == 0f) 0f else d / max
    return floatArrayOf(if (h < 0f) h + 360f else h, s, max)
}

internal fun hsvColor(h: Float, s: Float, v: Float, alpha: Float = 1f): Color {
    val c = v * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r, g, b) = when ((h / 60f).toInt() % 6) {
        0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m, alpha)
}

/** V9 dynamic colors: darkened/saturated field, light text on dark schemes, accent from dominant. */
internal data class V9Colors(val background: Color, val text: Color, val accent: Color, val iconOnAccent: Color)

internal fun v9Colors(dominant: Color, dark: Boolean): V9Colors {
    val (h, s, v) = hsvOf(dominant)
    val bg = if (dark) hsvColor(h, s.coerceIn(.12f, .35f), .08f) else hsvColor(h, s.coerceIn(.04f, .12f), .96f)
    val text = if (dark) hsvColor(h, s.coerceAtMost(.12f), .96f) else hsvColor(h, s.coerceIn(.12f, .35f), .08f)
    val accent = dominant
    val icon = if (accent.luminance() > .5f) Color.Black else Color.White
    return V9Colors(bg, text, accent, icon)
}

/** V10 editorial colors: field/accent approximated HCT tones via linear lerp toward black/white. */
internal fun v10FieldColor(dominant: Color, dark: Boolean): Color =
    if (dark) androidx.compose.ui.graphics.lerp(dominant, Color.Black, .7f)
    else androidx.compose.ui.graphics.lerp(dominant, Color.White, .9f)

internal fun v10AccentColor(dominant: Color, dark: Boolean): Color =
    if (dark) androidx.compose.ui.graphics.lerp(dominant, Color.White, .9f)
    else androidx.compose.ui.graphics.lerp(dominant, Color.Black, .9f)

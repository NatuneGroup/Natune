package moe.rukamori.archivetune.shared.ui.marquee

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.glass.motionAllowed

/** The player waits this long before its single pass, so the first words can be read before anything moves. */
internal const val TRACK_MARQUEE_DELAY_MS = 3000L
internal const val MARQUEE_REST_MS = 1200L
internal const val SETTINGS_MARQUEE_DELAY_MS = 1200L
internal const val SETTINGS_MARQUEE_PASSES = 2

private val MarqueeSpacing = 30.dp
private val MarqueeVelocity = 30.dp
private val MarqueeFadeWidth = 24.dp

/**
 * One line of text. Text that overflows scrolls while [active] holds and fades at the edges; text that fits is neither
 * animated nor faded. The offset is read from the time elapsed since the phase began, inside the layer and draw
 * lambdas, so each frame moves the layer without recomposing and nothing runs while the line is not composed.
 */
@Composable
internal fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    centered: Boolean = false,
    fade: Boolean = true,
    active: Boolean = motionAllowed(),
    phaseKey: Any? = null,
    iterations: Int = 1,
    initialDelayMs: Long = TRACK_MARQUEE_DELAY_MS,
    restMs: Long = MARQUEE_REST_MS,
) {
    val density = LocalDensity.current
    val spacingPx = with(density) { MarqueeSpacing.toPx() }
    val velocityPx = with(density) { MarqueeVelocity.toPx() }
    val fadePx = with(density) { MarqueeFadeWidth.toPx() }
    val timing = MarqueeTiming(initialDelayMs = initialDelayMs, iterations = iterations, restMs = restMs)
    val phase = MarqueePhaseKey(phaseKey, text)
    val startMs = remember(phase) { MarqueePhases.begin(phase) }
    DisposableEffect(phase) { onDispose { MarqueePhases.end(phase) } }

    var viewportWidthPx by remember { mutableIntStateOf(0) }
    var textWidthPx by remember { mutableIntStateOf(0) }
    val overflowing by remember { derivedStateOf { viewportWidthPx > 0 && textWidthPx > viewportWidthPx } }
    val running = active && overflowing && iterations > 0
    val frameMs = remember(startMs) { mutableLongStateOf(marqueeClockMs()) }

    fun travelPx(): Float = textWidthPx.toFloat() + spacingPx

    // A line that stops running sits at its start; a stale mid-pass offset would clip text that now fits.
    fun scrollPx(): Float {
        if (!running) return 0f
        val travel = travelPx()
        return marqueeOffsetPx(frameMs.longValue - startMs, travel, marqueePassMs(travel, velocityPx), timing)
    }

    LaunchedEffect(running, startMs) {
        if (!running) return@LaunchedEffect
        frameMs.longValue = marqueeClockMs()
        val endMs = marqueeEndMs(marqueePassMs(travelPx(), velocityPx), timing)
        // Write the end frame before leaving, or the line stops short of its rest offset.
        do {
            withFrameNanos { frameMs.longValue = marqueeClockMs() }
        } while (frameMs.longValue - startMs < endMs)
    }

    val fadeModifier = if (fade && overflowing) {
        Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val edge = fadePx.coerceAtMost(size.width / 2f)
                val leftPx = marqueeLeftFadePx(scrollPx(), travelPx(), edge)
                if (leftPx > 0.5f) {
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = leftPx),
                        blendMode = BlendMode.DstIn,
                    )
                }
                if (edge > 0.5f) {
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Black, Color.Transparent),
                            startX = size.width - edge,
                            endX = size.width,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .onSizeChanged { viewportWidthPx = it.width }
            .clipToBounds()
            .then(fadeModifier),
        contentAlignment = if (centered && !overflowing) Alignment.Center else Alignment.CenterStart,
    ) {
        // Unbounded width keeps the full text measurable; the clip above shows only the viewport.
        Text(
            text = text,
            modifier = Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .onSizeChanged { textWidthPx = it.width }
                .graphicsLayer { translationX = -scrollPx() },
            color = color,
            style = style,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        if (overflowing) {
            Text(
                text = text,
                modifier = Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .clearAndSetSemantics {}
                    .graphicsLayer { translationX = travelPx() - scrollPx() },
                color = color,
                style = style,
                fontWeight = fontWeight,
                fontStyle = fontStyle,
                fontFamily = fontFamily,
                letterSpacing = letterSpacing,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/**
 * A settings description: [SETTINGS_MARQUEE_PASSES] finite passes that keep their phase while the page is left and
 * re-entered. [active] is the host's own gate for visibility, resumed state and motion; it is not read from optics.
 */
@Composable
fun SettingsMarqueeText(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    phaseKey: Any? = null,
) {
    MarqueeText(
        text = text,
        modifier = modifier,
        color = color,
        style = style,
        fontWeight = fontWeight,
        active = active,
        phaseKey = phaseKey,
        iterations = SETTINGS_MARQUEE_PASSES,
        initialDelayMs = SETTINGS_MARQUEE_DELAY_MS,
        restMs = SETTINGS_MARQUEE_DELAY_MS,
    )
}

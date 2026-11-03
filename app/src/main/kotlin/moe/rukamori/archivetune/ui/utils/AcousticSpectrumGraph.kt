package moe.rukamori.archivetune.ui.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.acoustics.AcousticAuditResult
import moe.rukamori.archivetune.acoustics.AcousticHeatAxis
import moe.rukamori.archivetune.acoustics.AcousticSpectrum

private val MarkerLabelBand = 16.dp
private val AxisLabelBand = 20.dp
private val GraphHeight = 156.dp
private val MarkerGap = 4.dp
private val TickGap = 4.dp
private val CurveStroke = 1.5.dp
private val DashOn = 5.dp
private val DashOff = 4.dp
private const val REVEAL_MS = 450

private class CurvePaths(
    val fill: Path,
    val line: Path,
)

/**
 * The spectrum as one filled curve: pitch runs left to right up to [AcousticSpectrum.nyquistHz],
 * loudness runs bottom to top. A dashed marker sits at the detected cutoff and a few kHz labels sit
 * under the axis. The curve path and the label layouts are built once per result and size in
 * remember blocks; the draw pass itself allocates nothing. [bands] comes from
 * [AcousticSpectrum.of] on [Dispatchers.Default], then the whole graph fades in once.
 */
private fun buildCurvePaths(
    bands: FloatArray,
    width: Float,
    top: Float,
    bottom: Float,
): CurvePaths {
    val fill = Path()
    val line = Path()
    val span = bottom - top
    val step = width / bands.size
    fun x(i: Int) = step * (i + 0.5f)
    fun y(i: Int) = bottom - bands[i].coerceIn(0f, 1f) * span
    fun moveTo(px: Float, py: Float) {
        line.moveTo(px, py)
        fill.moveTo(px, py)
    }
    fun lineTo(px: Float, py: Float) {
        line.lineTo(px, py)
        fill.lineTo(px, py)
    }
    fun quad(cx: Float, cy: Float, px: Float, py: Float) {
        line.quadraticTo(cx, cy, px, py)
        fill.quadraticTo(cx, cy, px, py)
    }
    // Quadratic through the midpoints of consecutive band points: a smooth line that stays
    // inside the data without the overshoot a cubic spline can show on a hard shelf.
    moveTo(0f, y(0))
    lineTo(x(0), y(0))
    for (i in 0 until bands.size - 1) {
        quad(x(i), y(i), (x(i) + x(i + 1)) / 2f, (y(i) + y(i + 1)) / 2f)
    }
    lineTo(x(bands.size - 1), y(bands.size - 1))
    lineTo(width, y(bands.size - 1))
    fill.lineTo(width, bottom)
    fill.lineTo(0f, bottom)
    fill.close()
    return CurvePaths(fill, line)
}

@Composable
internal fun AcousticSpectrumGraph(result: AcousticAuditResult, description: String) {
    val spectrum by
        produceState<AcousticSpectrum?>(null, result) {
            value =
                withContext(Dispatchers.Default) {
                    AcousticSpectrum.of(result)
                }
        }
    val reveal = remember { Animatable(0f) }
    val animationsDisabled = LocalAnimationsDisabled.current
    LaunchedEffect(spectrum, animationsDisabled) {
        if (spectrum?.bands?.isNotEmpty() == true && reveal.value < 1f) {
            if (animationsDisabled) {
                reveal.snapTo(1f)
            } else {
                reveal.animateTo(1f, tween(REVEAL_MS, easing = FastOutSlowInEasing))
            }
        }
    }
    val curveColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val markerColor = MaterialTheme.colorScheme.tertiary
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = axisColor, fontSize = 10.sp)
    val markerStyle = TextStyle(color = markerColor, fontSize = 10.sp)
    val maxKHz = result.nyquistHz / 1000f
    val ticks =
        remember(maxKHz) {
            val step = AcousticHeatAxis.niceKHzStep(maxKHz)
            buildList {
                var khz = 0
                while (khz <= maxKHz) {
                    add(khz)
                    khz += step
                }
            }
        }
    val tickLabels =
        ticks.map { khz ->
            khz to
                if (khz == 0) {
                    stringResource(R.string.acoustic_quality_axis_zero)
                } else {
                    stringResource(R.string.acoustic_quality_axis_khz, khz)
                }
        }
    val cutoffHz =
        result.cutoffHz?.takeIf { it.isFinite() && it > 0f && it < result.nyquistHz }
    val markerText =
        cutoffHz?.let { stringResource(R.string.acoustic_quality_cutoff_marker, it / 1000f) }
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(GraphHeight)
            .semantics { contentDescription = description },
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val plotTopPx = with(density) { MarkerLabelBand.toPx() }
        val bottomPx = with(density) { GraphHeight.toPx() - AxisLabelBand.toPx() }
        val markerGapPx = with(density) { MarkerGap.toPx() }
        val tickGapPx = with(density) { TickGap.toPx() }
        val strokePx = with(density) { CurveStroke.toPx() }
        val dashOnPx = with(density) { DashOn.toPx() }
        val dashOffPx = with(density) { DashOff.toPx() }
        val bands = spectrum?.bands
        val paths =
            remember(bands, widthPx, plotTopPx, bottomPx) {
                if (bands == null || bands.isEmpty() || widthPx <= 0f || bottomPx <= plotTopPx) {
                    null
                } else {
                    buildCurvePaths(bands, widthPx, plotTopPx, bottomPx)
                }
            }
        val measuredTicks =
            remember(tickLabels, labelStyle) {
                tickLabels.map { (khz, label) -> khz to textMeasurer.measure(label, labelStyle) }
            }
        val markerLayout =
            remember(markerText, markerStyle) {
                markerText?.let { textMeasurer.measure(it, markerStyle) }
            }
        val fillBrush =
            remember(curveColor, plotTopPx, bottomPx) {
                Brush.verticalGradient(
                    0f to curveColor.copy(alpha = 0.28f),
                    1f to curveColor.copy(alpha = 0.02f),
                    startY = plotTopPx,
                    endY = bottomPx,
                )
            }
        val lineStroke = remember(strokePx) { Stroke(width = strokePx) }
        val dashEffect =
            remember(dashOnPx, dashOffPx) {
                PathEffect.dashPathEffect(floatArrayOf(dashOnPx, dashOffPx))
            }
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = reveal.value },
        ) {
            val nyquist = result.nyquistHz
            drawLine(
                axisColor.copy(alpha = 0.4f),
                Offset(0f, bottomPx),
                Offset(size.width, bottomPx),
                strokeWidth = 1f,
            )
            if (nyquist > 0f) {
                for ((khz, layout) in measuredTicks) {
                    val x = khz * 1000f / nyquist * size.width
                    val left =
                        if (khz == 0) {
                            0f
                        } else {
                            (x - layout.size.width / 2f)
                                .coerceIn(0f, maxOf(0f, size.width - layout.size.width))
                        }
                    drawText(layout, topLeft = Offset(left, bottomPx + tickGapPx))
                }
            }
            paths?.let {
                drawPath(it.fill, brush = fillBrush)
                drawPath(it.line, color = curveColor, style = lineStroke)
            }
            if (cutoffHz != null && markerLayout != null && nyquist > 0f) {
                val markerX = (cutoffHz / nyquist * size.width).coerceIn(0f, size.width)
                drawLine(
                    markerColor,
                    Offset(markerX, plotTopPx),
                    Offset(markerX, bottomPx),
                    strokeWidth = strokePx,
                    pathEffect = dashEffect,
                )
                val labelWidth = markerLayout.size.width.toFloat()
                val left =
                    (markerX - labelWidth - markerGapPx)
                        .takeIf { it >= 0f }
                        ?.coerceAtMost(maxOf(0f, size.width - labelWidth))
                        ?: (markerX + markerGapPx)
                            .coerceIn(0f, maxOf(0f, size.width - labelWidth))
                drawText(markerLayout, topLeft = Offset(left, 0f))
            }
        }
    }
}

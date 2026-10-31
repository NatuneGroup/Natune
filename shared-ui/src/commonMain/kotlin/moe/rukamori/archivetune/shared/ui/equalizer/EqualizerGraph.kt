package moe.rukamori.archivetune.shared.ui.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.ui.resources.Res
import org.jetbrains.compose.resources.stringResource

/** The gain span the graph draws above and below 0 dB by default. */
const val EQUALIZER_GRAPH_RANGE_DB = 12.0

private const val MIN_RANGE_DB = 1.0
private const val AXIS_MIN_HZ = 20.0
private const val AXIS_MAX_HZ = 20_000.0
private val GRAPH_HEIGHT = 168.dp
private val GRAPH_INSET = 12.dp
private val HANDLE_RADIUS = 6.dp
private val LINE_WIDTH = 1.5.dp
private val AXIS_LABEL_HEIGHT = 18.dp

/**
 * One handle per band on a log frequency axis, dragged vertically to set that band's gain.
 *
 * The gesture is keyed only on [enabled] and the drawn span, so a live update from the host does not cancel a drag.
 * The handle follows the pointer from a local draft, so it does not jump back while the host publishes a value.
 * While dragging, [onGainChange] fires once per 0.1 dB step; a host applies it live, because the DSP retunes in place.
 * [onGainChangeFinished] fires once when the drag ends or is cancelled, with the last value, so a host persists once.
 * Hosts without a band source must not show this graph.
 */
@Composable
fun EqualizerGraph(
    bands: List<EqualizerBand>,
    onGainChange: (index: Int, gainDb: Double) -> Unit,
    onGainChangeFinished: (index: Int, gainDb: Double) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    rangeDb: Double = EQUALIZER_GRAPH_RANGE_DB,
) {
    val span = rangeDb.coerceIn(MIN_RANGE_DB, EqualizerBand.MAX_GAIN_DB)
    val currentBands by rememberUpdatedState(bands)
    val currentOnChange by rememberUpdatedState(onGainChange)
    val currentOnFinished by rememberUpdatedState(onGainChangeFinished)
    var drag by remember { mutableStateOf<EqualizerGraphDrag?>(null) }
    val description = stringResource(Res.string.equalizer_graph_description)
    val curveColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val handleFill = MaterialTheme.colorScheme.surface
    val activeDrag = drag
    val readout = if (activeDrag != null && activeDrag.index in bands.indices) {
        stringResource(
            Res.string.equalizer_graph_readout,
            frequencyReadout(bands[activeDrag.index].frequencyHz),
            formatGainDb(activeDrag.gainDb),
        )
    } else {
        " "
    }
    val gestures = Modifier.pointerInput(enabled, span) {
        if (!enabled) return@pointerInput
        val inset = GRAPH_INSET.toPx()
        var active = -1
        var startGain = 0.0
        var lastGain = 0.0
        fun geometry() = EqualizerGraphGeometry(size.width.toFloat(), size.height.toFloat(), inset, span)
        fun finish() {
            if (active in 0 until currentBands.size && lastGain != startGain) currentOnFinished(active, lastGain)
            active = -1
            drag = null
        }
        detectDragGestures(
            onDragStart = { offset ->
                val bandList = currentBands
                active = geometry().nearestBand(bandList, offset.x)
                if (active >= 0) {
                    startGain = bandList[active].gainDb
                    lastGain = geometry().gainAt(offset.y)
                    drag = EqualizerGraphDrag(active, lastGain)
                    if (lastGain != startGain) currentOnChange(active, lastGain)
                }
            },
            onDrag = { change, _ ->
                change.consume()
                if (active in 0 until currentBands.size) {
                    val next = geometry().gainAt(change.position.y)
                    if (next != lastGain) {
                        lastGain = next
                        drag = EqualizerGraphDrag(active, next)
                        currentOnChange(active, next)
                    }
                }
            },
            onDragEnd = { finish() },
            onDragCancel = { finish() },
        )
    }
    Column(modifier = modifier.alpha(if (enabled) 1f else 0.38f)) {
        Text(
            text = readout,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(GRAPH_HEIGHT)
                .semantics { contentDescription = description }
                .then(gestures),
        ) {
            val inset = GRAPH_INSET.toPx()
            val line = LINE_WIDTH.toPx()
            val geometry = EqualizerGraphGeometry(size.width, size.height, inset, span)
            fun gainShown(index: Int): Double {
                val shown = drag
                return if (shown != null && shown.index == index) shown.gainDb else bands[index].gainDb
            }
            val zeroY = geometry.yOf(0.0)
            drawLine(gridColor, Offset(inset, zeroY), Offset(size.width - inset, zeroY), strokeWidth = line)
            val upperY = geometry.yOf(span)
            drawLine(gridColor.copy(alpha = 0.4f), Offset(inset, upperY), Offset(size.width - inset, upperY), strokeWidth = line)
            val lowerY = geometry.yOf(-span)
            drawLine(gridColor.copy(alpha = 0.4f), Offset(inset, lowerY), Offset(size.width - inset, lowerY), strokeWidth = line)
            val sorted = bands.indices.sortedBy { bands[it].frequencyHz }
            val curve = Path()
            sorted.forEachIndexed { position, index ->
                val x = geometry.xOf(bands[index].frequencyHz)
                val y = geometry.yOf(gainShown(index))
                if (position == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
            }
            if (sorted.size > 1) drawPath(curve, curveColor, style = Stroke(width = 2f * line, cap = StrokeCap.Round))
            val radius = HANDLE_RADIUS.toPx()
            bands.indices.forEach { index ->
                val center = Offset(geometry.xOf(bands[index].frequencyHz), geometry.yOf(gainShown(index)))
                if (drag?.index == index) drawCircle(curveColor.copy(alpha = 0.18f), radius = radius * 2.2f, center = center)
                drawCircle(handleFill, radius = radius, center = center)
                drawCircle(curveColor, radius = radius, center = center, style = Stroke(width = line))
            }
        }
        EqualizerAxisLabels(bands = bands, inset = GRAPH_INSET)
    }
}

/** A drag in progress: the band it moves and the gain the pointer is holding it at. */
private data class EqualizerGraphDrag(val index: Int, val gainDb: Double)

/** Maps frequency and gain onto the plot. Frequency runs on a log axis, so octaves sit evenly apart. */
internal class EqualizerGraphGeometry(
    widthPx: Float,
    heightPx: Float,
    insetPx: Float,
    private val rangeDb: Double,
) {
    private val left = insetPx
    private val top = insetPx
    private val plotWidth = (widthPx - 2f * insetPx).coerceAtLeast(1f)
    private val plotHeight = (heightPx - 2f * insetPx).coerceAtLeast(1f)

    fun xOf(frequencyHz: Double): Float = left + plotWidth * frequencyFraction(frequencyHz).toFloat()

    fun yOf(gainDb: Double): Float {
        val clamped = gainDb.coerceIn(-rangeDb, rangeDb)
        return top + plotHeight * ((rangeDb - clamped) / (2.0 * rangeDb)).toFloat()
    }

    /** The gain under the pointer at [y], kept inside the drawn span and rounded to 0.1 dB. */
    fun gainAt(y: Float): Double {
        val fraction = ((y - top) / plotHeight).toDouble().coerceIn(0.0, 1.0)
        return quantizeGainDb(rangeDb - fraction * 2.0 * rangeDb)
    }

    /** The band whose frequency sits nearest to [x], or -1 when there are no bands. */
    fun nearestBand(bands: List<EqualizerBand>, x: Float): Int =
        bands.indices.minByOrNull { abs(xOf(bands[it].frequencyHz) - x) } ?: -1
}

/** Where [frequencyHz] falls on the log axis: 0 at the lowest drawn frequency, 1 at the highest. */
internal fun frequencyFraction(frequencyHz: Double): Double {
    val low = log10(AXIS_MIN_HZ)
    val high = log10(AXIS_MAX_HZ)
    return (log10(frequencyHz.coerceIn(AXIS_MIN_HZ, AXIS_MAX_HZ)) - low) / (high - low)
}

/** Gains step in 0.1 dB. That bounds how often a drag reports, and keeps the readout equal to the stored value. */
internal fun quantizeGainDb(gainDb: Double): Double = (gainDb * 10.0).roundToInt() / 10.0

/** A signed one-decimal gain such as "+3.5", "-2.0" or "0.0", built without locale formatting. */
internal fun formatGainDb(gainDb: Double): String {
    val tenths = (abs(gainDb) * 10.0).roundToInt()
    val sign = if (tenths == 0) "" else if (gainDb > 0) "+" else "-"
    return "$sign${tenths / 10}.${tenths % 10}"
}

@Composable
private fun frequencyReadout(frequencyHz: Double): String {
    val hz = frequencyHz.roundToInt()
    return if (hz >= 1_000 && hz % 1_000 == 0) {
        stringResource(Res.string.equalizer_graph_readout_khz, hz / 1_000)
    } else {
        stringResource(Res.string.equalizer_graph_readout_hz, hz)
    }
}

@Composable
private fun axisLabel(frequencyHz: Double): String {
    val hz = frequencyHz.roundToInt()
    return if (hz >= 1_000 && hz % 1_000 == 0) {
        stringResource(Res.string.equalizer_graph_axis_khz, hz / 1_000)
    } else {
        hz.toString()
    }
}

/** Frequency labels placed at the same log positions as their handles, so each label sits under its band. */
@Composable
private fun EqualizerAxisLabels(bands: List<EqualizerBand>, inset: Dp) {
    val labels = bands.map { axisLabel(it.frequencyHz) }
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Layout(
        content = {
            labels.forEach { label -> Text(text = label, style = style, color = color) }
        },
        modifier = Modifier.fillMaxWidth().height(AXIS_LABEL_HEIGHT),
    ) { measurables, constraints ->
        val insetPx = inset.roundToPx()
        val plotPx = (constraints.maxWidth - 2 * insetPx).coerceAtLeast(1)
        val placeables = measurables.map { it.measure(Constraints()) }
        val heightPx = AXIS_LABEL_HEIGHT.roundToPx()
        layout(constraints.maxWidth, heightPx) {
            placeables.forEachIndexed { index, placeable ->
                val center = insetPx + plotPx * frequencyFraction(bands[index].frequencyHz)
                placeable.place((center - placeable.width / 2.0).roundToInt(), 0)
            }
        }
    }
}

package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The stream-quality badge: a lossless-wave glyph ahead of the quality label. A lossless label
 * gets a band of full-strength colour sweeping across it while motion is on, since a confirmed
 * lossless stream is the one claim worth the shine. Callers centre it in their own row.
 */
@Composable
internal fun QualityBadge(
    label: String,
    lossless: Boolean,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
) {
    val motion = LocalPlayerControlMotion.current.active
    val style = MaterialTheme.typography.labelMedium.copy(
        fontSize = (MaterialTheme.typography.labelMedium.fontSize.value + 1).sp,
    )
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        LosslessWaveGlyph(color.copy(alpha = if (lossless) .78f else .68f), Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        if (lossless && motion) {
            QualityShimmerText(label, style, color)
        } else {
            Text(
                text = label,
                style = style,
                color = color.copy(alpha = .68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The lossless-wave mark, drawn in the 24-unit viewport it was authored in and scaled to [modifier]. */
@Composable
private fun LosslessWaveGlyph(color: Color, modifier: Modifier) {
    val wave = remember { losslessWavePath() }
    Canvas(modifier) {
        val factor = size.width / 24f
        withTransform({ scale(factor, factor, Offset.Zero) }) {
            drawPath(wave, color, style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private fun losslessWavePath(): Path = Path().apply {
    moveTo(2f, 12f)
    cubicTo(4f, 12f, 4f, 6.5f, 7f, 6.5f)
    cubicTo(10f, 6.5f, 10f, 17.5f, 13f, 17.5f)
    cubicTo(16f, 17.5f, 16f, 6.5f, 19f, 6.5f)
    cubicTo(21f, 6.5f, 21f, 12f, 22f, 12f)
}

@Composable
private fun QualityShimmerText(text: String, style: TextStyle, color: Color) {
    var widthPx by remember { mutableIntStateOf(0) }
    val progress by rememberInfiniteTransition(label = "quality-shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 3_000, easing = LinearEasing), RepeatMode.Restart),
        label = "quality-shimmer-progress",
    )
    val base = color.copy(alpha = .68f)
    val brush =
        if (widthPx <= 0) {
            Brush.linearGradient(listOf(base, base))
        } else {
            val band = widthPx * .6f
            val center = -band + progress * (widthPx + 2 * band)
            Brush.linearGradient(
                colorStops = arrayOf(0f to base, .5f to color, 1f to base),
                start = Offset(center - band, 0f),
                end = Offset(center + band, 0f),
            )
        }
    Text(
        text = text,
        style = style.copy(brush = brush, fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.onSizeChanged { widthPx = it.width },
    )
}

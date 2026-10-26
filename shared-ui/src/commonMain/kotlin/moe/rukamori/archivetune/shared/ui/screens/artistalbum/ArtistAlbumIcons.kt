/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.screens.artistalbum

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Glyphs this package needs that the player's symbol set does not carry (radio, block, library, download). */
internal enum class ArtistAlbumSymbol {
    PLAY, PAUSE, SHUFFLE, RADIO, SHARE, LINK, BLOCK, MORE_HORIZONTAL, MORE_VERTICAL, BACK, CHEVRON_RIGHT,
    LIBRARY, ONLINE, ALBUM, DOWNLOAD, DOWNLOAD_DONE, REMOVE, HEART, HEART_FILLED, CHECK, SORT, ARROW_DOWN,
    ARROW_UP, CLOSE, SELECT_ALL, ADD, PLAYING, PERSON,
}

/** Decorative when [description] is null; otherwise the glyph carries that accessible name. */
@Composable
internal fun ArtistAlbumIcon(
    symbol: ArtistAlbumSymbol,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    description: String? = null,
) {
    Canvas(
        modifier
            .size(size)
            .semantics { if (description != null) this.contentDescription = description },
    ) {
        drawArtistAlbumSymbol(symbol, tint)
    }
}

private fun DrawScope.drawArtistAlbumSymbol(symbol: ArtistAlbumSymbol, tint: Color) {
    val strokeWidth = size.minDimension * 0.09f
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val w = size.width
    val h = size.height

    fun at(x: Float, y: Float) = Offset(w * x, h * y)

    fun segment(x1: Float, y1: Float, x2: Float, y2: Float) {
        drawLine(color = tint, start = at(x1, y1), end = at(x2, y2), strokeWidth = strokeWidth, cap = StrokeCap.Round)
    }

    fun polyline(vararg points: Pair<Float, Float>) {
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = w * point.first
            val y = h * point.second
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = tint, style = stroke)
    }

    fun filledPolygon(vararg points: Pair<Float, Float>) {
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = w * point.first
            val y = h * point.second
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path = path, color = tint)
    }

    fun ring(cx: Float, cy: Float, radius: Float) {
        drawCircle(color = tint, radius = w * radius, center = at(cx, cy), style = stroke)
    }

    fun dot(cx: Float, cy: Float, radius: Float) {
        drawCircle(color = tint, radius = w * radius, center = at(cx, cy))
    }

    fun bar(x: Float, y: Float, barWidth: Float, barHeight: Float) {
        drawRoundRect(
            color = tint,
            topLeft = at(x, y),
            size = Size(w * barWidth, h * barHeight),
            cornerRadius = CornerRadius(w * 0.03f),
        )
    }

    fun waves(inner: Float) {
        val arcSize = Size(w * inner * 2f, h * inner * 2f)
        val topLeft = at(0.5f - inner, 0.5f - inner)
        drawArc(color = tint, startAngle = -45f, sweepAngle = 90f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        drawArc(color = tint, startAngle = 135f, sweepAngle = 90f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
    }

    when (symbol) {
        ArtistAlbumSymbol.PLAY -> filledPolygon(0.3f to 0.2f, 0.8f to 0.5f, 0.3f to 0.8f)
        ArtistAlbumSymbol.PAUSE -> {
            bar(0.27f, 0.2f, 0.14f, 0.6f)
            bar(0.59f, 0.2f, 0.14f, 0.6f)
        }
        ArtistAlbumSymbol.SHUFFLE -> {
            polyline(0.12f to 0.3f, 0.38f to 0.3f, 0.62f to 0.7f, 0.88f to 0.7f)
            polyline(0.12f to 0.7f, 0.38f to 0.7f, 0.62f to 0.3f, 0.88f to 0.3f)
            polyline(0.76f to 0.2f, 0.88f to 0.3f, 0.76f to 0.4f)
            polyline(0.76f to 0.6f, 0.88f to 0.7f, 0.76f to 0.8f)
        }
        ArtistAlbumSymbol.RADIO -> {
            dot(0.5f, 0.5f, 0.07f)
            waves(0.22f)
            waves(0.38f)
        }
        ArtistAlbumSymbol.SHARE -> {
            dot(0.75f, 0.25f, 0.1f)
            dot(0.25f, 0.5f, 0.1f)
            dot(0.75f, 0.75f, 0.1f)
            segment(0.75f, 0.25f, 0.25f, 0.5f)
            segment(0.25f, 0.5f, 0.75f, 0.75f)
        }
        ArtistAlbumSymbol.LINK -> {
            drawRoundRect(
                color = tint,
                topLeft = at(0.08f, 0.36f),
                size = Size(w * 0.48f, h * 0.28f),
                cornerRadius = CornerRadius(h * 0.14f),
                style = stroke,
            )
            drawRoundRect(
                color = tint,
                topLeft = at(0.44f, 0.36f),
                size = Size(w * 0.48f, h * 0.28f),
                cornerRadius = CornerRadius(h * 0.14f),
                style = stroke,
            )
        }
        ArtistAlbumSymbol.BLOCK -> {
            ring(0.5f, 0.5f, 0.38f)
            segment(0.22f, 0.78f, 0.78f, 0.22f)
        }
        ArtistAlbumSymbol.MORE_HORIZONTAL -> {
            dot(0.2f, 0.5f, 0.07f)
            dot(0.5f, 0.5f, 0.07f)
            dot(0.8f, 0.5f, 0.07f)
        }
        ArtistAlbumSymbol.MORE_VERTICAL -> {
            dot(0.5f, 0.2f, 0.07f)
            dot(0.5f, 0.5f, 0.07f)
            dot(0.5f, 0.8f, 0.07f)
        }
        ArtistAlbumSymbol.BACK -> polyline(0.62f to 0.2f, 0.36f to 0.5f, 0.62f to 0.8f)
        ArtistAlbumSymbol.CHEVRON_RIGHT -> polyline(0.38f to 0.2f, 0.64f to 0.5f, 0.38f to 0.8f)
        ArtistAlbumSymbol.LIBRARY -> {
            segment(0.2f, 0.3f, 0.8f, 0.3f)
            segment(0.2f, 0.5f, 0.8f, 0.5f)
            segment(0.2f, 0.7f, 0.55f, 0.7f)
        }
        ArtistAlbumSymbol.ONLINE -> {
            ring(0.5f, 0.5f, 0.36f)
            drawOval(color = tint, topLeft = at(0.36f, 0.14f), size = Size(w * 0.28f, h * 0.72f), style = stroke)
            segment(0.14f, 0.5f, 0.86f, 0.5f)
        }
        ArtistAlbumSymbol.ALBUM -> {
            ring(0.5f, 0.5f, 0.36f)
            dot(0.5f, 0.5f, 0.1f)
        }
        ArtistAlbumSymbol.DOWNLOAD -> {
            segment(0.5f, 0.18f, 0.5f, 0.62f)
            polyline(0.32f to 0.5f, 0.5f to 0.68f, 0.68f to 0.5f)
            segment(0.2f, 0.84f, 0.8f, 0.84f)
        }
        ArtistAlbumSymbol.DOWNLOAD_DONE -> {
            polyline(0.3f to 0.5f, 0.44f to 0.64f, 0.72f to 0.34f)
            segment(0.2f, 0.84f, 0.8f, 0.84f)
        }
        ArtistAlbumSymbol.REMOVE -> {
            ring(0.5f, 0.5f, 0.36f)
            segment(0.32f, 0.5f, 0.68f, 0.5f)
        }
        ArtistAlbumSymbol.HEART, ArtistAlbumSymbol.HEART_FILLED -> {
            val path = Path().apply {
                moveTo(w * 0.5f, h * 0.84f)
                cubicTo(w * 0.2f, h * 0.64f, w * 0.08f, h * 0.48f, w * 0.2f, h * 0.3f)
                cubicTo(w * 0.3f, h * 0.16f, w * 0.46f, h * 0.18f, w * 0.5f, h * 0.32f)
                cubicTo(w * 0.54f, h * 0.18f, w * 0.7f, h * 0.16f, w * 0.8f, h * 0.3f)
                cubicTo(w * 0.92f, h * 0.48f, w * 0.8f, h * 0.64f, w * 0.5f, h * 0.84f)
                close()
            }
            if (symbol == ArtistAlbumSymbol.HEART_FILLED) {
                drawPath(path = path, color = tint)
            } else {
                drawPath(path = path, color = tint, style = stroke)
            }
        }
        ArtistAlbumSymbol.CHECK -> polyline(0.2f to 0.52f, 0.42f to 0.74f, 0.8f to 0.3f)
        ArtistAlbumSymbol.SORT -> {
            segment(0.2f, 0.3f, 0.8f, 0.3f)
            segment(0.3f, 0.5f, 0.7f, 0.5f)
            segment(0.4f, 0.7f, 0.6f, 0.7f)
        }
        ArtistAlbumSymbol.ARROW_DOWN -> {
            segment(0.5f, 0.2f, 0.5f, 0.8f)
            polyline(0.3f to 0.6f, 0.5f to 0.8f, 0.7f to 0.6f)
        }
        ArtistAlbumSymbol.ARROW_UP -> {
            segment(0.5f, 0.8f, 0.5f, 0.2f)
            polyline(0.3f to 0.4f, 0.5f to 0.2f, 0.7f to 0.4f)
        }
        ArtistAlbumSymbol.CLOSE -> {
            segment(0.25f, 0.25f, 0.75f, 0.75f)
            segment(0.75f, 0.25f, 0.25f, 0.75f)
        }
        ArtistAlbumSymbol.SELECT_ALL -> {
            drawRoundRect(
                color = tint,
                topLeft = at(0.16f, 0.16f),
                size = Size(w * 0.68f, h * 0.68f),
                cornerRadius = CornerRadius(w * 0.12f),
                style = stroke,
            )
            polyline(0.3f to 0.5f, 0.45f to 0.66f, 0.72f to 0.36f)
        }
        ArtistAlbumSymbol.ADD -> {
            segment(0.5f, 0.22f, 0.5f, 0.78f)
            segment(0.22f, 0.5f, 0.78f, 0.5f)
        }
        ArtistAlbumSymbol.PLAYING -> {
            bar(0.2f, 0.5f, 0.14f, 0.35f)
            bar(0.43f, 0.25f, 0.14f, 0.6f)
            bar(0.66f, 0.4f, 0.14f, 0.45f)
        }
        ArtistAlbumSymbol.PERSON -> {
            dot(0.5f, 0.32f, 0.17f)
            drawArc(
                color = tint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = at(0.2f, 0.55f),
                size = Size(w * 0.6f, h * 0.6f),
            )
        }
    }
}

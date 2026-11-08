package moe.rukamori.archivetune.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.ui.component.MarqueeText

/** The artwork edge follows the measured title and artist block, including font scaling. */
internal object LyricsHeaderMetrics {
    fun artworkSize(rowWidthDp: Float, textBlockHeightDp: Float): Float =
        textBlockHeightDp.coerceAtLeast(0f).coerceAtMost(rowWidthDp.coerceAtLeast(0f))

    fun artworkGapDp(compact: Boolean): Float = if (compact) 8f else 14f
}

/**
 * The lyrics sheet's header: the track's artwork, its title and artist, each on one line that scrolls
 * when it does not fit. [compact] tightens the gap in short windows.
 */
@Composable
internal fun AppleMusicTrackHeader(
    mediaMetadata: MediaMetadata,
    foregroundColor: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val artistText =
        remember(mediaMetadata.id, mediaMetadata.artists) {
            mediaMetadata.artists.joinToString { it.name }
        }

    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(foregroundColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                PlayerCoverImage(
                    imageUrl = mediaMetadata.thumbnailUrl,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (mediaMetadata.thumbnailUrl == null) {
                    Icon(
                        painter = painterResource(R.drawable.player_music_note),
                        contentDescription = null,
                        tint = foregroundColor.copy(alpha = 0.72f),
                        modifier = Modifier.fillMaxSize(0.42f),
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.Center,
            ) {
                MarqueeText(
                    text = mediaMetadata.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = foregroundColor,
                )
                MarqueeText(
                    text = artistText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = foregroundColor.copy(alpha = 0.72f),
                )
            }
        },
    ) { measurables, constraints ->
        val gap = LyricsHeaderMetrics.artworkGapDp(compact).dp.roundToPx().coerceAtMost(constraints.maxWidth)
        val textMeasurable = measurables[1]
        val textHeight = textMeasurable.minIntrinsicHeight((constraints.maxWidth - gap).coerceAtLeast(0))
        val reservedEdge = LyricsHeaderMetrics.artworkSize((constraints.maxWidth - gap).toFloat(), textHeight.toFloat()).toInt()
        val text = textMeasurable.measure(Constraints(maxWidth = (constraints.maxWidth - reservedEdge - gap).coerceAtLeast(0),
            maxHeight = constraints.maxHeight))
        val edge = LyricsHeaderMetrics.artworkSize((constraints.maxWidth - gap).toFloat(), text.height.toFloat()).toInt()
        val artwork = measurables[0].measure(Constraints.fixed(edge, edge))
        val height = constraints.constrainHeight(maxOf(text.height, artwork.height))
        layout(constraints.maxWidth, height) {
            artwork.placeRelative(0, (height - artwork.height) / 2)
            text.placeRelative(edge + gap, (height - text.height) / 2)
        }
    }
}

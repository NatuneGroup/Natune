package moe.rukamori.archivetune.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.ui.component.MarqueeText

/**
 * The lyrics header's row: the artwork tile, then the track's title and artist. The tile and gap match
 * upstream ArchiveTune (72 dp and 14 dp) so the header stays a compact strip above the lyrics; the sheet's
 * controls live in `LyricsScreenFooter`, so the title and artist get the rest of the row.
 */
internal object LyricsHeaderMetrics {
    const val ARTWORK_DP = 72f

    /** The gap between the artwork and the title/artist column. */
    const val ARTWORK_GAP_DP = 14f

    /** This row's end-to-end chrome, in dp, with the title and artist column left out. */
    const val CHROME_DP = ARTWORK_GAP_DP

    /** Short windows keep a smaller tile and tighter gap so the lyrics list keeps its room. */
    const val COMPACT_ARTWORK_DP = 48f
    const val COMPACT_ARTWORK_GAP_DP = 8f

    /** The artwork tile's edge for a row [rowWidthDp] wide; never wider than the row itself. */
    fun artworkSize(
        rowWidthDp: Float,
        compact: Boolean = false,
    ): Float = (if (compact) COMPACT_ARTWORK_DP else ARTWORK_DP).coerceAtMost(rowWidthDp.coerceAtLeast(0f))

    /** The gap that follows the artwork in a row [compact] tall or not. */
    fun artworkGapDp(compact: Boolean): Float =
        if (compact) COMPACT_ARTWORK_GAP_DP else ARTWORK_GAP_DP

    /** The width the title and artist keep once the artwork and its gap are taken out. */
    fun textWidth(
        rowWidthDp: Float,
        compact: Boolean = false,
    ): Float =
        (rowWidthDp - artworkSize(rowWidthDp, compact) - artworkGapDp(compact)).coerceAtLeast(0f)
}

/**
 * The lyrics sheet's header: the track's artwork, its title and artist, each on one line that scrolls
 * when it does not fit. [compact] is the short-window variant with a smaller tile and tighter gap.
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

    BoxWithConstraints(modifier.heightIn(min = if (compact) 48.dp else 72.dp)) {
        val artworkSize = LyricsHeaderMetrics.artworkSize(maxWidth.value, compact).dp
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .size(artworkSize)
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

            Spacer(modifier = Modifier.width(LyricsHeaderMetrics.artworkGapDp(compact).dp))

            Column(
                modifier = Modifier.weight(1f),
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
        }
    }
}

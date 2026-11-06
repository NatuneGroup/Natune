package moe.rukamori.archivetune.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.ui.component.MarqueeText

/**
 * The width budget of the lyrics header. The row is one line holding the artwork tile, the track's
 * title and artist, and three 48 dp targets. At fixed sizes that chrome is 302 dp of a 360 dp phone
 * row, so the title and artist were left about six characters — a clipped line of text sitting under
 * the buttons rather than a track title. The artwork therefore gives way with the row while the tap
 * targets stay 48 dp, and the title and artist keep every dp the chrome does not take, so the marquee
 * always has a readable viewport and the two can never overlap the buttons.
 */
internal object LyricsHeaderMetrics {
    /** The artwork tile's share of the row, clamped so it stays artwork and never a thumbnail. */
    const val ARTWORK_SHARE = 0.13f
    const val ARTWORK_MIN_DP = 40f
    const val ARTWORK_MAX_DP = 72f

    /** The gap between the artwork and the title/artist column. */
    const val ARTWORK_GAP_DP = 10f

    /** The gap between the title/artist column and the button group. */
    const val TRACK_GAP_DP = 6f

    /** One icon button's tap target; the filled circle drawn inside it is smaller. */
    const val ICON_TARGET_DP = 48f

    /** The gap between two icon buttons: the three of them read as one group at the row's end. */
    const val BUTTON_GAP_DP = 2f

    /** Close, favourite and overflow: the three controls the lyrics header always carries. */
    const val BUTTON_COUNT = 3

    /** The header row's end-to-end chrome, in dp, with the title and artist column left out. */
    const val CHROME_DP =
        ARTWORK_GAP_DP + TRACK_GAP_DP + ICON_TARGET_DP * BUTTON_COUNT + BUTTON_GAP_DP * (BUTTON_COUNT - 1)

    /** The artwork tile's edge for a row [rowWidthDp] wide. */
    fun artworkSize(rowWidthDp: Float): Float =
        (rowWidthDp * ARTWORK_SHARE).coerceIn(ARTWORK_MIN_DP, ARTWORK_MAX_DP)

    /** The width the title and artist keep once the artwork, its gaps and the buttons are taken out. */
    fun textWidth(rowWidthDp: Float): Float =
        (rowWidthDp - artworkSize(rowWidthDp) - CHROME_DP).coerceAtLeast(0f)
}

/**
 * The lyrics sheet's header: the track's artwork, its title and artist, and the close, favourite and
 * overflow buttons. The sizes come from [LyricsHeaderMetrics] so the text column is measured against
 * the row's real width instead of against whatever three fixed targets leave over.
 */
@Composable
internal fun AppleMusicTrackHeader(
    mediaMetadata: MediaMetadata,
    foregroundColor: Color,
    onMoreClick: () -> Unit,
    onDismissClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    isLiked: Boolean = false,
    onToggleLike: () -> Unit = {},
    onMorePositioned: ((Rect) -> Unit)? = null,
) {
    val artistText =
        remember(mediaMetadata.id, mediaMetadata.artists) {
            mediaMetadata.artists.joinToString { it.name }
        }

    BoxWithConstraints(modifier.heightIn(min = 72.dp)) {
        val artworkSize = LyricsHeaderMetrics.artworkSize(maxWidth.value).dp
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

            Spacer(modifier = Modifier.width(LyricsHeaderMetrics.ARTWORK_GAP_DP.dp))

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

            Spacer(modifier = Modifier.width(LyricsHeaderMetrics.TRACK_GAP_DP.dp))

            // Close (cross) button: the lyrics sheet is dismissed without the system back gesture.
            // It sits to the left of the favourite and overflow buttons, as upstream's lyrics bar does.
            AppleMusicHeaderIconButton(
                iconRes = R.drawable.close,
                contentDescription = stringResource(R.string.close),
                foregroundColor = foregroundColor,
                onClick = onDismissClick,
            )

            Spacer(modifier = Modifier.width(LyricsHeaderMetrics.BUTTON_GAP_DP.dp))

            AppleMusicHeaderIconButton(
                iconRes = if (isLiked) R.drawable.player_favorite else R.drawable.player_favorite_border,
                contentDescription = stringResource(
                    if (isLiked) R.string.action_remove_like else R.string.action_like,
                ),
                foregroundColor = foregroundColor,
                onClick = onToggleLike,
            )

            Spacer(modifier = Modifier.width(LyricsHeaderMetrics.BUTTON_GAP_DP.dp))

            AppleMusicHeaderIconButton(
                iconRes = R.drawable.player_more_horiz,
                contentDescription = stringResource(R.string.more_options),
                foregroundColor = foregroundColor,
                onClick = onMoreClick,
                onPositioned = onMorePositioned,
            )
        }
    }
}

@Composable
private fun AppleMusicHeaderIconButton(
    iconRes: Int,
    contentDescription: String,
    foregroundColor: Color,
    onClick: () -> Unit,
    onPositioned: ((Rect) -> Unit)? = null,
) {
    val target = LyricsHeaderMetrics.ICON_TARGET_DP.dp
    Box(
        modifier =
            Modifier
                .size(target)
                .let { base ->
                    if (onPositioned != null) {
                        base.onGloballyPositioned { coords -> onPositioned(coords.boundsInRoot()) }
                    } else {
                        base
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = target / 2),
                    role = Role.Button,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(target * 0.75f)
                    .clip(CircleShape)
                    .background(foregroundColor.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = foregroundColor,
                modifier = Modifier.fillMaxSize(0.61f),
            )
        }
    }
}

package moe.rukamori.archivetune.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R

/**
 * The credit, and beside it the lyric actions and the way out. Ported from the BitChord style, where this row
 * already sat under the scrubber, so every lyrics style names its provider and offers the lyric actions in the
 * same place. It is drawn outside the auto-hiding transport — see the portrait column — because it is the
 * sheet's chrome rather than a player control: the credit and the way out stay reachable while the transport
 * tucks itself away.
 */
@Composable
internal fun AppleMusicLyricsFooter(
    lyricsProviderName: String,
    hasLyrics: Boolean,
    foregroundColor: Color,
    onOverflowClick: () -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOverflowPositioned: ((Rect) -> Unit)? = null,
) {
    Row(
        modifier = modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Box(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(foregroundColor.copy(alpha = 0.10f))
                    .clickable(onClick = onOverflowClick)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            Text(
                text =
                    when {
                        lyricsProviderName.isNotBlank() ->
                            stringResource(R.string.lyrics_from_source, lyricsProviderName)
                        hasLyrics -> stringResource(R.string.lyrics)
                        else -> stringResource(R.string.lyrics_not_found)
                    },
                style = MaterialTheme.typography.labelLarge,
                color = foregroundColor.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LyricsTransportButton(
            iconRes = R.drawable.more_horiz,
            contentDescription = stringResource(R.string.more_options),
            iconSize = 20.dp,
            touchSize = 40.dp,
            foregroundColor = foregroundColor.copy(alpha = 0.75f),
            onClick = onOverflowClick,
            modifier =
                if (onOverflowPositioned != null) {
                    Modifier.onGloballyPositioned { coords -> onOverflowPositioned(coords.boundsInRoot()) }
                } else {
                    Modifier
                },
        )
        LyricsTransportButton(
            iconRes = R.drawable.close,
            contentDescription = stringResource(R.string.close),
            iconSize = 20.dp,
            touchSize = 40.dp,
            foregroundColor = foregroundColor.copy(alpha = 0.75f),
            onClick = onCloseClick,
        )
    }
}

@Composable
internal fun LyricsTransportButton(
    iconRes: Int,
    contentDescription: String?,
    iconSize: Dp,
    touchSize: Dp,
    foregroundColor: Color,
    mirrored: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(touchSize),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = foregroundColor,
            modifier =
                Modifier
                    .size(iconSize)
                    .graphicsLayer { if (mirrored) scaleX = -1f },
        )
    }
}

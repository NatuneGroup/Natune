/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * The stream-quality badge — "Lossless", "Hi-Res Lossless", or the codec of a lossy stream.
 *
 * One look for every player style and the lyrics page: the Flamingo player's indicator, a small
 * smooth-cornered pill with the lossless glyph (or an EQ glyph for lossy streams) and the label at
 * low emphasis. Each style passes its own content colour, so the pill reads on light and dark
 * players alike; on light-on-dark it keeps Flamingo's additive glow.
 */

package app.natune.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.natune.R
import app.natune.db.entities.FormatEntity
import app.natune.ui.player.flamingo.FlamingoSmoothCornerShape
import app.natune.ui.player.flamingo.overlayEffect

/**
 * The badge between the timestamps on the lyrics page and in the BitChord and SimpMusic players.
 * The text comes from [streamQuality], the same classification every player style reads.
 */
@Composable
fun LosslessOrStats(
    format: FormatEntity?,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.White,
    onClick: (() -> Unit)? = null,
) {
    CodecBadge(format = format, modifier = modifier, contentColor = contentColor, onClick = onClick)
}

/**
 * The codec badge every player style shows. [onClick] opens the stream details where a style
 * offers them; without it the badge is a label.
 */
@Composable
fun CodecBadge(
    format: FormatEntity?,
    modifier: Modifier = Modifier,
    contentColor: Color = LocalContentColor.current,
    onClick: (() -> Unit)? = null,
) {
    CodecBadge(remember(format) { format?.streamQuality() }, modifier, contentColor, onClick)
}

/** [CodecBadge] for a style that keeps the classified [StreamQuality] rather than the format. */
@Composable
fun CodecBadge(
    quality: StreamQuality?,
    modifier: Modifier = Modifier,
    contentColor: Color = LocalContentColor.current,
    onClick: (() -> Unit)? = null,
) {
    if (quality == null) return
    val text = quality.label()
    val lossless = quality.isLossless
    val glow = contentColor.luminance() > 0.5f
    val clickModifier =
        if (onClick == null) {
            Modifier
        } else {
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
        }
    Box(
        modifier = modifier
            .then(clickModifier)
            .then(if (glow) Modifier.overlayEffect() else Modifier)
            .background(color = contentColor.copy(alpha = 0.08f), shape = FlamingoSmoothCornerShape(5.dp))
            .height(20.dp)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(if (lossless) R.drawable.flamingo_quality_lossless else R.drawable.player_graphic_eq),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .height(if (lossless) 9.dp else 10.dp)
                    .alpha(BADGE_ALPHA),
            )
            Spacer(modifier = Modifier.width(5.dp))
            AnimatedContent(
                targetState = text,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "codec-badge",
            ) { shown ->
                Text(
                    text = shown,
                    color = contentColor,
                    modifier = Modifier.alpha(BADGE_ALPHA),
                    fontSize = 11.sp,
                    lineHeight = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val BADGE_ALPHA = 0.45f

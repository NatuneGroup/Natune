package moe.rukamori.archivetune.shared.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The left and right inset every home page's content sits at (BitChord PAGE_GUTTER). */
val HomeFeedGutter = 10.dp

/** Width of a card in the compact carousels (BitChord SHELF_CARD_WIDTH). */
val HomeShelfCardWidth = 150.dp

/** Corner radius of a compact shelf card. */
val HomeShelfCardCorner = 12.dp

/** Horizontal gap between cards in a shelf row (BitChord: 14dp). */
val HomeShelfCardSpacing = 14.dp

/** Vertical gap between the end of one shelf and the header of the next (BitChord: 26dp). */
val HomeShelfBottomSpacing = 26.dp

/** Share of the row a lead-shelf hero card takes, so the next one peeks in past it. */
private const val HomeHeroCardFraction = 0.70f

/** How wide a hero card is ever allowed to get (tablet ceiling). */
private val HomeHeroCardMaxWidth = 320.dp

/** A hero card's proportions: a touch taller than it is wide. */
const val HomeHeroCardRatio = 0.92f

/** Corner radius of a hero card. */
val HomeHeroCardCorner = 18.dp

/**
 * How wide a hero card should be in a row [available] wide. The real shelf and the skeleton that stands in for it both
 * use this answer, so they agree to the pixel and the feed does not jump when the data lands.
 */
fun homeHeroCardWidth(available: Dp): Dp = minOf(available * HomeHeroCardFraction, HomeHeroCardMaxWidth)

/**
 * The hairline border every thumbnail carries (BitChord thumbnailBorder): 1dp at 15% alpha, white on dark themes and
 * black on light ones, clipped to the artwork's shape. It keeps pale sleeves from dissolving into the background and dark
 * ones from dissolving into a dark theme.
 */
fun Modifier.homeFeedThumbnailBorder(shape: Shape): Modifier =
    composed {
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        then(
            Modifier.border(
                width = 1.dp,
                color = if (dark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.15f),
                shape = shape,
            ),
        )
    }

/**
 * BitChord-style section header: a heavy title with an optional subtitle, typography-led rather than chrome-led.
 * [thumbnail] and [leadingIcon] render inline before the title, and [onClick] makes the header a navigation target
 * without an indication of its own.
 */
@Composable
fun HomeFeedSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    thumbnail: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .let { m ->
                if (onClick != null) m.clickable(interactionSource = interactionSource, indication = null, onClick = onClick) else m
            }
            .padding(horizontal = HomeFeedGutter, vertical = 10.dp),
    ) {
        leadingIcon?.invoke()
        thumbnail?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = (-0.4).sp,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W600,
                    letterSpacing = (-0.2).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 *
 * BitChord-style presentation (SF Pro Display typography, 16dp page gutter, playing-bars indicator, recents shelf with a
 * list/grid toggle) is adapted from BitChord (https://github.com/kushagrasinghx/BitChord, GPL-3.0) as an original
 * re-implementation over ArchiveTune's data model.
 */
package moe.rukamori.archivetune.shared.ui.home.variants

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.home.HomeHeroCardCorner
import moe.rukamori.archivetune.shared.ui.home.HomeHeroCardRatio
import moe.rukamori.archivetune.shared.ui.home.HomeShelfBottomSpacing
import moe.rukamori.archivetune.shared.ui.home.HomeShelfCardCorner
import moe.rukamori.archivetune.shared.ui.home.HomeShelfCardSpacing
import moe.rukamori.archivetune.shared.ui.home.HomeShelfCardWidth
import moe.rukamori.archivetune.shared.ui.home.homeFeedThumbnailBorder
import moe.rukamori.archivetune.shared.ui.home.homeHeroCardWidth
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ContentArtwork
import moe.rukamori.archivetune.shared.ui.theme.LocalArchiveTuneFontFamily
import org.jetbrains.compose.resources.painterResource

/** The BitChord page gutter. The shared Rukamori feed uses a tighter 10dp gutter, so BitChord keeps its own 16dp. */
internal val BitChordPageGutter = 16.dp

/** The playing accent on the current card's title. */
internal val BitChordPlayingAccent = Color(0xFFFB4A62)

private val BitChordTrackColumnMaxWidth = 400.dp
private const val BitChordRecentTracksPerColumn = 4

/** A BitChord section header: a heavy title with an optional quiet subtitle. Headers do not navigate. */
@Composable
internal fun BitChordSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = BitChordPageGutter, vertical = 10.dp),
    ) {
        Text(
            text = title,
            fontSize = 22.sp,
            fontWeight = FontWeight.W700,
            fontFamily = LocalArchiveTuneFontFamily.current,
            letterSpacing = (-0.4).sp,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                fontSize = 16.sp,
                fontWeight = FontWeight.W400,
                fontFamily = LocalArchiveTuneFontFamily.current,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Three bars that bounce while the current track plays. They are scaled in the draw layer, so the row never recomposes. */
@Composable
internal fun BitChordPlayingBars(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "BitChordPlayingBars")
    val bars =
        (0..2).map { index ->
            transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation =
                            keyframes {
                                durationMillis = 520 + index * 130
                                0.38f at 0
                                1f at (durationMillis / 2)
                                0.52f at durationMillis
                            },
                        repeatMode = RepeatMode.Restart,
                    ),
                label = "bar$index",
            )
        }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.52f))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bars.forEach { bar ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(14.dp)
                    .graphicsLayer { scaleY = bar.value }
                    .background(Color.White),
            )
        }
    }
}

/**
 * Wraps the owner's long-press action with the haptic tick the baseline gave. A null [onLongClick] stays null, so the
 * gesture is not claimed.
 */
@Composable
private fun rememberBitChordLongClick(onLongClick: (() -> Unit)?): (() -> Unit)? {
    val haptic = LocalHapticFeedback.current
    return onLongClick?.let { action ->
        {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            action()
        }
    }
}

/** A hero card: artwork with a bottom gradient carrying the title and subtitle, and the playing bars when current. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BitChordHeroCard(
    item: ContentItem,
    artwork: ContentArtwork,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val longClick = rememberBitChordLongClick(onLongClick)
    Box(
        modifier = modifier
            .aspectRatio(HomeHeroCardRatio)
            .clip(RoundedCornerShape(HomeHeroCardCorner))
            .homeFeedThumbnailBorder(RoundedCornerShape(HomeHeroCardCorner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = longClick),
    ) {
        artwork(item, Modifier.fillMaxSize())
        if (isCurrent && isPlaying) {
            BitChordPlayingBars(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(6.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                    ),
                )
                .padding(start = 16.dp, end = 16.dp, top = 34.dp, bottom = 14.dp),
        ) {
            Text(
                text = item.title,
                fontSize = 20.sp,
                fontWeight = FontWeight.W700,
                fontFamily = LocalArchiveTuneFontFamily.current,
                color = if (isCurrent) BitChordPlayingAccent else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.W400,
                fontFamily = LocalArchiveTuneFontFamily.current,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * A shelf card: a 150dp square slot with the title and subtitle beneath it. The frame takes [thumbnailAspectRatio] inside
 * that slot, so a wide frame sits centred at the same row height and drops the hairline border, as the common shelf card
 * does. [thumbnailAspectRatio] must already be a finite positive ratio from [frameRatioFor].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BitChordShelfCard(
    item: ContentItem,
    artwork: ContentArtwork,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    thumbnailAspectRatio: Float = 1f,
) {
    val longClick = rememberBitChordLongClick(onLongClick)
    val shape = RoundedCornerShape(HomeShelfCardCorner)
    val isWideFrame = abs(thumbnailAspectRatio - 1f) > 0.001f
    Column(
        modifier = modifier
            .width(HomeShelfCardWidth)
            .combinedClickable(onClick = onClick, onLongClick = longClick),
    ) {
        Box(
            modifier = Modifier.size(HomeShelfCardWidth),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .aspectRatio(thumbnailAspectRatio)
                    .clip(shape)
                    .let { m -> if (isWideFrame) m else m.homeFeedThumbnailBorder(shape) }
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                artwork(item, Modifier.fillMaxSize())
                if (isCurrent && isPlaying) {
                    BitChordPlayingBars(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = item.title,
            fontSize = 16.sp,
            fontWeight = FontWeight.W600,
            fontFamily = LocalArchiveTuneFontFamily.current,
            letterSpacing = (-0.2).sp,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.subtitle,
            fontSize = 14.sp,
            fontWeight = FontWeight.W400,
            fontFamily = LocalArchiveTuneFontFamily.current,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** A compact row of the recents list view: a 48dp thumbnail, then the title and subtitle. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BitChordCompactTrackRow(
    item: ContentItem,
    artwork: ContentArtwork,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val longClick = rememberBitChordLongClick(onLongClick)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = longClick)
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(7.dp))
                .homeFeedThumbnailBorder(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            artwork(item, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.W600,
                fontFamily = LocalArchiveTuneFontFamily.current,
                letterSpacing = (-0.2).sp,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.W400,
                fontFamily = LocalArchiveTuneFontFamily.current,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/**
 * The recents shelf with its list/grid toggle. The list view stacks up to four rows per column, and the grid view is a
 * rail of hero cards. [onPlay] receives the tapped occurrence and its index in the distinct list, and [onMenu] opens its
 * sheet on long press. [availableWidth] is the page width the column and hero widths are derived from.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BitChordRecentsShelf(
    title: String,
    items: List<ContentOccurrence>,
    nowPlaying: HomeVariantNowPlaying,
    artwork: ContentArtwork,
    availableWidth: Dp,
    onPlay: (ContentOccurrence, Int) -> Unit,
    onMenu: (ContentOccurrence) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A Boolean, not an enum, so the toggle survives process death on every platform without a custom saver.
    var gridView by rememberSaveable { mutableStateOf(false) }
    val distinctItems = remember(items) { items.distinctBy { it.item.ref } }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BitChordPageGutter, vertical = 10.dp),
        ) {
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.W700,
                fontFamily = LocalArchiveTuneFontFamily.current,
                letterSpacing = (-0.4).sp,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { gridView = !gridView }) {
                Icon(
                    painter =
                        painterResource(
                            if (gridView) Res.drawable.m4hv_icon_list_view else Res.drawable.m4hv_icon_grid_view,
                        ),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (gridView) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = BitChordPageGutter),
                horizontalArrangement = Arrangement.spacedBy(HomeShelfCardSpacing),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(
                    distinctItems,
                    key = { index, occurrence -> "recent_${occurrence.occurrenceId}_$index" },
                ) { index, occurrence ->
                    BitChordHeroCard(
                        item = occurrence.item,
                        artwork = artwork,
                        isCurrent = nowPlaying.isActive(occurrence.item.ref),
                        isPlaying = nowPlaying.isPlaying,
                        onClick = { onPlay(occurrence, index) },
                        onLongClick = { onMenu(occurrence) },
                        modifier = Modifier.width(homeHeroCardWidth(availableWidth)),
                    )
                }
            }
        } else {
            val columns = remember(distinctItems) { distinctItems.chunked(BitChordRecentTracksPerColumn) }
            LazyRow(
                contentPadding = PaddingValues(horizontal = BitChordPageGutter),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
                userScrollEnabled = columns.size > 1,
            ) {
                itemsIndexed(columns, key = { columnIndex, _ -> "recent_col_$columnIndex" }) { columnIndex, column ->
                    Column(
                        modifier = Modifier.width(
                            (availableWidth * 0.88f).coerceAtMost(BitChordTrackColumnMaxWidth) - BitChordPageGutter,
                        ),
                    ) {
                        column.forEachIndexed { rowIndexInColumn, occurrence ->
                            val globalIndex = columnIndex * BitChordRecentTracksPerColumn + rowIndexInColumn
                            BitChordCompactTrackRow(
                                item = occurrence.item,
                                artwork = artwork,
                                isCurrent = nowPlaying.isActive(occurrence.item.ref),
                                onClick = { onPlay(occurrence, globalIndex) },
                                onLongClick = { onMenu(occurrence) },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(HomeShelfBottomSpacing))
    }
}

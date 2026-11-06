package moe.rukamori.archivetune.shared.ui.home.variants

import moe.rukamori.archivetune.shared.ui.songmetadata.effectiveSong

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselItemScope
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.home.HomeFeedGutter
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ContentArtwork
import org.jetbrains.compose.resources.painterResource

/** Height of one list row in the quick-picks grid, the baseline ListItemHeight. */
private val QuickPickListItemHeight = 72.dp

/**
 * The quick picks in the layout the owner chose. CARD is a rail of hero cards and LIST is a four-row grid that scrolls
 * sideways. [onPlay] reports the tap and the owner decides the queue (a tap on the track that is already playing toggles
 * play and pause). [onMenu] opens the item's sheet from the list's more button or a long press on a card.
 */
@Composable
internal fun NatuneClassicHomeQuickPicks(
    items: List<ContentOccurrence>,
    layout: HomeVariantQuickPickLayout,
    nowPlaying: HomeVariantNowPlaying,
    artwork: ContentArtwork,
    onPlay: (ContentOccurrence) -> Unit,
    onMenu: (ContentOccurrence) -> Unit,
    modifier: Modifier = Modifier,
) {
    val distinct = remember(items) { items.distinctBy { it.item.ref } }
    when (layout) {
        HomeVariantQuickPickLayout.CARD ->
            NatuneClassicHomeQuickPickRail(
                items = distinct,
                nowPlaying = nowPlaying,
                artwork = artwork,
                onPlay = onPlay,
                onMenu = onMenu,
                modifier = modifier,
            )

        HomeVariantQuickPickLayout.LIST ->
            NatuneClassicHomeQuickPickGrid(
                items = distinct,
                nowPlaying = nowPlaying,
                artwork = artwork,
                onPlay = onPlay,
                onMenu = onMenu,
                modifier = modifier,
            )
    }
}

/**
 * The hero rail, the baseline's centred hero carousel. The sizes are the baseline's: the card is the screen width less
 * 48dp, clamped to 232-440dp, and the rail is 332, 356 or 380dp tall at the 600dp and 840dp breakpoints, with 10dp
 * between cards and a 16dp inset. The carousel supplies the centring, the snap and each card's morphing mask.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NatuneClassicHomeQuickPickRail(
    items: List<ContentOccurrence>,
    nowPlaying: HomeVariantNowPlaying,
    artwork: ContentArtwork,
    onPlay: (ContentOccurrence) -> Unit,
    onMenu: (ContentOccurrence) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val heroMaxWidth = (maxWidth - 48.dp).coerceAtLeast(232.dp).coerceAtMost(440.dp)
        val heroHeight =
            when {
                maxWidth >= 840.dp -> 380.dp
                maxWidth >= 600.dp -> 356.dp
                else -> 332.dp
            }
        HorizontalCenteredHeroCarousel(
            state = rememberCarouselState { items.size },
            maxItemWidth = heroMaxWidth,
            itemSpacing = 10.dp,
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.fillMaxWidth().height(heroHeight),
        ) { index ->
            val occurrence = items[index]
            NatuneClassicHomeQuickPickHero(
                occurrence = occurrence,
                artwork = artwork,
                active = nowPlaying.isActive(occurrence.item.ref),
                isPlaying = nowPlaying.isPlaying,
                onClick = { onPlay(occurrence) },
                onLongClick =
                    if (occurrence.item.ref.kind.hasHomeMenu()) {
                        { onMenu(occurrence) }
                    } else {
                        null
                    },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * One quick pick as a hero card: artwork, a bottom scrim, the playing badge while this track is audible, then the text.
 * It extends the carousel item scope because the mask modifiers that clip the card to its morphing shape exist only there.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CarouselItemScope.NatuneClassicHomeQuickPickHero(
    occurrence: ContentOccurrence,
    artwork: ContentArtwork,
    active: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier,
) {
    val item = effectiveSong(occurrence.item)
    val shape = MaterialTheme.shapes.extraLarge
    Box(
        modifier =
            modifier
                .maskClip(shape)
                .maskBorder(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)), shape)
                .focusable()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        artwork(item, Modifier.fillMaxSize())
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.48f to Color.Black.copy(alpha = 0.08f),
                            1f to Color.Black.copy(alpha = 0.84f),
                        ),
                    ),
        )
        if (active && isPlaying) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                tonalElevation = 2.dp,
                modifier = Modifier.align(Alignment.TopEnd).padding(14.dp).size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(Res.drawable.m4hv_icon_volume_up),
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The list layout: four rows that scroll sideways. Columns are nearly half the width on wide screens and nearly all of
 * it on narrow ones, as the baseline sizes them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NatuneClassicHomeQuickPickGrid(
    items: List<ContentOccurrence>,
    nowPlaying: HomeVariantNowPlaying,
    artwork: ContentArtwork,
    onPlay: (ContentOccurrence) -> Unit,
    onMenu: (ContentOccurrence) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columnWidth = if (maxWidth < 600.dp) maxWidth * 0.9f else maxWidth * 0.475f
        LazyHorizontalGrid(
            rows = GridCells.Fixed(4),
            contentPadding = PaddingValues(horizontal = HomeFeedGutter),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().height(QuickPickListItemHeight * 4),
        ) {
            gridItems(
                items = items,
                key = { occurrence -> occurrence.occurrenceId },
                contentType = { "quick_pick_row" },
            ) { occurrence ->
                NatuneClassicHomeQuickPickRow(
                    occurrence = occurrence,
                    active = nowPlaying.isActive(occurrence.item.ref),
                    isPlaying = nowPlaying.isPlaying,
                    artwork = artwork,
                    onClick = { onPlay(occurrence) },
                    onMenu = { onMenu(occurrence) },
                    modifier = Modifier.width(columnWidth),
                )
            }
        }
    }
}

/**
 * One quick pick as a list row: artwork at the baseline 56dp, the title and artists, the playing mark while audible,
 * and the more button that opens the sheet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NatuneClassicHomeQuickPickRow(
    occurrence: ContentOccurrence,
    active: Boolean,
    isPlaying: Boolean,
    artwork: ContentArtwork,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier,
) {
    val item = effectiveSong(occurrence.item)
    ListItem(
        headlineContent = {
            Text(
                text = item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        },
        modifier = modifier.combinedClickable(onClick = onClick),
        supportingContent = {
            Text(text = item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            artwork(
                item,
                Modifier
                    .size(HomeVariantThumbnailSize)
                    .clip(RoundedCornerShape(HomeVariantThumbnailCorner)),
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active && isPlaying) {
                    Icon(
                        painter = painterResource(Res.drawable.m4_icon_listening),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (item.ref.kind.hasHomeMenu()) {
                    IconButton(onClick = onMenu) {
                        Icon(
                            painter = painterResource(Res.drawable.m4hv_icon_more_vert),
                            contentDescription = null,
                        )
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

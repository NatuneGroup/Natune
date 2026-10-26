/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.home.variants

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.random.Random
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ContentArtwork
import org.jetbrains.compose.resources.painterResource

/** The baseline speed dial: at most 24 pinned items, laid out 3 by 3 per page. */
private const val SpeedDialMaxItems = 24
private const val SpeedDialColumns = 3
private const val SpeedDialRows = 3
private const val SpeedDialPageSize = SpeedDialColumns * SpeedDialRows
private val SpeedDialGap = 10.dp

/** One grid slot: a pinned item, or the random tile when [occurrence] is null. */
private class SpeedDialSlot(val key: String, val occurrence: ContentOccurrence?)

/**
 * The speed dial. [items] is the owner's pin order; the variant keeps the first distinct 24 and appends a random tile.
 * Songs form one finite queue in pin order: a tap starts it at the tapped song, and the owner toggles play and pause
 * when that song is already playing. Albums, artists and playlists lead to their pages with [RukamoriHomeActions.open].
 * A long press opens the item's sheet. The random tile starts the same song queue at a random position without
 * toggling, and does nothing when the dial holds no songs, as the baseline does.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RukamoriSpeedDial(
    items: List<ContentOccurrence>,
    nowPlaying: HomeVariantNowPlaying,
    artwork: ContentArtwork,
    shelfTitle: String,
    actions: RukamoriHomeActions,
    modifier: Modifier = Modifier,
) {
    val pinned = remember(items) { items.distinctBy { it.item.ref }.take(SpeedDialMaxItems) }
    val songs = remember(pinned) { pinned.filter { it.item.ref.kind == ContentKind.SONG } }
    val songIndexByRef =
        remember(songs) {
            songs.withIndex().associate { (index, occurrence) -> occurrence.item.ref to index }
        }
    val slots =
        remember(pinned) {
            buildList {
                pinned.forEach { occurrence -> add(SpeedDialSlot(key = occurrence.occurrenceId, occurrence = occurrence)) }
                add(SpeedDialSlot(key = "speed_dial_random", occurrence = null))
            }
        }
    val pages = remember(slots) { slots.chunked(SpeedDialPageSize) }
    // A single page is only as tall as its rows, so a short dial does not leave an empty band; several pages keep the
    // full three rows so every page lines up.
    val visibleRows =
        remember(pages) {
            if (pages.size == 1) {
                ((pages.first().size + SpeedDialColumns - 1) / SpeedDialColumns).coerceIn(1, SpeedDialRows)
            } else {
                SpeedDialRows
            }
        }
    val pagerState = rememberPagerState(pageCount = { pages.size })

    LaunchedEffect(pages.size) {
        if (pagerState.currentPage > pages.lastIndex) {
            pagerState.scrollToPage(pages.lastIndex.coerceAtLeast(0))
        }
    }

    val selectedDot by
        remember(pagerState, pages) {
            derivedStateOf {
                (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                    .roundToInt()
                    .coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            }
        }
    val motionScheme = MaterialTheme.motionScheme

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        modifier = modifier.padding(horizontal = 24.dp).fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val tileSize = (maxWidth - SpeedDialGap * (SpeedDialColumns - 1)) / SpeedDialColumns
                val gridHeight = tileSize * visibleRows + SpeedDialGap * (visibleRows - 1)
                HorizontalPager(
                    state = pagerState,
                    pageSize = PageSize.Fill,
                    pageSpacing = SpeedDialGap,
                    key = { page -> pages.getOrNull(page)?.firstOrNull()?.key ?: "stale_speed_dial_page_$page" },
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth().height(gridHeight),
                ) { page ->
                    val pageSlots = pages.getOrNull(page)
                    if (pageSlots == null) {
                        // The pins can change while the pager is on a page that no longer exists. Draw nothing for
                        // that frame rather than read past the end of the grid.
                        Box(modifier = Modifier.fillMaxSize())
                        return@HorizontalPager
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(SpeedDialGap),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        pageSlots.chunked(SpeedDialColumns).forEach { rowSlots ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(SpeedDialGap),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                rowSlots.forEach { slot ->
                                    val occurrence = slot.occurrence
                                    if (occurrence == null) {
                                        RukamoriSpeedDialRandomTile(
                                            onClick = {
                                                if (songs.isNotEmpty()) {
                                                    actions.play(
                                                        HomeVariantPlay.Shelf(
                                                            title = shelfTitle,
                                                            occurrences = songs,
                                                            startIndex = Random.nextInt(songs.size),
                                                            toggleIfActive = false,
                                                        ),
                                                    )
                                                }
                                            },
                                            modifier = Modifier.size(tileSize),
                                        )
                                    } else {
                                        RukamoriSpeedDialTile(
                                            occurrence = occurrence,
                                            artwork = artwork,
                                            isActive = nowPlaying.isActive(occurrence.item.ref),
                                            isPlaying = nowPlaying.isPlaying,
                                            onClick = {
                                                val ref = occurrence.item.ref
                                                if (ref.kind == ContentKind.SONG) {
                                                    val start = songIndexByRef[ref]
                                                    if (start != null) {
                                                        actions.play(
                                                            HomeVariantPlay.Shelf(
                                                                title = shelfTitle,
                                                                occurrences = songs,
                                                                startIndex = start,
                                                                toggleIfActive = true,
                                                            ),
                                                        )
                                                    }
                                                } else {
                                                    actions.open(ref)
                                                }
                                            },
                                            onLongClick = { actions.menu(occurrence) },
                                            modifier = Modifier.size(tileSize),
                                        )
                                    }
                                }
                                repeat(SpeedDialColumns - rowSlots.size) {
                                    Spacer(Modifier.size(tileSize))
                                }
                            }
                        }
                    }
                }
            }

            if (pages.size > 1) {
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    repeat(pages.size) { index ->
                        val isSelected = index == selectedDot
                        val dotColor by
                            animateColorAsState(
                                targetValue =
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                animationSpec = motionScheme.defaultEffectsSpec(),
                                label = "speedDialDotColor",
                            )
                        val dotWidth by
                            animateDpAsState(
                                targetValue = if (isSelected) 22.dp else 8.dp,
                                animationSpec = motionScheme.defaultSpatialSpec(),
                                label = "speedDialDotWidth",
                            )
                        Surface(
                            color = dotColor,
                            shape = MaterialTheme.shapes.extraLarge,
                            modifier = Modifier.width(dotWidth).height(8.dp),
                        ) {}
                    }
                }
            }
        }
    }
}

/**
 * One pinned item as a square tile: artwork, the bottom label scrim with the title, the navigate mark for anything that is
 * not a song, and the pinned bookmark. The active tile grows its border and lifts, and scales slightly while audible.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RukamoriSpeedDialTile(
    occurrence: ContentOccurrence,
    artwork: ContentArtwork,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    val motionScheme = MaterialTheme.motionScheme
    val containerColor by
        animateColorAsState(
            targetValue =
                if (isActive) {
                    MaterialTheme.colorScheme.inverseOnSurface
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            animationSpec = motionScheme.defaultEffectsSpec(),
            label = "speedDialContainerColor",
        )
    val borderWidth by
        animateDpAsState(
            targetValue = if (isActive) 3.dp else 0.dp,
            animationSpec = motionScheme.defaultSpatialSpec(),
            label = "speedDialBorderWidth",
        )
    val playingScale by
        animateFloatAsState(
            targetValue = if (isPlaying && isActive) 0.96f else 1f,
            animationSpec = motionScheme.fastSpatialSpec(),
            label = "speedDialPlayingScale",
        )
    val shape = if (isActive) MaterialTheme.shapes.extraLarge else MaterialTheme.shapes.large
    val foreground = Color.White
    val labelGradient =
        Brush.verticalGradient(
            listOf(
                Color.Transparent,
                Color.Black.copy(alpha = 0.34f),
                Color.Black.copy(alpha = 0.78f),
            ),
        )

    Box(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.large)
                .focusable()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Surface(
            color = containerColor,
            contentColor = foreground,
            shape = shape,
            tonalElevation = if (isActive) 8.dp else 2.dp,
            border = BorderStroke(borderWidth, MaterialTheme.colorScheme.inverseOnSurface),
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).scale(playingScale),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                artwork(occurrence.item, Modifier.fillMaxSize())
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(86.dp)
                            .background(labelGradient),
                )
                Row(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = occurrence.item.title,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (occurrence.item.ref.kind != ContentKind.SONG) {
                        Icon(
                            painter = painterResource(Res.drawable.m4hv_icon_navigate_next),
                            contentDescription = null,
                            tint = foreground,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Icon(
                    painter = painterResource(Res.drawable.m4hv_icon_bookmark_filled),
                    contentDescription = null,
                    tint = foreground,
                    modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(24.dp),
                )
            }
        }
    }
}

/** The random tile, drawn as the baseline: three primary dots on a raised surface. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RukamoriSpeedDialRandomTile(
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        modifier = modifier.aspectRatio(1f).combinedClickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                repeat(3) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                        modifier = Modifier.size(18.dp),
                    ) {}
                }
            }
        }
    }
}

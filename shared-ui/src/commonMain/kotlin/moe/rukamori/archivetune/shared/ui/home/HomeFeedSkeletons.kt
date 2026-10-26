package moe.rukamori.archivetune.shared.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.glass.motionAllowed

// Grey stand-ins for home content still on the wire, laid out to the same metrics as the real rows and cards so
// nothing jumps when the data lands.

/** How long one highlight sweep takes to cross a placeholder. */
private const val HomeShimmerPeriodMs = 1400

private val HomeBlockShape = RoundedCornerShape(6.dp)
private val HomeLineShape = RoundedCornerShape(4.dp)

// Ragged widths, so a run of rows reads as text rather than as a barcode.
private val HomeTitleWidths = listOf(0.68f, 0.46f, 0.58f, 0.74f, 0.52f)
private val HomeSubtitleWidths = listOf(0.34f, 0.44f, 0.27f, 0.38f, 0.31f)

/**
 * One placeholder block, with a highlight sweeping across it. The sweep is read inside the draw cache rather than the
 * composable body: a screenful of these would otherwise recompose on every animation frame, and all any of them needs
 * per frame is a fresh gradient.
 */
@Composable
fun HomeShimmerBox(modifier: Modifier = Modifier, shape: Shape = HomeBlockShape) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight =
        MaterialTheme.colorScheme.onSurfaceVariant
            .copy(alpha = 0.16f)
            .compositeOver(base)
    // With motion off the sweep holds at its start, which parks the band beyond the left edge: the block reads as flat
    // grey, and no infinite transition keeps a frame clock running for it.
    val sweep: State<Float> =
        if (motionAllowed()) {
            rememberInfiniteTransition(label = "home_skeleton").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(HomeShimmerPeriodMs, easing = LinearEasing)),
                label = "home_sweep",
            )
        } else {
            remember { mutableStateOf(0f) }
        }
    Box(
        modifier
            .clip(shape)
            .drawWithCache {
                // The band travels from fully off one edge to fully off the other, which leaves a beat of flat grey
                // between passes rather than a highlight permanently parked somewhere on the block.
                val band = size.width * 0.5f
                val startX = -band + sweep.value * (size.width + band * 2)
                val brush =
                    Brush.horizontalGradient(
                        colors = listOf(base, highlight, base),
                        startX = startX,
                        endX = startX + band,
                    )
                onDrawBehind { drawRect(brush) }
            },
    )
}

/** A placeholder for one line of text, sized as a fraction of its parent. */
@Composable
private fun HomeSkeletonLine(fraction: Float, height: Dp, modifier: Modifier = Modifier) {
    HomeShimmerBox(
        modifier = modifier.fillMaxWidth(fraction).height(height),
        shape = HomeLineShape,
    )
}

/** Stands in for a section heading. */
@Composable
private fun HomeSectionHeaderSkeleton(index: Int = 0) {
    Column(Modifier.padding(horizontal = HomeFeedGutter, vertical = 10.dp)) {
        HomeSkeletonLine(fraction = HomeTitleWidths[index % HomeTitleWidths.size] * 0.7f, height = 18.dp)
    }
}

/**
 * The lead shelf skeleton: near-page-width cards. Built on a [LazyRow] with scrolling off rather than a plain Row, so a
 * card running past the right edge is measured and clipped exactly as the real shelf's is.
 */
@Composable
private fun HomeHeroShelfSkeleton() {
    Column(Modifier.padding(bottom = HomeShelfBottomSpacing)) {
        HomeSectionHeaderSkeleton()
        BoxWithConstraints {
            val cardWidth = homeHeroCardWidth(maxWidth)
            LazyRow(
                contentPadding = PaddingValues(horizontal = HomeFeedGutter),
                horizontalArrangement = Arrangement.spacedBy(HomeShelfCardSpacing),
                userScrollEnabled = false,
            ) {
                items(2) {
                    HomeShimmerBox(
                        modifier =
                            Modifier
                                .width(cardWidth)
                                .aspectRatio(HomeHeroCardRatio),
                        shape = RoundedCornerShape(HomeHeroCardCorner),
                    )
                }
            }
        }
    }
}

/** The compact carousel of square cards used by every shelf below the first. */
@Composable
fun HomeShelfSkeleton(index: Int = 0) {
    Column(Modifier.padding(bottom = HomeShelfBottomSpacing)) {
        HomeSectionHeaderSkeleton(index = index)
        LazyRow(
            contentPadding = PaddingValues(horizontal = HomeFeedGutter),
            horizontalArrangement = Arrangement.spacedBy(HomeShelfCardSpacing),
            userScrollEnabled = false,
        ) {
            items(3) { card ->
                Column(Modifier.width(HomeShelfCardWidth)) {
                    HomeShimmerBox(
                        modifier =
                            Modifier
                                .width(HomeShelfCardWidth)
                                .aspectRatio(1f),
                        shape = RoundedCornerShape(HomeShelfCardCorner),
                    )
                    Spacer(Modifier.height(10.dp))
                    HomeSkeletonLine(
                        fraction = HomeTitleWidths[card % HomeTitleWidths.size],
                        height = 13.dp,
                    )
                    Spacer(Modifier.height(6.dp))
                    HomeSkeletonLine(
                        fraction = HomeSubtitleWidths[card % HomeSubtitleWidths.size],
                        height = 11.dp,
                    )
                }
            }
        }
    }
}

/** Home while the first page of shelves is still loading. */
fun LazyListScope.homeFeedSkeleton(shelves: Int = 3) {
    item(key = "home_skeleton_hero") { HomeHeroShelfSkeleton() }
    items(shelves - 1, key = { "home_skeleton_shelf_$it" }) { index ->
        HomeShelfSkeleton(index = index + 1)
    }
}

/** Appended to the feed while a further page of shelves is on its way. */
fun LazyListScope.homeFeedMoreSkeleton() {
    item(key = "home_skeleton_more") { HomeShelfSkeleton() }
}

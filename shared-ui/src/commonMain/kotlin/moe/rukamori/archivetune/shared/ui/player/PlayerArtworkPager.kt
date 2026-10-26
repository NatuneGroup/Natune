/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest

@Stable
internal class PlayerArtworkPagerState(val pager: PagerState) {
    var programmatic by mutableStateOf(false)
    var dragged by mutableStateOf(false)
    var cancelledDragGeneration by mutableIntStateOf(0)
}

/** One synchronizer above look/orientation branches; only genuine user settles select an occurrence. */
@Composable
internal fun rememberPlayerArtworkPager(state: PlayerState,actions: PlayerActions): PlayerArtworkPagerState {
    val ids=state.occurrenceIds
    val current=state.playback?.occurrenceId
    val pager=rememberPagerState(initialPage=ids.indexOf(current).coerceAtLeast(0),pageCount={ ids.size })
    val sync=remember(pager) { PlayerArtworkPagerState(pager) }
    val latestState by rememberUpdatedState(state)
    val latestActions by rememberUpdatedState(actions)
    LaunchedEffect(pager) {
        pager.interactionSource.interactions.collect { event ->
            if(event is DragInteraction.Start && !sync.programmatic) sync.dragged=true
            if(event is DragInteraction.Cancel) {
                sync.dragged=false
                sync.cancelledDragGeneration++
            }
        }
    }
    // Cancellation can unblock a follow whose occurrence keys already changed while dragging.
    LaunchedEffect(current,ids,sync.cancelledDragGeneration) {
        val target=ids.indexOf(current)
        if(target>=0 && target!=pager.currentPage && !sync.dragged) {
            sync.programmatic=true
            try { pager.scrollToPage(target) } finally { sync.programmatic=false }
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { Triple(pager.isScrollInProgress,pager.settledPage,sync.programmatic) }
            .collectLatest { (scrolling,page,programmatic) ->
                if(!scrolling && !programmatic && sync.dragged) {
                    val target=settledArtworkOccurrence(latestState.occurrenceIds,latestState.playback?.occurrenceId,
                        page,programmatic,sync.dragged)
                    sync.dragged=false
                    if(target!=null) latestActions.selectOccurrence(target)
                }
            }
    }
    return sync
}

@Composable
internal fun PlayerArtworkPager(c: PlayerRenderContext,modifier: Modifier=Modifier,vertical: Boolean=false) {
    val ids=c.state.occurrenceIds
    if(ids.isEmpty()) {
        c.slots.artwork(c.state.playback?.occurrenceId,modifier.aspectRatio(1f))
        return
    }
    val enabled=c.preferences.swipeArtwork && c.state.repeat!=PlayerRepeat.ONE && ids.size>1
    val hidden=!vertical && c.preferences.style.capabilities.ordinarySleeve && c.preferences.hideSleeve
    val frame=if(vertical) modifier.fillMaxSize() else modifier.aspectRatio(1f)
        .clip(RoundedCornerShape(c.preferences.sleeveRadius.dp))
        .graphicsLayer { alpha=if(hidden) 0f else 1f }
    val page: @Composable PagerScope.(Int) -> Unit = { index ->
        val id=ids.getOrNull(index)
        Box(Modifier.fillMaxSize()) {
            if(c.state.canvasAvailable && c.preferences.style.capabilities.artworkCanvas && id==c.state.playback?.occurrenceId) {
                c.slots.canvas(id,c.surfaces.artwork && !hidden,Modifier.fillMaxSize())
            } else c.slots.artwork(id,Modifier.fillMaxSize())
        }
    }
    if(vertical) VerticalPager(c.artworkPager.pager,modifier=frame.testTag("vertical-artwork-pager"),
        userScrollEnabled=enabled,key={ ids[it] },pageContent=page)
    else HorizontalPager(c.artworkPager.pager,modifier=frame.testTag("horizontal-artwork-pager"),
        userScrollEnabled=enabled,key={ ids[it] },pageContent=page)
}

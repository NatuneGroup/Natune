package moe.rukamori.archivetune.ui.player.spatialflow

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.ui.player.LocalPlayerPresentation
import moe.rukamori.archivetune.ui.player.PlayerCoverImage
import moe.rukamori.archivetune.ui.player.ordinarySleeve
import moe.rukamori.archivetune.ui.player.playerSeekDoubleTap

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SpatialFlowArtworkPager(
    mediaMetadata: MediaMetadata,
    queueWindows: List<androidx.media3.common.Timeline.Window>,
    currentWindowIndex: Int,
    userScrollEnabled: Boolean,
    artUrl: String?,
    cornerRadius: androidx.compose.ui.unit.Dp,
    shadowElevation: androidx.compose.ui.unit.Dp,
    onPlaySongAtWindow: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val animationsDisabled = moe.rukamori.archivetune.LocalAnimationsDisabled.current
    val pagerState =
        rememberPagerState(initialPage = currentWindowIndex.coerceAtLeast(0)) {
            queueWindows.size.coerceAtLeast(1)
        }

    LaunchedEffect(currentWindowIndex) {
        if (currentWindowIndex >= 0 &&
            currentWindowIndex < pagerState.pageCount &&
            pagerState.currentPage != currentWindowIndex
        ) {
            if (animationsDisabled) pagerState.scrollToPage(currentWindowIndex)
            else pagerState.animateScrollToPage(currentWindowIndex)
        }
    }

    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress &&
            currentWindowIndex >= 0 &&
            pagerState.currentPage != currentWindowIndex
        ) {
            onPlaySongAtWindow(pagerState.currentPage)
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = userScrollEnabled,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val pageMetadata =
                if (page == currentWindowIndex) {
                    mediaMetadata
                } else {
                    queueWindows.getOrNull(page)?.mediaItem?.metadata ?: mediaMetadata
                }
            val pageArtUrl = if (page == currentWindowIndex) artUrl else pageMetadata.thumbnailUrl

            var isError by remember(pageArtUrl) { mutableStateOf(false) }

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .ordinarySleeve(LocalPlayerPresentation.current.hideSleeve)
                        .shadow(shadowElevation, RoundedCornerShape(cornerRadius))
                        .clip(RoundedCornerShape(cornerRadius)),
                contentAlignment = Alignment.Center,
            ) {
                if (!pageArtUrl.isNullOrBlank() && !isError) {
                    PlayerCoverImage(
                        imageUrl = pageArtUrl,
                        contentScale = ContentScale.Crop,
                        onError = { isError = true },
                        modifier = Modifier.fillMaxSize().playerSeekDoubleTap(),
                    )
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        colors =
                                            listOf(
                                                MaterialTheme.colorScheme.surfaceVariant,
                                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                            ),
                                    ),
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.spatialflow_ic_music_note),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(96.dp),
                        )
                    }
                }
            }
        }
    }
}

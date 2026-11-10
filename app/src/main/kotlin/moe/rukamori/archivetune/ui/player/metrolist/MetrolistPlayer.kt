package moe.rukamori.archivetune.ui.player.metrolist

import moe.rukamori.archivetune.ui.player.rememberPlayerControlMotion
import moe.rukamori.archivetune.ui.player.PlayerControlKind
import moe.rukamori.archivetune.ui.player.PlayerLoadingCrossfade
import moe.rukamori.archivetune.ui.player.PlayerTransportIcon
import moe.rukamori.archivetune.ui.player.playerPressFeedback
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.HidePlayerThumbnailKey
import moe.rukamori.archivetune.constants.MetrolistPresentationKey
import moe.rukamori.archivetune.constants.SliderStyle
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.extensions.toggleRepeatMode
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.shared.ui.player.MetrolistPresentation
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.LocalBottomSheetPageState
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.MenuState
import moe.rukamori.archivetune.ui.menu.PlayerMenu
import moe.rukamori.archivetune.ui.player.ClickableArtists
import moe.rukamori.archivetune.ui.player.PlayerCoverImage
import moe.rukamori.archivetune.ui.player.PlayerSlider
import moe.rukamori.archivetune.ui.player.PlayerTimeLabel
import moe.rukamori.archivetune.ui.player.PlayerTitleText
import moe.rukamori.archivetune.ui.player.rememberPlayerTitleActions
import moe.rukamori.archivetune.ui.utils.ShowMediaInfo
import moe.rukamori.archivetune.ui.utils.highRes
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference

// The reference look keeps one gutter for artwork, text and transport; the seek pair insets
// itself (PlayerSlider / PlayerTimeLabel pad by PlayerHorizontalPadding already).
private val Gutter = 24.dp
private val ContentGutter = 32.dp

/**
 * The Metrolist player surface (MetrolistGroup/Metrolist, GPL-3.0) — collapse/like header with the
 * presentation label, a square sleeve, title and artists, a thin seek bar, native prev/play/next
 * and the six-button utility row (lyrics, shuffle, queue, repeat, sleep, more). Modern shows a
 * rounded sleeve and squircle play button; Vintage frames the sleeve and centres the text.
 */
@Composable
fun MetrolistPlayerContent(
    mediaMetadata: MediaMetadata,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    sliderPosition: Long?,
    positionProvider: () -> Long,
    duration: Long,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
    currentSongLiked: Boolean,
    sleepTimerEnabled: Boolean,
    repeatMode: Int,
    textButtonColor: Color,
    iconButtonColor: Color,
    textBackgroundColor: Color,
    contentBottomPadding: Dp,
    onSleepTimerClick: () -> Unit,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    landscape: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val presentation by rememberEnumPreference(MetrolistPresentationKey, MetrolistPresentation.MODERN)
    val vintage = presentation == MetrolistPresentation.VINTAGE
    val hidePlayerThumbnail by rememberPreference(HidePlayerThumbnailKey, false)
    val artworkUrl = mediaMetadata.thumbnailUrl?.highRes()
    val titleActions = rememberPlayerTitleActions(mediaMetadata, navController, state)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = contentBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { state.collapseSoft() }) {
                Icon(
                    painter = painterResource(R.drawable.expand_more),
                    contentDescription = stringResource(R.string.collapse),
                    tint = textBackgroundColor,
                )
            }
            Text(
                text =
                    stringResource(
                        if (vintage) {
                            R.string.metrolist_presentation_vintage
                        } else {
                            R.string.metrolist_presentation_modern
                        },
                    ),
                style = MaterialTheme.typography.labelLarge,
                color = textBackgroundColor,
            )
            IconButton(onClick = { playerConnection.toggleLike() }) {
                Icon(
                    painter =
                        painterResource(
                            if (currentSongLiked) {
                                R.drawable.favorite
                            } else {
                                R.drawable.favorite_border
                            },
                        ),
                    contentDescription = null,
                    tint = textBackgroundColor,
                )
            }
        }

        if (landscape) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    MetrolistArtwork(
                        artworkUrl = artworkUrl,
                        vintage = vintage,
                        hidden = hidePlayerThumbnail,
                    )
                    MetrolistMetadata(
                        mediaMetadata = mediaMetadata,
                        textBackgroundColor = textBackgroundColor,
                        centered = vintage,
                        onTitleClick = titleActions.onTitleClick,
                        onCopyTitle = titleActions.onCopyTitle,
                        onArtistClick = titleActions.onArtistClick,
                        onCopyArtists = titleActions.onCopyArtists,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    MetrolistSeek(
                        sliderPosition = sliderPosition,
                        positionProvider = positionProvider,
                        duration = duration,
                        isPlaying = isPlaying,
                        textButtonColor = textButtonColor,
                        textBackgroundColor = textBackgroundColor,
                        onSliderValueChange = onSliderValueChange,
                        onSliderValueChangeFinished = onSliderValueChangeFinished,
                    )
                    MetrolistTransport(
                        playbackState = playbackState,
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        canSkipPrevious = canSkipPrevious,
                        canSkipNext = canSkipNext,
                        vintage = vintage,
                        textButtonColor = textButtonColor,
                        iconButtonColor = iconButtonColor,
                        textBackgroundColor = textBackgroundColor,
                        playerConnection = playerConnection,
                    )
                }
            }
        } else {
            MetrolistArtwork(
                artworkUrl = artworkUrl,
                vintage = vintage,
                hidden = hidePlayerThumbnail,
            )
            MetrolistMetadata(
                mediaMetadata = mediaMetadata,
                textBackgroundColor = textBackgroundColor,
                centered = vintage,
                onTitleClick = titleActions.onTitleClick,
                onCopyTitle = titleActions.onCopyTitle,
                onArtistClick = titleActions.onArtistClick,
                onCopyArtists = titleActions.onCopyArtists,
            )
            MetrolistSeek(
                sliderPosition = sliderPosition,
                positionProvider = positionProvider,
                duration = duration,
                isPlaying = isPlaying,
                textButtonColor = textButtonColor,
                textBackgroundColor = textBackgroundColor,
                onSliderValueChange = onSliderValueChange,
                onSliderValueChangeFinished = onSliderValueChangeFinished,
            )
            MetrolistTransport(
                playbackState = playbackState,
                isPlaying = isPlaying,
                isLoading = isLoading,
                canSkipPrevious = canSkipPrevious,
                canSkipNext = canSkipNext,
                vintage = vintage,
                textButtonColor = textButtonColor,
                iconButtonColor = iconButtonColor,
                textBackgroundColor = textBackgroundColor,
                playerConnection = playerConnection,
            )
        }

        MetrolistBottomControls(
            mediaMetadata = mediaMetadata,
            navController = navController,
            state = state,
            menuState = menuState,
            bottomSheetPageState = bottomSheetPageState,
            sleepTimerEnabled = sleepTimerEnabled,
            repeatMode = repeatMode,
            textBackgroundColor = textBackgroundColor,
            playerConnection = playerConnection,
            onSleepTimerClick = onSleepTimerClick,
            onQueueClick = onQueueClick,
            onLyricsClick = onLyricsClick,
        )
    }
}

@Composable
private fun MetrolistArtwork(
    artworkUrl: String?,
    vintage: Boolean,
    hidden: Boolean,
) {
    // Vintage hangs the sleeve in a matted frame; Modern is the bare rounded square. The alpha
    // hides the art for the hide-thumbnail preference but keeps the square's bounds, like the
    // shared layout, so the rest of the column does not reflow.
    val frame =
        if (vintage) {
            Modifier
                .border(8.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(0.dp))
                .padding(16.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        } else {
            Modifier.clip(RoundedCornerShape(12.dp))
        }
    Box(
        modifier =
            Modifier
                .padding(horizontal = Gutter)
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(frame)
                .graphicsLayer { alpha = if (hidden) 0f else 1f },
    ) {
        Crossfade(
            targetState = artworkUrl,
            animationSpec = tween(if (LocalAnimationsDisabled.current) 0 else 300),
            label = "metrolist-artwork",
        ) { url ->
            PlayerCoverImage(
                imageUrl = url,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MetrolistMetadata(
    mediaMetadata: MediaMetadata,
    textBackgroundColor: Color,
    centered: Boolean,
    onTitleClick: () -> Unit,
    onCopyTitle: () -> Unit,
    onArtistClick: (String) -> Unit,
    onCopyArtists: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ContentGutter),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        PlayerTitleText(
            title = mediaMetadata.title,
            explicit = mediaMetadata.explicit,
            color = textBackgroundColor,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(
                        onClick = onTitleClick,
                        onLongClick = onCopyTitle,
                    ),
        )
        ClickableArtists(
            artists = mediaMetadata.artists,
            onArtistClick = onArtistClick,
            style = MaterialTheme.typography.bodyLarge,
            color = textBackgroundColor.copy(alpha = 0.75f),
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            onLongClick = onCopyArtists,
            modifier = if (centered) Modifier.fillMaxWidth() else Modifier,
        )
    }
}

@Composable
private fun MetrolistSeek(
    sliderPosition: Long?,
    positionProvider: () -> Long,
    duration: Long,
    isPlaying: Boolean,
    textButtonColor: Color,
    textBackgroundColor: Color,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
) {
    // The Metrolist seek bar is always the plain thin track; it ignores the shared slider-style
    // preference, which SLIDER_STYLE's capability row also reports as unhonoured for this style.
    PlayerSlider(
        sliderStyle = SliderStyle.Simple,
        sliderPosition = sliderPosition,
        position = positionProvider(),
        duration = duration,
        isPlaying = isPlaying,
        textButtonColor = textButtonColor,
        onValueChange = onSliderValueChange,
        onValueChangeFinished = onSliderValueChangeFinished,
    )
    PlayerTimeLabel(
        sliderPosition = sliderPosition,
        position = positionProvider(),
        duration = duration,
        textBackgroundColor = textBackgroundColor,
    )
}

@Composable
private fun MetrolistTransport(
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    vintage: Boolean,
    textButtonColor: Color,
    iconButtonColor: Color,
    textBackgroundColor: Color,
    playerConnection: PlayerConnection,
) {
    val previousMotion = rememberPlayerControlMotion(PlayerControlKind.Previous)
    val nextMotion = rememberPlayerControlMotion(PlayerControlKind.Next)
    val onPlayPauseClick = {
        if (playbackState == Player.STATE_ENDED) {
            playerConnection.player.seekTo(0, 0)
            playerConnection.player.playWhenReady = true
        } else {
            playerConnection.player.togglePlayPause()
        }
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ContentGutter),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = previousMotion.onClick { playerConnection.seekToPrevious() },
            enabled = canSkipPrevious,
        ) {
            Icon(
                painter = painterResource(R.drawable.player_skip_previous),
                contentDescription = null,
                tint = textBackgroundColor,
                modifier = Modifier.size(32.dp).then(previousMotion.modifier),
            )
        }

        // Vintage plays in a circle, Modern in a squircle — the only chrome difference the
        // presentation choice makes to the transport.
        Box(
            modifier =
                Modifier
                    .size(64.dp)
                    .playerPressFeedback()
                    .clip(moe.rukamori.archivetune.ui.player.rememberPlayerPlayShape(isPlaying, if (vintage) CircleShape else RoundedCornerShape(20.dp)))
                    .background(textButtonColor)
                    .clickable(onClick = onPlayPauseClick),
            contentAlignment = Alignment.Center,
        ) {
            PlayerLoadingCrossfade(isLoading, {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = iconButtonColor,
                    strokeWidth = 2.dp,
                )
            }) {
                PlayerTransportIcon(
                    iconRes = if (isPlaying) R.drawable.player_pause else R.drawable.player_play,
                    contentDescription = null,
                    tint = iconButtonColor,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        IconButton(
            onClick = nextMotion.onClick { playerConnection.seekToNext() },
            enabled = canSkipNext,
        ) {
            Icon(
                painter = painterResource(R.drawable.player_skip_next),
                contentDescription = null,
                tint = textBackgroundColor,
                modifier = Modifier.size(32.dp).then(nextMotion.modifier),
            )
        }
    }
}

@Composable
private fun MetrolistBottomControls(
    mediaMetadata: MediaMetadata,
    navController: NavController,
    state: BottomSheetState,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
    sleepTimerEnabled: Boolean,
    repeatMode: Int,
    textBackgroundColor: Color,
    playerConnection: PlayerConnection,
    onSleepTimerClick: () -> Unit,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
) {
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()
    // Toggled actions read at full strength, idle ones at half — the way the reference row tells
    // active state without adding a second tint.
    val activeColor = textBackgroundColor
    val idleColor = textBackgroundColor.copy(alpha = 0.55f)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onLyricsClick) {
            Icon(
                painter = painterResource(R.drawable.player_lyrics),
                contentDescription = null,
                tint = idleColor,
            )
        }
        IconButton(
            onClick = { playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled },
        ) {
            Icon(
                painter = painterResource(R.drawable.player_shuffle),
                contentDescription = null,
                tint = if (shuffleModeEnabled) activeColor else idleColor,
            )
        }
        IconButton(onClick = onQueueClick) {
            Icon(
                painter = painterResource(R.drawable.player_queue_music),
                contentDescription = null,
                tint = idleColor,
            )
        }
        IconButton(onClick = { playerConnection.player.toggleRepeatMode() }) {
            Icon(
                painter =
                    painterResource(
                        if (repeatMode == Player.REPEAT_MODE_ONE) {
                            R.drawable.player_repeat_one
                        } else {
                            R.drawable.player_repeat
                        },
                    ),
                contentDescription = null,
                tint = if (repeatMode != Player.REPEAT_MODE_OFF) activeColor else idleColor,
            )
        }
        IconButton(onClick = onSleepTimerClick) {
            Icon(
                painter = painterResource(R.drawable.player_bedtime),
                contentDescription = null,
                tint = if (sleepTimerEnabled) activeColor else idleColor,
            )
        }
        IconButton(
            onClick = {
                menuState.show {
                    PlayerMenu(
                        mediaMetadata = mediaMetadata,
                        navController = navController,
                        playerBottomSheetState = state,
                        onShowDetailsDialog = {
                            bottomSheetPageState.show {
                                ShowMediaInfo(mediaMetadata.id)
                            }
                        },
                        onDismiss = menuState::dismiss,
                    )
                }
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.player_more_vert),
                contentDescription = null,
                tint = idleColor,
            )
        }
    }
}
/**
 * Player.kt dispatch shim: collects the connection-driven state here so both mount sites
 * stay a single call, then forwards the host-resolved colors, seek plumbing and callbacks.
 */
@Composable
fun MetrolistPlayerDispatch(
    mediaMetadata: MediaMetadata?,
    navController: NavController,
    state: BottomSheetState,
    isPlaying: Boolean,
    isLoading: Boolean,
    sliderPosition: Long?,
    positionProvider: () -> Long,
    duration: Long,
    textButtonColor: Color,
    iconButtonColor: Color,
    textBackgroundColor: Color,
    contentBottomPadding: Dp,
    onSleepTimerClick: () -> Unit,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val metadata = mediaMetadata ?: return
    val playbackState by playerConnection.playbackState.collectAsStateWithLifecycle()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsStateWithLifecycle()
    val canSkipNext by playerConnection.canSkipNext.collectAsStateWithLifecycle()
    val repeatMode by playerConnection.repeatMode.collectAsStateWithLifecycle()
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle(initialValue = null)

    MetrolistPlayerContent(
        mediaMetadata = metadata,
        playbackState = playbackState,
        isPlaying = isPlaying,
        isLoading = isLoading,
        canSkipPrevious = canSkipPrevious,
        canSkipNext = canSkipNext,
        sliderPosition = sliderPosition,
        positionProvider = positionProvider,
        duration = duration,
        playerConnection = playerConnection,
        navController = navController,
        state = state,
        menuState = LocalMenuState.current,
        bottomSheetPageState = LocalBottomSheetPageState.current,
        currentSongLiked = currentSong?.song?.liked == true,
        sleepTimerEnabled = playerConnection.service.sleepTimer.isActive,
        repeatMode = repeatMode,
        textButtonColor = textButtonColor,
        iconButtonColor = iconButtonColor,
        textBackgroundColor = textBackgroundColor,
        contentBottomPadding = contentBottomPadding,
        onSleepTimerClick = onSleepTimerClick,
        onQueueClick = onQueueClick,
        onLyricsClick = onLyricsClick,
        onSliderValueChange = onSliderValueChange,
        onSliderValueChangeFinished = onSliderValueChangeFinished,
        landscape = landscape,
        modifier = modifier,
    )
}

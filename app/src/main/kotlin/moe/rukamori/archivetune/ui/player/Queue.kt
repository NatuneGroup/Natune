/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.player

import moe.rukamori.archivetune.ui.utils.LocalItemPreferences
import android.annotation.SuppressLint
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AutoLoadMoreKey
import moe.rukamori.archivetune.constants.ShowCodecOnPlayerKey
import moe.rukamori.archivetune.constants.ListItemHeight
import moe.rukamori.archivetune.constants.PlayerDesignStyle
import moe.rukamori.archivetune.constants.PlayerDesignStyleKey
import moe.rukamori.archivetune.constants.QueueEditLockKey
import moe.rukamori.archivetune.db.entities.PlaylistEntity
import moe.rukamori.archivetune.db.entities.PlaylistSongMap
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.extensions.move
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.extensions.toggleRepeatMode
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.ui.component.BottomSheet
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.LocalBottomSheetPageState
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.TextFieldDialog
import moe.rukamori.archivetune.ui.menu.AddToPlaylistDialog
import moe.rukamori.archivetune.ui.menu.PlayerMenu
import moe.rukamori.archivetune.ui.utils.ShowMediaInfo
import moe.rukamori.archivetune.utils.oem.SystemMediaControlResolver
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.constants.SwipeUpToOpenQueueKey
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.LocalDateTime

@SuppressLint("UnrememberedMutableState")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Queue(
    state: BottomSheetState,
    playerBottomSheetState: BottomSheetState,
    navController: NavController,
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    onBackgroundColor: Color,
    TextBackgroundColor: Color,
    textButtonColor: Color,
    iconButtonColor: Color,
    onShowLyrics: () -> Unit = {},
    pureBlack: Boolean,
) {
    val enableHapticFeedback = LocalItemPreferences.current.hapticFeedbackEnabled
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboard.current
    val menuState = LocalMenuState.current
    val bottomSheetPageState = LocalBottomSheetPageState.current

    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val repeatMode by playerConnection.repeatMode.collectAsStateWithLifecycle()
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()

    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle(initialValue = null)
    val currentSongLiked = currentSong?.song?.liked == true

    val currentFormat by playerConnection.currentFormat.collectAsStateWithLifecycle(initialValue = null)
    val queueTitle by playerConnection.queueTitle.collectAsStateWithLifecycle()

    val selectedSongs = remember { mutableStateListOf<MediaMetadata>() }
    val selectedItems = remember { mutableStateListOf<Timeline.Window>() }
    var selection by remember { mutableStateOf(false) }

    fun clearSelection() {
        selection = false
        selectedSongs.clear()
        selectedItems.clear()
    }

    if (selection) {
        BackHandler {
            clearSelection()
        }
    }

    var locked by rememberPreference(QueueEditLockKey, defaultValue = true)
    var infiniteQueueEnabled by rememberPreference(AutoLoadMoreKey, defaultValue = true)
    val infiniteQueueLoading by playerConnection.service.infiniteQueueLoading.collectAsStateWithLifecycle()

    val playerDesignStyle by rememberEnumPreference(
        key = PlayerDesignStyleKey,
        defaultValue = PlayerDesignStyle.Default,
    )

    val snackbarHostState = remember { SnackbarHostState() }
    var dismissJob: Job? by remember { mutableStateOf(null) }
    val coroutineScope = rememberCoroutineScope()
    val database = LocalDatabase.current
    var showChoosePlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var showCreateQueuePlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var queuePlaylistSnapshot by remember { mutableStateOf<List<MediaMetadata>?>(null) }

    AddToPlaylistDialog(
        isVisible = showChoosePlaylistDialog,
        onGetSong = {
            selectedSongs.map {
                database.withTransaction {
                    insert(it)
                }
                it.id
            }
        },
        onDismiss = { showChoosePlaylistDialog = false },
        onAddComplete = { songCount, playlistNames ->
            val message =
                when {
                    songCount == 1 && playlistNames.size == 1 -> {
                        context.getString(R.string.added_to_playlist, playlistNames.first())
                    }

                    songCount > 1 && playlistNames.size == 1 -> {
                        context.getString(
                            R.string.added_n_songs_to_playlist,
                            songCount,
                            playlistNames.first(),
                        )
                    }

                    songCount == 1 -> {
                        context.getString(R.string.added_to_n_playlists, playlistNames.size)
                    }

                    else -> {
                        context.getString(R.string.added_n_songs_to_n_playlists, songCount, playlistNames.size)
                    }
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            clearSelection()
        },
    )

    if (showCreateQueuePlaylistDialog) {
        val songs = queuePlaylistSnapshot ?: selectedSongs.toList()
        TextFieldDialog(
            icon = {
                Icon(
                    painter = painterResource(R.drawable.queue_music),
                    contentDescription = null,
                )
            },
            title = { Text(text = stringResource(R.string.create_playlist)) },
            placeholder = { Text(text = stringResource(R.string.playlist_name)) },
            initialTextFieldValue = TextFieldValue(queueTitle ?: context.getString(R.string.queue)),
            isInputValid = { it.trim().isNotEmpty() && songs.isNotEmpty() },
            onDismiss = { showCreateQueuePlaylistDialog = false; queuePlaylistSnapshot = null },
            onDone = onDone@{ rawPlaylistName ->
                val playlistName = rawPlaylistName.trim()
                if (playlistName.isEmpty() || songs.isEmpty()) return@onDone

                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val playlist =
                            PlaylistEntity(
                                name = playlistName,
                                bookmarkedAt = LocalDateTime.now(),
                                isEditable = true,
                            )

                        database.withTransaction {
                            insert(playlist)
                            songs.forEachIndexed { index, song ->
                                insert(song)
                                insert(
                                    PlaylistSongMap(
                                        playlistId = playlist.id,
                                        songId = song.id,
                                        position = index,
                                        setVideoId = song.setVideoId,
                                    ),
                                )
                            }
                        }

                        withContext(Dispatchers.Main) {
                            val message =
                                if (songs.size == 1) {
                                    context.getString(R.string.added_to_playlist, playlistName)
                                } else {
                                    context.getString(R.string.added_n_songs_to_playlist, songs.size, playlistName)
                                }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            clearSelection()
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, R.string.queue_save_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
        )
    }

    val queueWindows by playerConnection.queueWindows.collectAsStateWithLifecycle()
    val currentWindow =
        remember(currentWindowIndex, queueWindows) {
            queueWindows.getOrNull(currentWindowIndex)
        }
    var queuePositionMs by remember { mutableStateOf(0L) }
    LaunchedEffect(state.isCollapsed, currentWindowIndex, isPlaying) {
        while (!state.isCollapsed && isActive) {
            queuePositionMs = playerConnection.player.currentPosition.coerceAtLeast(0L)
            delay(1_000L)
        }
    }

    val showQueueUndo: (Long, Int, String?) -> Unit = { ticket, count, title ->
        dismissJob?.cancel()
        dismissJob =
            coroutineScope.launch {
                val snackbarResult =
                    snackbarHostState.showSnackbar(
                        message = if (count == 1) context.getString(R.string.removed_song_from_queue, title)
                            else context.getString(R.string.removed_n_songs_from_queue, count),
                        actionLabel = context.getString(R.string.undo),
                        duration = SnackbarDuration.Short,
                    )
                if (snackbarResult == SnackbarResult.ActionPerformed) {
                    playerConnection.service.undoQueueRemoval(ticket)
                }
            }
    }

    val onRemoveWithUndo: (Timeline.Window) -> Unit = { window ->
        playerConnection.service.removeQueueItemsWithUndo(listOf(window))?.let {
            showQueueUndo(it.ticket, it.count, window.mediaItem.metadata?.title)
        }
    }

    val onRemoveMultipleWithUndo: (List<Timeline.Window>) -> Unit = { windows ->
        playerConnection.service.removeQueueItemsWithUndo(windows)?.let {
            showQueueUndo(it.ticket, it.count, windows.firstOrNull()?.mediaItem?.metadata?.title)
        }
    }

    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var sleepTimerValue by remember { mutableStateOf(30f) }
    val sleepTimerEnabled =
        remember(
            playerConnection.service.sleepTimer.triggerTime,
            playerConnection.service.sleepTimer.pauseWhenSongEnd,
        ) {
            playerConnection.service.sleepTimer.isActive
        }
    val sleepTimerTimeLeft by rememberQueueSleepTimerRemainingMs(playerConnection.service.sleepTimer) {
        playerConnection.player
    }

    val (showCodecOnPlayer) =
        rememberPreference(
            key = ShowCodecOnPlayerKey,
            defaultValue = true,
        )

    var scrollToCurrentRequested by remember { mutableStateOf(true) }
    val openQueue =
        remember(playerBottomSheetState, state) {
            {
                scrollToCurrentRequested = true
                if (!playerBottomSheetState.isExpandedOrExpanding) {
                    playerBottomSheetState.expandSoft()
                }
                state.expandSoft()
            }
        }

    val (swipeUpToOpenQueue) = rememberPreference(SwipeUpToOpenQueueKey, defaultValue = true)

    BottomSheet(
        state = state,
        backgroundColor = Color.Unspecified,
        modifier = modifier,
        onCollapsedContentClick = openQueue,
        collapsedDragEnabled = swipeUpToOpenQueue,
        collapsedContent = {
            when (playerDesignStyle) {
                PlayerDesignStyle.V2 -> {
                    QueueCollapsedContentV2(
                        showCodecOnPlayer = showCodecOnPlayer,
                        currentFormat = currentFormat,
                        textBackgroundColor = TextBackgroundColor,
                        textButtonColor = textButtonColor,
                        iconButtonColor = iconButtonColor,
                        sleepTimerEnabled = sleepTimerEnabled,
                        sleepTimerTimeLeft = sleepTimerTimeLeft,
                        repeatMode = repeatMode,
                        mediaMetadata = mediaMetadata,
                        onExpandQueue = openQueue,
                        onSleepTimerClick = {
                            if (sleepTimerEnabled) {
                                playerConnection.service.sleepTimer.clear()
                            } else {
                                showSleepTimerDialog = true
                            }
                        },
                        onShowLyrics = onShowLyrics,
                        onRepeatModeClick = { playerConnection.player.toggleRepeatMode() },
                        onMenuClick = {
                            menuState.show {
                                PlayerMenu(
                                    mediaMetadata = mediaMetadata,
                                    navController = navController,
                                    playerBottomSheetState = playerBottomSheetState,
                                    onShowDetailsDialog = {
                                        mediaMetadata?.id?.let {
                                            bottomSheetPageState.show {
                                                ShowMediaInfo(it)
                                            }
                                        }
                                    },
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        },
                    )
                }

                PlayerDesignStyle.V3 -> {
                    QueueCollapsedContentV3(
                        showCodecOnPlayer = showCodecOnPlayer,
                        currentFormat = currentFormat,
                        textBackgroundColor = TextBackgroundColor,
                        sleepTimerEnabled = sleepTimerEnabled,
                        sleepTimerTimeLeft = sleepTimerTimeLeft,
                        onExpandQueue = openQueue,
                        onSleepTimerClick = {
                            if (sleepTimerEnabled) {
                                playerConnection.service.sleepTimer.clear()
                            } else {
                                showSleepTimerDialog = true
                            }
                        },
                        onShowLyrics = onShowLyrics,
                        onMenuClick = {
                            menuState.show {
                                PlayerMenu(
                                    mediaMetadata = mediaMetadata,
                                    navController = navController,
                                    playerBottomSheetState = playerBottomSheetState,
                                    onShowDetailsDialog = {
                                        mediaMetadata?.id?.let {
                                            bottomSheetPageState.show {
                                                ShowMediaInfo(it)
                                            }
                                        }
                                    },
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        },
                    )
                }

                PlayerDesignStyle.V5 -> {
                    QueueCollapsedContentV3(
                        showCodecOnPlayer = showCodecOnPlayer,
                        currentFormat = currentFormat,
                        textBackgroundColor = TextBackgroundColor,
                        sleepTimerEnabled = sleepTimerEnabled,
                        sleepTimerTimeLeft = sleepTimerTimeLeft,
                        onExpandQueue = openQueue,
                        onSleepTimerClick = {
                            if (sleepTimerEnabled) {
                                playerConnection.service.sleepTimer.clear()
                            } else {
                                showSleepTimerDialog = true
                            }
                        },
                        onShowLyrics = onShowLyrics,
                        onMenuClick = {
                            menuState.show {
                                PlayerMenu(
                                    mediaMetadata = mediaMetadata,
                                    navController = navController,
                                    playerBottomSheetState = playerBottomSheetState,
                                    onShowDetailsDialog = {
                                        mediaMetadata?.id?.let {
                                            bottomSheetPageState.show {
                                                ShowMediaInfo(it)
                                            }
                                        }
                                    },
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        },
                    )
                }

                PlayerDesignStyle.V4 -> {
                    QueueCollapsedContentV4(
                        showCodecOnPlayer = showCodecOnPlayer,
                        currentFormat = currentFormat,
                        textBackgroundColor = TextBackgroundColor,
                        textButtonColor = textButtonColor,
                        iconButtonColor = iconButtonColor,
                        sleepTimerEnabled = sleepTimerEnabled,
                        sleepTimerTimeLeft = sleepTimerTimeLeft,
                        mediaMetadata = mediaMetadata,
                        onExpandQueue = openQueue,
                        onSleepTimerClick = {
                            if (sleepTimerEnabled) {
                                playerConnection.service.sleepTimer.clear()
                            } else {
                                showSleepTimerDialog = true
                            }
                        },
                        onShowLyrics = onShowLyrics,
                    )
                }

                PlayerDesignStyle.V1 -> {
                    QueueCollapsedContentV1(
                        showCodecOnPlayer = showCodecOnPlayer,
                        currentFormat = currentFormat,
                        textBackgroundColor = TextBackgroundColor,
                        sleepTimerEnabled = sleepTimerEnabled,
                        sleepTimerTimeLeft = sleepTimerTimeLeft,
                        onExpandQueue = openQueue,
                        onSleepTimerClick = {
                            if (sleepTimerEnabled) {
                                playerConnection.service.sleepTimer.clear()
                            } else {
                                showSleepTimerDialog = true
                            }
                        },
                        onShowLyrics = onShowLyrics,
                    )
                }

                PlayerDesignStyle.V6 -> {
                    QueueCollapsedContentV4(
                        showCodecOnPlayer = showCodecOnPlayer,
                        currentFormat = currentFormat,
                        textBackgroundColor = TextBackgroundColor,
                        textButtonColor = textButtonColor,
                        iconButtonColor = iconButtonColor,
                        sleepTimerEnabled = sleepTimerEnabled,
                        sleepTimerTimeLeft = sleepTimerTimeLeft,
                        mediaMetadata = mediaMetadata,
                        onExpandQueue = openQueue,
                        onSleepTimerClick = {
                            if (sleepTimerEnabled) {
                                playerConnection.service.sleepTimer.clear()
                            } else {
                                showSleepTimerDialog = true
                            }
                        },
                        onShowLyrics = onShowLyrics,
                    )
                }

                // Looper draws its own transport but opens the shared queue sheet (its onQueueClick),
                // so it keeps the modern collapsed peek rather than opting out of the sheet.
                PlayerDesignStyle.V9, PlayerDesignStyle.V10, PlayerDesignStyle.LOOPER -> {
                    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()
                    QueueCollapsedContentV9(
                        showCodecOnPlayer = showCodecOnPlayer,
                        currentFormat = currentFormat,
                        textBackgroundColor = TextBackgroundColor,
                        sleepTimerEnabled = sleepTimerEnabled,
                        sleepTimerTimeLeft = sleepTimerTimeLeft,
                        shuffleModeEnabled = shuffleModeEnabled,
                        repeatMode = repeatMode,
                        onShuffleClick = {
                            playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled
                        },
                        onRepeatModeClick = { playerConnection.player.toggleRepeatMode() },
                        onMenuClick = {
                            menuState.show {
                                PlayerMenu(
                                    mediaMetadata = mediaMetadata,
                                    navController = navController,
                                    playerBottomSheetState = playerBottomSheetState,
                                    onShowDetailsDialog = {
                                        mediaMetadata?.id?.let {
                                            bottomSheetPageState.show {
                                                ShowMediaInfo(it)
                                            }
                                        }
                                    },
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        },
                        onSleepTimerClick = {
                            if (sleepTimerEnabled) {
                                playerConnection.service.sleepTimer.clear()
                            } else {
                                showSleepTimerDialog = true
                            }
                        },
                    )
                }

                PlayerDesignStyle.V7, PlayerDesignStyle.V8 -> {
                    val audioDevice by playerConnection.service.activeAudioDevice.collectAsStateWithLifecycle()

                    val view = LocalView.current
                    DisposableEffect(view) {
                        val listener =
                            ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                                if (hasFocus) playerConnection.service.refreshActiveDevice()
                            }
                        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
                        onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
                    }

                    QueueCollapsedContentV7(
                        showCodecOnPlayer = showCodecOnPlayer,
                        currentFormat = currentFormat,
                        textBackgroundColor = TextBackgroundColor,
                        sleepTimerEnabled = sleepTimerEnabled,
                        sleepTimerTimeLeft = sleepTimerTimeLeft,
                        onExpandQueue = openQueue,
                        onShowLyrics = onShowLyrics,
                        onSleepTimerClick = {
                            if (sleepTimerEnabled) {
                                playerConnection.service.sleepTimer.clear()
                            } else {
                                showSleepTimerDialog = true
                            }
                        },
                        onDeviceClick = {
                            SystemMediaControlResolver.openMediaOutputSwitcher(context)
                        },
                        device = audioDevice,
                    )
                }

                PlayerDesignStyle.APPLE_MUSIC -> {
                    // The Apple Music style renders its queue via the in-place
                    // SharedTransitionLayout morph in AppleMusicPlayer (its
                    // collapsed peek height is 0dp, so this BottomSheet never
                    // visibly collapses for that style). Keeping an explicit
                    // empty branch here means the `when` stays exhaustive and
                    // we don't accidentally render an upstream collapsed-content
                    // variant behind the morph.
                }

                PlayerDesignStyle.BITCHORD -> {
                    // The Bitchord style renders its queue inline inside its own player (drag the
                    // sleeve up, or tap the queue glyph), exactly like BitChord upstream. Its
                    // collapsed peek height is 0dp, so this sheet never visibly collapses either.
                }

                PlayerDesignStyle.TIKTOK -> {
                    // The TikTok style owns its queue surface too: the feed IS the queue — every
                    // page is a queue entry — and its collapsed peek height is 0dp.
                }

                PlayerDesignStyle.SIMPMUSIC -> {
                    // Same for the SimpMusic style: its artwork pager is a page per queue entry,
                    // and its collapsed peek height is 0dp.
                }

                PlayerDesignStyle.SPATIALFLOW -> {
                    // SpatialFlow carries its own sliding queue drawer, so the shared sheet would
                    // be a second queue over the top of it.
                }

                PlayerDesignStyle.FLAMINGO -> {
                    // Flamingo's queue is one of its own pages, reached from its transport, and
                    // its collapsed peek height is 0dp.
                }
            }

            if (showSleepTimerDialog) {
                SleepTimerDialog(
                    onDismiss = { showSleepTimerDialog = false },
                    onConfirm = { minutes ->
                        showSleepTimerDialog = false
                        playerConnection.service.sleepTimer.start(minutes)
                    },
                    onEndOfSong = {
                        showSleepTimerDialog = false
                        playerConnection.service.sleepTimer.start(-1)
                    },
                    initialValue = sleepTimerValue,
                )
            }
        },
    ) {
        val queueWindows by playerConnection.queueWindows.collectAsStateWithLifecycle()
        val mutableQueueWindows =
            remember {
                mutableStateListOf<Timeline.Window>().apply {
                    addAll(queueWindows)
                }
            }
        val queueLength by remember {
            derivedStateOf {
                queueWindows.sumOf { it.mediaItem.metadata?.duration ?: 0 }
            }
        }

        // The now-playing card and the selection spacer come before the rows.
        val headerItems = 2
        val lazyListState = rememberLazyListState()
        val density = LocalDensity.current
        val compactHeaderOffsetPx = with(density) { QueueCompactHeaderHeight.toPx() }
        val listScrolled by remember {
            derivedStateOf {
                lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0
            }
        }
        val statusBarTopInset =
            WindowInsets.systemBars
                .only(WindowInsetsSides.Top)
                .asPaddingValues()
                .calculateTopPadding()
        var dragInfo by remember { mutableStateOf<QueueDragInfo?>(null) }

        val currentPlayingUid =
            remember(currentWindowIndex, queueWindows) {
                if (currentWindowIndex in queueWindows.indices) {
                    queueWindows[currentWindowIndex].uid
                } else {
                    null
                }
            }
        val nextPlayingUid =
            remember(currentWindowIndex, queueWindows) {
                queueWindows.getOrNull(currentWindowIndex + 1)?.uid
            }

        val reorderableState =
            rememberReorderableLazyListState(
                lazyListState = lazyListState,
                scrollThresholdPadding =
                    WindowInsets.systemBars
                        .only(WindowInsetsSides.Bottom)
                        .add(
                            WindowInsets(
                                bottom = ListItemHeight,
                            ),
                        ).asPaddingValues(),
            ) onMove@{ from, to ->
                val fromQueueIndex = from.index - headerItems
                val toQueueIndex = to.index - headerItems
                if (
                    fromQueueIndex !in mutableQueueWindows.indices ||
                    toQueueIndex !in mutableQueueWindows.indices
                ) {
                    return@onMove
                }

                val draggedItemUid = dragInfo?.draggedItemUid ?: mutableQueueWindows[fromQueueIndex].uid
                val actualFromQueueIndex = mutableQueueWindows.indexOfFirst { it.uid == draggedItemUid }
                if (actualFromQueueIndex == -1) return@onMove

                mutableQueueWindows.move(actualFromQueueIndex, toQueueIndex)
                dragInfo =
                    QueueDragInfo(
                        draggedItemUid = draggedItemUid,
                        destination =
                            if (toQueueIndex == 0) {
                                QueueDragDestination.Start
                            } else {
                                QueueDragDestination.After(
                                    itemUid = mutableQueueWindows[toQueueIndex - 1].uid,
                                )
                            },
                    )

                if (selection && currentWindowIndex in mutableQueueWindows.indices) {
                    val currentItem = queueWindows.getOrNull(currentWindowIndex)

                    if (currentItem?.uid == draggedItemUid) {
                        val newIndex = mutableQueueWindows.indexOfFirst { it.uid == draggedItemUid }
                        if (newIndex != -1) {
                            selectedSongs.clear()
                            selectedItems.clear()
                            mutableQueueWindows.getOrNull(newIndex)?.let { window ->
                                window.mediaItem.metadata?.let { metadata ->
                                    selectedSongs.add(metadata)
                                    selectedItems.add(window)
                                }
                            }
                        }
                    }
                }
            }

        LaunchedEffect(queueWindows, reorderableState.isAnyItemDragging) {
            if (reorderableState.isAnyItemDragging) return@LaunchedEffect

            val completedDrag = dragInfo
            if (completedDrag != null) {
                val sourceIndex = queueWindows.indexOfFirst { it.uid == completedDrag.draggedItemUid }
                val destinationIndex = completedDrag.destination.resolveIndex(queueWindows, sourceIndex)
                dragInfo = null

                if (
                    sourceIndex != -1 &&
                    destinationIndex != null &&
                    sourceIndex != destinationIndex
                ) {
                    if (!playerConnection.player.shuffleModeEnabled) {
                        playerConnection.player.moveMediaItem(sourceIndex, destinationIndex)
                    } else {
                        playerConnection.localPlayer.setShuffleOrder(
                            DefaultShuffleOrder(
                                queueWindows
                                    .map { it.firstPeriodIndex }
                                    .toMutableList()
                                    .move(sourceIndex, destinationIndex)
                                    .toIntArray(),
                                System.currentTimeMillis(),
                            ),
                        )
                    }
                    return@LaunchedEffect
                }
            }

            Snapshot.withMutableSnapshot {
                mutableQueueWindows.clear()
                mutableQueueWindows.addAll(queueWindows)
            }
        }

        LaunchedEffect(
            state.isCollapsed,
            scrollToCurrentRequested,
            currentPlayingUid,
            reorderableState.isAnyItemDragging,
        ) {
            if (
                !state.isCollapsed &&
                scrollToCurrentRequested &&
                currentPlayingUid != null &&
                !reorderableState.isAnyItemDragging
            ) {
                val indexInMutableList = mutableQueueWindows.indexOfFirst { it.uid == currentPlayingUid }
                if (indexInMutableList != -1) {
                    lazyListState.scrollToItem(indexInMutableList + headerItems)
                    // The compact header covers the top of the viewport once the list has moved.
                    lazyListState.scrollBy(-compactHeaderOffsetPx)
                    scrollToCurrentRequested = false
                }
            }
        }

        // Shared by the expanded card and the compact header, so both surfaces run the same actions.
        val onToggleLikeClick: () -> Unit = {
            playerConnection.service.toggleLike()
        }
        val onQueueMenuClick: () -> Unit = {
            menuState.show {
                PlayerMenu(
                    mediaMetadata = mediaMetadata,
                    navController = navController,
                    playerBottomSheetState = playerBottomSheetState,
                    isQueueTrigger = true,
                    onRemoveFromQueue = {
                        currentWindow?.let { onRemoveWithUndo(it) }
                    },
                    onShowDetailsDialog = {
                        mediaMetadata?.id?.let {
                            bottomSheetPageState.show {
                                ShowMediaInfo(it)
                            }
                        }
                    },
                    onDismiss = menuState::dismiss,
                )
            }
        }
        val onClearQueueAction: () -> Unit = {
            infiniteQueueEnabled = false
            playerConnection.service.clearQueueWithUndo()?.let {
                showQueueUndo(it.ticket, it.count, null)
            }
            clearSelection()
        }
        val onRepeatQueueAction: () -> Unit = {
            playerConnection.player.toggleRepeatMode()
        }
        val onShuffleQueueAction: () -> Unit = {
            coroutineScope.launch(Dispatchers.Main) {
                playerConnection.player.shuffleModeEnabled = !playerConnection.player.shuffleModeEnabled
            }
        }
        val onLockQueueAction: () -> Unit = {
            locked = !locked
        }
        val onInfiniteQueueAction: () -> Unit = {
            val enabled = !infiniteQueueEnabled
            infiniteQueueEnabled = enabled
            if (!enabled) playerConnection.service.onInfiniteQueueDisabled()
        }
        val onSaveQueueAction: () -> Unit = {
            clearSelection()
            val songs = queueWindows.mapNotNull { it.mediaItem.metadata }
            queuePlaylistSnapshot = songs
            showCreateQueuePlaylistDialog = songs.isNotEmpty()
        }
        val onJumpToCurrentAction: () -> Unit = {
            scrollToCurrentRequested = true
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(backgroundColor),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                QueueSheetDragStrip(
                    sheetState = state,
                    backgroundColor = backgroundColor,
                    onBackgroundColor = onBackgroundColor,
                )

                LazyColumn(
                    state = lazyListState,
                    contentPadding =
                        WindowInsets.systemBars
                            .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                            .add(
                                WindowInsets(
                                    bottom = ListItemHeight + if (selection) 88.dp else 8.dp,
                                ),
                            ).asPaddingValues(),
                    modifier =
                        Modifier
                            .weight(1f)
                            .nestedScroll(state.preUpPostDownNestedScrollConnection),
                ) {
                    item(
                        key = "queue_header",
                        contentType = "queue_header",
                    ) {
                        QueueSongHeader(
                            mediaMetadata = mediaMetadata,
                            liked = currentSongLiked,
                            repeatMode = repeatMode,
                            shuffleModeEnabled = shuffleModeEnabled,
                            locked = locked,
                            songCount = queueWindows.size,
                            queueDuration = queueLength,
                            queueTitle = queueTitle,
                            queueRemainingMs = queueWindows.drop(currentWindowIndex.coerceAtLeast(0)).takeIf { windows ->
                                currentWindowIndex >= 0 && windows.all { (it.mediaItem.metadata?.duration ?: 0) > 0 }
                            }?.let { windows ->
                                (windows.sumOf { (it.mediaItem.metadata?.duration ?: 0) * 1_000L } - queuePositionMs).coerceAtLeast(0L)
                            },
                            infiniteQueueEnabled = infiniteQueueEnabled,
                            infiniteQueueLoading = infiniteQueueLoading,
                            onBackgroundColor = onBackgroundColor,
                            onToggleLike = onToggleLikeClick,
                            onMenuClick = onQueueMenuClick,
                            onClearQueueClick = onClearQueueAction,
                            onRepeatClick = onRepeatQueueAction,
                            onShuffleClick = onShuffleQueueAction,
                            onLockClick = onLockQueueAction,
                            onInfiniteQueueClick = onInfiniteQueueAction,
                            onSaveQueueClick = onSaveQueueAction,
                            onJumpToCurrentClick = onJumpToCurrentAction,
                        )
                    }

                    item(
                        key = "queue_selection_spacer",
                        contentType = "queue_selection_spacer",
                    ) {
                        Spacer(
                            modifier =
                                Modifier
                                    .animateContentSize()
                                    .height(if (selection) 48.dp else 0.dp),
                        )
                    }

                    itemsIndexed(
                        items = mutableQueueWindows,
                        key = { _, item -> item.queueItemKey },
                        contentType = { _, _ -> "queue_item" },
                    ) { index, window ->
                        ReorderableItem(
                            state = reorderableState,
                            key = window.queueItemKey,
                        ) {
                            val currentItem by rememberUpdatedState(window)
                            val isActive = window.uid == currentPlayingUid
                            val dismissBoxState =
                                rememberSwipeToDismissBoxState(
                                    positionalThreshold = { totalDistance -> totalDistance },
                                )

                            var processedDismiss by remember { mutableStateOf(false) }
                            LaunchedEffect(dismissBoxState.currentValue) {
                                val dv = dismissBoxState.currentValue
                                if (!processedDismiss && (
                                        dv == SwipeToDismissBoxValue.StartToEnd ||
                                            dv == SwipeToDismissBoxValue.EndToStart
                                    )
                                ) {
                                    processedDismiss = true
                                    onRemoveWithUndo(currentItem)
                                }
                                if (dv == SwipeToDismissBoxValue.Settled) {
                                    processedDismiss = false
                                }
                            }

                            val content: @Composable () -> Unit = {
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    val shouldLoadImages by remember {
                                        derivedStateOf {
                                            state.value > state.collapsedBound + 80.dp
                                        }
                                    }

                                    val trackMetadata = window.mediaItem.metadata ?: return@Row
                                    val onPlayNextFromQueue =
                                        remember(
                                            window.uid,
                                            window.firstPeriodIndex,
                                            currentPlayingUid,
                                            nextPlayingUid,
                                        ) {
                                            if (window.uid != currentPlayingUid && window.uid != nextPlayingUid) {
                                                {
                                                    playerConnection.moveQueueItemToNext(window.firstPeriodIndex)
                                                }
                                            } else {
                                                null
                                            }
                                        }
                                    QueueMediaMetadataListItem(
                                        mediaMetadata = trackMetadata,
                                        isSelected = selection && trackMetadata in selectedSongs,
                                        isActive = isActive,
                                        isPlaying = isPlaying && isActive,
                                        shouldLoadImage = shouldLoadImages,
                                        trailingContent = {
                                            IconButton(
                                                onClick = {
                                                    menuState.show {
                                                        PlayerMenu(
                                                            mediaMetadata = trackMetadata,
                                                            navController = navController,
                                                            playerBottomSheetState = playerBottomSheetState,
                                                            isQueueTrigger = true,
                                                            onPlayNextFromQueue = onPlayNextFromQueue,
                                                            onRemoveFromQueue = {
                                                                onRemoveWithUndo(window)
                                                            },
                                                            onShowDetailsDialog = {
                                                                window.mediaItem.mediaId.let {
                                                                    bottomSheetPageState.show {
                                                                        ShowMediaInfo(it)
                                                                    }
                                                                }
                                                            },
                                                            onDismiss = menuState::dismiss,
                                                        )
                                                    }
                                                },
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.more_vert),
                                                    contentDescription = null,
                                                )
                                            }
                                            if (!locked) {
                                                IconButton(
                                                    onClick = { },
                                                    modifier = Modifier.draggableHandle(),
                                                ) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.drag_handle),
                                                        contentDescription = null,
                                                    )
                                                }
                                            }
                                        },
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .background(backgroundColor)
                                                .combinedClickable(
                                                    onClick = {
                                                        if (selection) {
                                                            if (trackMetadata in selectedSongs) {
                                                                selectedSongs.remove(trackMetadata)
                                                                selectedItems.remove(currentItem)
                                                                if (selectedSongs.isEmpty()) {
                                                                    selection = false
                                                                }
                                                            } else {
                                                                selectedSongs.add(trackMetadata)
                                                                selectedItems.add(currentItem)
                                                            }
                                                        } else {
                                                            if (index == currentWindowIndex) {
                                                                playerConnection.player.togglePlayPause()
                                                            } else {
                                                                playerConnection.player.seekToDefaultPosition(
                                                                    window.firstPeriodIndex,
                                                                )
                                                                playerConnection.player.playWhenReady = true
                                                            }
                                                        }
                                                    },
                                                    onLongClick = {
                                                        if (enableHapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        if (!selection) {
                                                            selection = true
                                                        }
                                                        selectedSongs.clear()
                                                        selectedItems.clear()
                                                        selectedSongs.add(trackMetadata)
                                                        selectedItems.add(currentItem)
                                                    },
                                                ),
                                    )
                                }
                            }

                            if (locked) {
                                content()
                            } else {
                                SwipeToDismissBox(
                                    state = dismissBoxState,
                                    backgroundContent = {},
                                ) {
                                    content()
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = listScrolled,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = statusBarTopInset + QueueDragStripHeight),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                QueueCompactHeader(
                    mediaMetadata = mediaMetadata,
                    locked = locked,
                    shuffleModeEnabled = shuffleModeEnabled,
                    repeatMode = repeatMode,
                    infiniteQueueEnabled = infiniteQueueEnabled,
                    backgroundColor = backgroundColor,
                    onBackgroundColor = onBackgroundColor,
                    onShuffleClick = onShuffleQueueAction,
                    onRepeatClick = onRepeatQueueAction,
                    onLockClick = onLockQueueAction,
                    onInfiniteQueueClick = onInfiniteQueueAction,
                    onSaveQueueClick = onSaveQueueAction,
                    onJumpToCurrentClick = onJumpToCurrentAction,
                    onClearQueueClick = onClearQueueAction,
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier =
                        Modifier
                            .padding(
                                bottom =
                                    (if (selection) ListItemHeight * 2 + 16.dp else ListItemHeight) +
                                        WindowInsets.systemBars
                                            .asPaddingValues()
                                            .calculateBottomPadding(),
                            ).align(Alignment.BottomCenter),
                )

                AnimatedVisibility(
                    visible = selection,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(
                                bottom =
                                    ListItemHeight +
                                        WindowInsets.systemBars
                                            .asPaddingValues()
                                            .calculateBottomPadding(),
                            ),
                ) {
                    QueueSelectionFloatingToolbar(
                        allSelected = selectedSongs.size == mutableQueueWindows.size,
                        pureBlack = pureBlack,
                        onClose = ::clearSelection,
                        onToggleSelectAll = {
                            if (selectedSongs.size == mutableQueueWindows.size) {
                                clearSelection()
                            } else {
                                selectedSongs.clear()
                                selectedItems.clear()
                                mutableQueueWindows.forEach { window ->
                                    window.mediaItem.metadata?.let { metadata ->
                                        selectedSongs.add(metadata)
                                        selectedItems.add(window)
                                    }
                                }
                            }
                        },
                        onAddToPlaylist = { showChoosePlaylistDialog = true },
                        onCreatePlaylist = { showCreateQueuePlaylistDialog = true },
                        onDelete = {
                            onRemoveMultipleWithUndo(selectedItems.toList())
                            clearSelection()
                        },
                        modifier =
                            Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private val Timeline.Window.queueItemKey: Long
    get() =
        (uid.hashCode().toLong() shl Int.SIZE_BITS) xor
            (mediaItem.mediaId.hashCode().toLong() and UInt.MAX_VALUE.toLong())

@Immutable
private data class QueueDragInfo(
    public val draggedItemUid: Any,
    public val destination: QueueDragDestination,
)

@Immutable
private sealed interface QueueDragDestination {
    public data object Start : QueueDragDestination

    public data class After(
        public val itemUid: Any,
    ) : QueueDragDestination
}

private fun QueueDragDestination.resolveIndex(
    queueWindows: List<Timeline.Window>,
    sourceIndex: Int,
): Int? =
    when (this) {
        QueueDragDestination.Start -> if (queueWindows.isEmpty()) null else 0
        is QueueDragDestination.After -> {
            val anchorIndex = queueWindows.indexOfFirst { it.uid == itemUid }
            when {
                sourceIndex !in queueWindows.indices -> null
                anchorIndex == -1 -> null
                sourceIndex < anchorIndex -> anchorIndex
                else -> (anchorIndex + 1).coerceAtMost(queueWindows.lastIndex)
            }
        }
    }

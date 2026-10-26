package moe.rukamori.archivetune.shared.ui.menu.playerqueue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

private const val PLAYER_QUEUE_WATCH_URL = "https://music.youtube.com/watch?v="

/**
 * The player and queue-item overflow menu body: the now-playing header, the action grid and every section the baseline
 * `PlayerMenu` showed for the trigger. [trigger] decides the queue rows, [track] is the song every command acts on, and
 * [state] carries the host facts. Every command goes through [actions]; the menu runs no backend itself. The owner
 * supplies the sheet, so the menu needs a bounded height from its host. [onDismiss] closes that sheet, and the menu calls
 * it wherever [PlayerQueueMenuActions] documents a dismissal.
 *
 * The sleep timer replaces the body with its picker, as the baseline did. The source chooser, tempo and pitch dialog and
 * artist picker open over the menu. Owner-rendered dialogs (playlist picker, equalizer, save canvas, acoustic graph) are
 * commands, and the owner shows them.
 *
 * The four section parameters let the owner add rows at fixed points in this same scroll body: [canvasSection] after the
 * Save Canvas row, [artworkSection] after that, [songSection] between the playlist rows and the artist and album rows,
 * and [lyricsSection] after the download rows. A block adds `item`s to the menu's LazyColumn, so its rows scroll, dismiss
 * and lay out with the menu. A block adds its own spacing, because the menu adds none for it, so an empty block changes
 * nothing. The sleep timer hides every block along with the rest of the body. The Refetch canvas and Save Canvas rows
 * belong to the menu and must not be repeated in a block.
 */
@Composable
fun PlayerQueueMenu(
    trigger: PlayerQueueMenuTrigger,
    track: PlayerQueueTrack,
    state: PlayerQueueMenuState,
    actions: PlayerQueueMenuActions,
    clock: PlayerQueueClock,
    icon: PlayerQueueMenuIconSlot,
    artwork: PlayerQueueArtworkSlot,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    songSection: LazyListScope.() -> Unit = {},
    artworkSection: LazyListScope.() -> Unit = {},
    canvasSection: LazyListScope.() -> Unit = {},
    lyricsSection: LazyListScope.() -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val queueItem = trigger as? PlayerQueueMenuTrigger.QueueItem
    val isQueue = queueItem != null
    val playNextQueueItem = queueItem?.takeIf { it.playNextFromQueueAvailable }
    val removableQueueItem = queueItem?.takeIf { it.removeFromQueueAvailable }
    val canvasShown =
        !track.isLocal &&
            !isQueue &&
            state.canvasFeatureEnabled &&
            !state.lowDataModeActive &&
            state.style != PlayerDesignStyle.V5 &&
            state.hasCanvasArtwork
    val splitArtists =
        remember(track.artists, state.artistSeparators) {
            splitPlayerQueueArtists(navigableArtists(track), state.artistSeparators)
        }
    val album = track.album
    val trackUrl = "$PLAYER_QUEUE_WATCH_URL${track.mediaId}"

    var showArtistPicker by rememberSaveable { mutableStateOf(false) }
    var showSourceDialog by rememberSaveable { mutableStateOf(false) }
    var showTempoPitchDialog by rememberSaveable { mutableStateOf(false) }
    var showSleepTimerSheet by rememberSaveable { mutableStateOf(false) }

    val gridItems =
        buildList {
            if (state.castAvailable) {
                add(
                    PlayerQueueGridItem(
                        label = Res.string.m5pq_cast,
                        enabled = true,
                        leading = {
                            icon(
                                if (state.castConnected) PlayerQueueMenuIcon.CAST_CONNECTED else PlayerQueueMenuIcon.CAST,
                                LocalContentColor.current,
                                Modifier.size(28.dp),
                            )
                        },
                        onClick = { actions.cast(track) },
                    ),
                )
            }
            if (!track.isLocal) {
                add(
                    PlayerQueueGridItem(
                        label = Res.string.m5pq_start_radio,
                        enabled = true,
                        leading = { icon(PlayerQueueMenuIcon.RADIO, LocalContentColor.current, Modifier.size(28.dp)) },
                        onClick = {
                            actions.startRadio(track)
                            onDismiss()
                        },
                    ),
                )
            }
            if (canvasShown) {
                add(
                    PlayerQueueGridItem(
                        label = Res.string.m5pq_refetch_canvas,
                        enabled = !state.canvasRefetching,
                        leading = {
                            if (state.canvasRefetching) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            } else {
                                icon(PlayerQueueMenuIcon.SYNC, LocalContentColor.current, Modifier.size(28.dp))
                            }
                        },
                        onClick = {
                            scope.launch {
                                when (actions.refetchCanvas(track, requireVertical = state.style == PlayerDesignStyle.V7)) {
                                    PlayerQueueCanvasRefetchResult.SUCCESS -> onDismiss()
                                    // FAILURE is shown by the owner; ALREADY_RUNNING is a no-op, as the baseline's was.
                                    PlayerQueueCanvasRefetchResult.FAILURE, PlayerQueueCanvasRefetchResult.ALREADY_RUNNING -> Unit
                                }
                            }
                        },
                    ),
                )
            }
            if (track.isLocal) {
                add(
                    PlayerQueueGridItem(
                        label = Res.string.m5pq_share,
                        enabled = true,
                        leading = { icon(PlayerQueueMenuIcon.SHARE, LocalContentColor.current, Modifier.size(28.dp)) },
                        onClick = {
                            actions.shareLocalAudio(track)
                            onDismiss()
                        },
                    ),
                )
            } else {
                add(
                    PlayerQueueGridItem(
                        label = Res.string.m5pq_copy_link,
                        enabled = true,
                        leading = { icon(PlayerQueueMenuIcon.LINK, LocalContentColor.current, Modifier.size(28.dp)) },
                        onClick = {
                            actions.copyLink(trackUrl)
                            onDismiss()
                        },
                    ),
                )
            }
            if (!track.isLocal) {
                add(
                    PlayerQueueGridItem(
                        label = Res.string.m5pq_source,
                        enabled = true,
                        leading = { icon(PlayerQueueMenuIcon.TUNE, LocalContentColor.current, Modifier.size(28.dp)) },
                        onClick = { showSourceDialog = true },
                    ),
                )
            }
            if (!isQueue) {
                add(
                    PlayerQueueGridItem(
                        label = Res.string.m5pq_aod_mode,
                        enabled = true,
                        leading = { icon(PlayerQueueMenuIcon.BEDTIME, LocalContentColor.current, Modifier.size(28.dp)) },
                        onClick = {
                            actions.setAodMode()
                            onDismiss()
                        },
                    ),
                )
            }
        }

    PlayerQueueYouTubePlaylistDialog(state = state.playlistLoad, actions = actions)

    Column(modifier = modifier.fillMaxWidth()) {
        PlayerQueueHeaderCard(
            track = track,
            appleMusicExperience = state.appleMusicExperience,
            icon = icon,
            artwork = artwork,
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding =
                PaddingValues(
                    bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
                ),
        ) {
            if (showSleepTimerSheet) {
                item {
                    PlayerQueueSleepTimerSheet(
                        state = state.sleepTimer,
                        clock = clock,
                        actions = actions,
                        icon = icon,
                        onDismiss = {
                            showSleepTimerSheet = false
                            onDismiss()
                        },
                    )
                }
            } else {
                item {
                    PlayerQueueYouTubePlaylistRecoveryActions(state = state.playlistLoad, actions = actions)
                }
                item {
                    PlayerQueueSurface(modifier = Modifier.padding(vertical = 6.dp)) {
                        PlayerQueueGrid(
                            items = gridItems,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(12.dp)) }

                if (canvasShown) {
                    item {
                        PlayerQueueSurface(modifier = Modifier.padding(vertical = 6.dp)) {
                            PlayerQueueRow(
                                headline = stringResource(Res.string.m5pq_save_canvas),
                                leading = { icon(PlayerQueueMenuIcon.MOTION_PHOTOS, LocalContentColor.current, Modifier) },
                                onClick = { actions.saveCanvas(track) },
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.height(12.dp)) }
                }
                canvasSection()
                artworkSection()

                item {
                    PlayerQueueSurface(modifier = Modifier.padding(vertical = 6.dp)) {
                        PlayerQueueRow(
                            headline = stringResource(Res.string.m5pq_add_to_playlist),
                            leading = { icon(PlayerQueueMenuIcon.PLAYLIST_ADD, LocalContentColor.current, Modifier) },
                            onClick = { actions.addToPlaylist(track) },
                        )
                        PlayerQueueDivider()
                        PlayerQueueRow(
                            headline =
                                stringResource(
                                    if (state.pinnedToSpeedDial) {
                                        Res.string.m5pq_remove_from_speed_dial
                                    } else {
                                        Res.string.m5pq_pin_to_speed_dial
                                    },
                                ),
                            leading = {
                                icon(
                                    if (state.pinnedToSpeedDial) PlayerQueueMenuIcon.BOOKMARK_FILLED else PlayerQueueMenuIcon.BOOKMARK,
                                    LocalContentColor.current,
                                    Modifier,
                                )
                            },
                            onClick = {
                                actions.toggleSpeedDialPin(track)
                                onDismiss()
                            },
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(12.dp)) }

                songSection()

                if (splitArtists.isNotEmpty() || album != null) {
                    item {
                        PlayerQueueSurface(modifier = Modifier.padding(vertical = 6.dp)) {
                            if (splitArtists.isNotEmpty()) {
                                PlayerQueueRow(
                                    headline = stringResource(Res.string.m5pq_view_artist),
                                    leading = { icon(PlayerQueueMenuIcon.ARTIST, LocalContentColor.current, Modifier) },
                                    onClick = {
                                        if (splitArtists.size == 1) {
                                            onDismiss()
                                            actions.openArtist(splitArtists[0].original, PlayerQueueSheetCollapse.SNAP)
                                        } else {
                                            showArtistPicker = true
                                        }
                                    },
                                )
                            }
                            if (splitArtists.isNotEmpty() && album != null) {
                                PlayerQueueDivider()
                            }
                            if (album != null) {
                                PlayerQueueRow(
                                    headline = stringResource(Res.string.m5pq_view_album),
                                    leading = { icon(PlayerQueueMenuIcon.ALBUM, LocalContentColor.current, Modifier) },
                                    onClick = {
                                        onDismiss()
                                        actions.openAlbum(album)
                                    },
                                )
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(12.dp)) }
                }

                if (!track.isLocal) {
                    item {
                        PlayerQueueSurface(modifier = Modifier.padding(vertical = 6.dp)) {
                            when (state.download) {
                                PlayerQueueDownloadState.COMPLETED ->
                                    PlayerQueueRow(
                                        headline = stringResource(Res.string.m5pq_remove_download),
                                        leading = {
                                            icon(PlayerQueueMenuIcon.OFFLINE, MaterialTheme.colorScheme.error, Modifier)
                                        },
                                        onClick = { actions.removeDownload(track) },
                                        isError = true,
                                    )

                                PlayerQueueDownloadState.IN_PROGRESS ->
                                    PlayerQueueRow(
                                        headline = stringResource(Res.string.m5pq_downloading),
                                        leading = { CircularProgressIndicator(modifier = Modifier.size(24.dp)) },
                                        onClick = { actions.removeDownload(track) },
                                    )

                                PlayerQueueDownloadState.NOT_DOWNLOADED ->
                                    PlayerQueueRow(
                                        headline = stringResource(Res.string.m5pq_action_download),
                                        leading = { icon(PlayerQueueMenuIcon.DOWNLOAD, LocalContentColor.current, Modifier) },
                                        onClick = { actions.downloadTrack(track) },
                                    )
                            }
                            if (state.externalDownloaderEnabled) {
                                PlayerQueueDivider()
                                PlayerQueueRow(
                                    headline = stringResource(Res.string.m5pq_open_with_downloader),
                                    leading = { icon(PlayerQueueMenuIcon.DOWNLOAD, LocalContentColor.current, Modifier) },
                                    onClick = {
                                        onDismiss()
                                        actions.openWithExternalDownloader(trackUrl)
                                    },
                                )
                            }
                        }
                    }
                }

                lyricsSection()

                item {
                    PlayerQueueSurface(modifier = Modifier.padding(vertical = 6.dp)) {
                        if (playNextQueueItem != null) {
                            PlayerQueueRow(
                                headline = stringResource(Res.string.m5pq_play_next),
                                leading = { icon(PlayerQueueMenuIcon.PLAYLIST_PLAY, LocalContentColor.current, Modifier) },
                                onClick = {
                                    actions.playNextFromQueue(playNextQueueItem.occurrenceId)
                                    onDismiss()
                                },
                            )
                            PlayerQueueDivider()
                        }
                        if (removableQueueItem != null) {
                            PlayerQueueRow(
                                headline = stringResource(Res.string.m5pq_remove_from_queue),
                                leading = { icon(PlayerQueueMenuIcon.DELETE, MaterialTheme.colorScheme.error, Modifier) },
                                onClick = {
                                    actions.removeFromQueue(removableQueueItem.occurrenceId)
                                    onDismiss()
                                },
                                isError = true,
                            )
                            PlayerQueueDivider()
                            PlayerQueueRow(
                                headline = stringResource(Res.string.m5pq_play_next),
                                leading = { icon(PlayerQueueMenuIcon.PLAYLIST_PLAY, LocalContentColor.current, Modifier) },
                                onClick = {
                                    actions.playNext(track)
                                    onDismiss()
                                },
                            )
                            PlayerQueueRow(
                                headline = stringResource(Res.string.m5pq_add_to_queue),
                                leading = { icon(PlayerQueueMenuIcon.QUEUE_MUSIC, LocalContentColor.current, Modifier) },
                                onClick = {
                                    actions.addToQueue(track)
                                    onDismiss()
                                },
                            )
                            PlayerQueueDivider()
                        }
                        PlayerQueueRow(
                            headline = stringResource(Res.string.m5pq_acoustic_quality_title),
                            leading = { icon(PlayerQueueMenuIcon.GRAPHIC_EQ, LocalContentColor.current, Modifier) },
                            onClick = {
                                onDismiss()
                                actions.openAcousticQuality(track)
                            },
                        )
                        PlayerQueueRow(
                            headline = stringResource(Res.string.m5pq_details),
                            leading = { icon(PlayerQueueMenuIcon.INFO, LocalContentColor.current, Modifier) },
                            onClick = {
                                onDismiss()
                                actions.showDetails(track)
                            },
                        )
                        if (!isQueue) {
                            PlayerQueueDivider()
                            // Apple Music shows the sleep timer as a pill in its queue sheet, so the menu omits it there.
                            if (state.style != PlayerDesignStyle.APPLE_MUSIC) {
                                PlayerQueueRow(
                                    headline = stringResource(Res.string.m5pq_sleep_timer),
                                    leading = { icon(PlayerQueueMenuIcon.BEDTIME, LocalContentColor.current, Modifier) },
                                    onClick = { showSleepTimerSheet = true },
                                )
                                PlayerQueueDivider()
                            }
                            PlayerQueueRow(
                                headline = stringResource(Res.string.m5pq_equalizer),
                                leading = { icon(PlayerQueueMenuIcon.EQUALIZER, LocalContentColor.current, Modifier) },
                                onClick = { actions.openEqualizer() },
                            )
                            PlayerQueueDivider()
                            PlayerQueueRow(
                                headline = stringResource(Res.string.m5pq_tempo_and_pitch),
                                leading = { icon(PlayerQueueMenuIcon.SPEED, LocalContentColor.current, Modifier) },
                                supporting =
                                    stringResource(
                                        Res.string.m5pq_tempo_pitch_summary,
                                        formatMultiplier(state.playbackSpeed),
                                        formatMultiplier(state.playbackPitch),
                                    ),
                                onClick = { showTempoPitchDialog = true },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showArtistPicker) {
        PlayerQueueListDialog(onDismiss = { showArtistPicker = false }) {
            items(splitArtists.distinctBy { it.name }, key = { it.name }) { splitArtist ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = splitArtist.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = {
                        PlayerQueueArtistThumbnail(
                            url = splitArtist.original.thumbnailUrl,
                            icon = icon,
                            artwork = artwork,
                        )
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                showArtistPicker = false
                                onDismiss()
                                actions.openArtist(splitArtist.original, PlayerQueueSheetCollapse.SOFT)
                            },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }

    if (showSourceDialog) {
        PlayerQueueSongSourceDialog(
            track = track,
            state = state,
            actions = actions,
            icon = icon,
            artwork = artwork,
            onDismiss = { showSourceDialog = false },
        )
    }

    if (showTempoPitchDialog) {
        PlayerQueueTempoPitchDialog(
            initialSpeed = state.playbackSpeed,
            initialPitch = state.playbackPitch,
            actions = actions,
            icon = icon,
            onDismiss = { showTempoPitchDialog = false },
        )
    }
}

/** The artist picker thumbnail: the owner's image in a 40 dp circle, or the music-note mark when none resolved. */
@Composable
private fun PlayerQueueArtistThumbnail(
    url: String?,
    icon: PlayerQueueMenuIconSlot,
    artwork: PlayerQueueArtworkSlot,
) {
    if (url.isNullOrBlank()) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            icon(PlayerQueueMenuIcon.MUSIC_NOTE, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(18.dp))
        }
    } else {
        artwork(url, Modifier.size(40.dp).clip(CircleShape))
    }
}

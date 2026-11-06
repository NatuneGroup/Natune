package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.awt.Desktop
import java.io.File
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticAuditTarget
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentDownload
import moe.rukamori.archivetune.shared.domain.repository.LibraryMutation
import moe.rukamori.archivetune.shared.domain.settings.appleMusicMenus
import moe.rukamori.archivetune.shared.domain.settings.canvasPlaybackEnabled
import moe.rukamori.archivetune.shared.domain.settings.ArtistSeparatorsKey
import moe.rukamori.archivetune.shared.domain.settings.encodeTempoPitchPresets
import moe.rukamori.archivetune.shared.domain.settings.newTempoPitchPresetId
import moe.rukamori.archivetune.shared.domain.settings.lowDataMode
import moe.rukamori.archivetune.shared.domain.settings.parseTempoPitchPresets
import moe.rukamori.archivetune.shared.domain.settings.PlaybackPitchKey
import moe.rukamori.archivetune.shared.domain.settings.PlaybackSpeedKey
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyleKey
import moe.rukamori.archivetune.shared.domain.settings.PlayerMenuStyle
import moe.rukamori.archivetune.shared.domain.settings.playerMenuStyle
import moe.rukamori.archivetune.shared.domain.settings.PreferenceEditor
import moe.rukamori.archivetune.shared.domain.settings.removeTempoPitchPreset
import moe.rukamori.archivetune.shared.domain.settings.renameTempoPitchPreset
import moe.rukamori.archivetune.shared.domain.settings.SongSourceAmazonPickKey
import moe.rukamori.archivetune.shared.domain.settings.SongSourceOverrideKey
import moe.rukamori.archivetune.shared.domain.settings.TempoPitchPreset
import moe.rukamori.archivetune.shared.domain.settings.TempoPitchPresetsJsonKey
import moe.rukamori.archivetune.shared.domain.settings.TempoPitchRememberedKey
import moe.rukamori.archivetune.shared.domain.settings.upsertTempoPitchPreset
import moe.rukamori.archivetune.shared.domain.settings.SongSourceQobuzBackupVideoIdKey
import moe.rukamori.archivetune.shared.domain.settings.SongSourceQobuzTrackIdKey
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialSongIdsKey
import moe.rukamori.archivetune.shared.domain.settings.parseSpeedDialPins
import moe.rukamori.archivetune.shared.domain.settings.serializeSpeedDialPins
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialPin
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialPinType
import moe.rukamori.archivetune.shared.domain.settings.toEnum
import moe.rukamori.archivetune.shared.domain.settings.toggleSpeedDialPin
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.AmazonTrackPick
import moe.rukamori.archivetune.shared.domain.source.SongSourceAmazonPick
import moe.rukamori.archivetune.shared.domain.source.SongSourceOverride
import moe.rukamori.archivetune.shared.domain.source.SongSourceQobuzBackupVideoId
import moe.rukamori.archivetune.shared.domain.source.SongSourceQobuzTrackId
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueAlbum
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueArtist
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueCanvasRefetchResult
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueClock
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueDownloadState
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueMenu
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueMenuActions
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueMenuIcon
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueMenuState
import moe.rukamori.archivetune.shared.ui.menu.CanvasSourcePicker
import moe.rukamori.archivetune.shared.ui.menu.rememberCanvasSaveMenuState
import moe.rukamori.archivetune.shared.ui.menu.shareLinkAtPosition
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueMenuTrigger
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueuePlaylistLoadState
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueSheetCollapse
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueTrack
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerSourceOverrideRequest
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerSourceSearchResult
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes

private val DesktopPlayerQueueClock = PlayerQueueClock { System.currentTimeMillis() }

/** A fresh occurrence identity for a menu insertion: the song's id plus a unique tail, so the queue and playlists
 * accept the same song twice, as the baseline allowed. */
private fun menuOccurrenceId(songId: String): String = "menu-$songId-${System.nanoTime()}"

/**
 * The player's overflow menu on the desktop host. It mounts the shared [PlayerQueueMenu] body, so the rows are the
 * baseline's; rows whose owner this host has (queue order, playlist picker, source pin, speed dial, tempo and pitch,
 * sleep timer, equalizer, copy link, artist and album pages, details, share local, radio, downloads) act, and rows it
 * does not (cast, canvas, always-on display, the external downloader, acoustic graph) are hidden through
 * the state's capability flags instead of appearing as no-ops.
 */
internal class DesktopPlayerMenuState {
    /** The song the menu is open for, or null while it is closed. */
    var target: ContentItem? by mutableStateOf(null)
        private set

    /** The song the save-canvas flow is bound to, kept through the menu's own dismissal. */
    var canvasItem: ContentItem? by mutableStateOf(null)
        private set

    /** The song the playlist picker is open for, kept across the picker's own dialog. */
    var playlistPick: PlayerQueueTrack? by mutableStateOf(null)

    /** The song the details dialog shows. */
    var details: PlayerQueueTrack? by mutableStateOf(null)

    /** The save-canvas outcome text the owner reported, resolved by the dialog. */
    var canvasNotice: StringResource? by mutableStateOf(null)

    fun open(item: ContentItem) {
        target = item
        canvasItem = item
    }

    fun dismiss() {
        target = null
        playlistPick = null
        details = null
    }
}

/** Maps the queue item the menu opened for to the shared menu's projection. */
internal fun desktopPlayerMenuTrack(item: ContentItem): PlayerQueueTrack = PlayerQueueTrack(
    mediaId = item.ref.id,
    title = item.title,
    artists = item.artists.map { PlayerQueueArtist(id = it.id, name = it.name) },
    album = item.albumId?.let { PlayerQueueAlbum(id = it, title = item.album ?: it) },
    thumbnailUrl = item.artworkUri,
    isLocal = item.ref.provider == "local",
    localMimeType = null,
)

/** Every player-menu command the desktop host owns. Commands for hidden rows are unreachable and only logged. */
internal class DesktopPlayerQueueActions(
    private val graph: DesktopSessionGraph,
    private val menu: DesktopPlayerMenuState,
    private val canvas: DesktopCanvasOwner,
    private val navigate: (String) -> Unit,
) : PlayerQueueMenuActions {
    // The applied tempo and pitch of this session, as the baseline's live playback parameters.
    private val appliedSpeed = MutableStateFlow(1f)
    private val appliedPitch = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = appliedSpeed.asStateFlow()
    val playbackPitch: StateFlow<Float> = appliedPitch.asStateFlow()

    private fun unsupported(command: String) = graph.report("player menu $command has no owner on the desktop host")

    // Queue order, through the one queue owner.

    override fun playNextFromQueue(occurrenceId: String) {
        graph.scope.launch {
            try {
                val state = graph.queue.state.value
                val current = state.currentOccurrenceId
                val anchor = current?.let { id ->
                    val index = state.playOrder.indexOf(id)
                    state.playOrder.getOrNull(index + 1)
                }
                graph.queue.move(occurrenceId, anchor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("play next was not applied: ${failure.javaClass.simpleName}")
            }
        }
    }

    override fun removeFromQueue(occurrenceId: String) {
        graph.scope.launch {
            try {
                graph.queue.remove(setOf(occurrenceId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("queue remove was not applied: ${failure.javaClass.simpleName}")
            }
        }
    }

    private fun enqueue(track: PlayerQueueTrack, kind: UserQueuedKind) {
        val item = menu.target ?: return
        graph.scope.launch {
            try {
                graph.queue.insert(listOf(ContentOccurrence(occurrenceId = menuOccurrenceId(item.ref.id), item = item)), kind)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("queue insert was not applied: ${failure.javaClass.simpleName}")
            }
        }
    }

    override fun playNext(track: PlayerQueueTrack) = enqueue(track, UserQueuedKind.PLAY_NEXT)

    override fun addToQueue(track: PlayerQueueTrack) = enqueue(track, UserQueuedKind.ADD_TO_QUEUE)

    // Tempo and pitch: live playback parameters, as the baseline applies them. While the opt-in "remember"
    // flag holds, every applied pair is persisted for the next launch's restore.

    override fun applyPlaybackParameters(speed: Float, pitch: Float) {
        graph.player.setPlaybackSpeed(speed)
        graph.player.setPlaybackPitch(pitch)
        appliedSpeed.value = speed
        appliedPitch.value = pitch
        if (graph.settings.snapshots.value?.get(TempoPitchRememberedKey) == true) {
            persistTempoPitch {
                set(PlaybackSpeedKey, speed)
                set(PlaybackPitchKey, pitch)
            }
        }
    }

    override fun copyLinkAtCurrentPosition(url: String) =
        copyToDesktopClipboard(shareLinkAtPosition(url, graph.player.positionMs() ?: 0L))

    override fun setTempoPitchRemembered(remembered: Boolean) =
        persistTempoPitch {
            set(TempoPitchRememberedKey, remembered)
            // Opting in stores the applied pair at once; opting out keeps the last stored values unread.
            if (remembered) {
                set(PlaybackSpeedKey, appliedSpeed.value)
                set(PlaybackPitchKey, appliedPitch.value)
            }
        }

    override fun saveTempoPitchPreset(name: String, speed: Float, pitch: Float) {
        val preset = TempoPitchPreset(newTempoPitchPresetId(), name.trim(), speed, pitch)
        if (!preset.isValid) return
        persistTempoPitch {
            set(
                TempoPitchPresetsJsonKey,
                encodeTempoPitchPresets(upsertTempoPitchPreset(parseTempoPitchPresets(get(TempoPitchPresetsJsonKey)), preset)),
            )
        }
    }

    override fun renameTempoPitchPreset(id: String, name: String) {
        if (name.isBlank()) return
        persistTempoPitch {
            set(
                TempoPitchPresetsJsonKey,
                encodeTempoPitchPresets(renameTempoPitchPreset(parseTempoPitchPresets(get(TempoPitchPresetsJsonKey)), id, name)),
            )
        }
    }

    override fun deleteTempoPitchPreset(id: String) =
        persistTempoPitch {
            set(
                TempoPitchPresetsJsonKey,
                encodeTempoPitchPresets(removeTempoPitchPreset(parseTempoPitchPresets(get(TempoPitchPresetsJsonKey)), id)),
            )
        }

    private fun persistTempoPitch(change: PreferenceEditor.() -> Unit) {
        graph.scope.launch {
            try {
                graph.settings.edit(change)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("tempo/pitch preference was not saved: ${failure.javaClass.simpleName}")
            }
        }
    }

    // The source chooser: per-song pins persisted exactly as the portable codecs store them, consulted by the
    // queue locator on the next resolution, and re-applied at once when the picked song is playing.

    override fun chooseSongSource(track: PlayerQueueTrack, source: AudioSourceType?) {
        graph.scope.launch {
            try {
                graph.settings.edit {
                    set(SongSourceOverrideKey, SongSourceOverride.withOverride(get(SongSourceOverrideKey), track.mediaId, source))
                }
                reselectIfPlaying(track)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("source pin was not saved: ${failure.javaClass.simpleName}")
            }
        }
    }

    override fun chooseSongSourceSearchResult(track: PlayerQueueTrack, request: PlayerSourceOverrideRequest) {
        graph.scope.launch {
            try {
                graph.settings.edit {
                    when (request) {
                        is PlayerSourceOverrideRequest.Plain -> {
                            set(SongSourceOverrideKey, SongSourceOverride.withOverride(get(SongSourceOverrideKey), track.mediaId, request.source))
                        }
                        is PlayerSourceOverrideRequest.QobuzTrack -> {
                            set(SongSourceOverrideKey, SongSourceOverride.withOverride(get(SongSourceOverrideKey), track.mediaId, AudioSourceType.QOBUZ))
                            set(SongSourceQobuzTrackIdKey, SongSourceQobuzTrackId.withOverride(get(SongSourceQobuzTrackIdKey), track.mediaId, request.trackId))
                        }
                        is PlayerSourceOverrideRequest.QobuzBackupVideo -> {
                            set(SongSourceOverrideKey, SongSourceOverride.withOverride(get(SongSourceOverrideKey), track.mediaId, AudioSourceType.QOBUZ_BACKUP))
                            set(SongSourceQobuzBackupVideoIdKey, SongSourceQobuzBackupVideoId.withOverride(get(SongSourceQobuzBackupVideoIdKey), track.mediaId, request.videoId))
                        }
                        is PlayerSourceOverrideRequest.AmazonPick -> {
                            set(SongSourceOverrideKey, SongSourceOverride.withOverride(get(SongSourceOverrideKey), track.mediaId, AudioSourceType.AMAZON))
                            set(SongSourceAmazonPickKey, SongSourceAmazonPick.withOverride(get(SongSourceAmazonPickKey),
                                track.mediaId, AmazonTrackPick(request.title, request.artist, request.album, request.durationMs)))
                        }
                    }
                }
                reselectIfPlaying(track)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("source pin was not saved: ${failure.javaClass.simpleName}")
            }
        }
    }

    /** Re-resolves the playing occurrence so a pin acts on it now, not only on its next play. */
    private suspend fun reselectIfPlaying(track: PlayerQueueTrack) {
        val state = graph.queue.state.value
        val current = state.currentOccurrenceId ?: return
        if (state.entries.firstOrNull { it.occurrenceId == current }?.item?.ref?.id != track.mediaId) return
        val position = graph.player.positionMs() ?: 0L
        graph.queue.select(current, position)
    }

    override fun refreshSongSources(track: PlayerQueueTrack) {
        graph.youtubeResolver.invalidateSearch(track.mediaId)
        graph.scope.launch {
            try {
                reselectIfPlaying(track)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("source refresh was not applied: ${failure.javaClass.simpleName}")
            }
        }
    }

    /** One search owner reuses the playback backends; unsupported provider filters stay honestly unavailable. */
    override suspend fun searchSongSources(
        query: String,
        sourceFilter: AudioSourceType?,
        includeAmazon: Boolean,
    ): List<PlayerSourceSearchResult> = graph.audioSources.search(graph.youtubeContent, query, sourceFilter, includeAmazon)

    /** Plays the picked YouTube result: the song goes next and starts, as the dialog's play action did. */
    override fun playYouTubeSearchResult(track: PlayerQueueTrack, result: PlayerSourceSearchResult) {
        graph.scope.launch {
            try {
                val item = ContentItem(
                    ref = ContentRef(provider = ContentProvider.YOUTUBE.id, kind = ContentKind.SONG, id = result.trackId),
                    title = result.title,
                    artworkUri = result.thumbnailUrl,
                    durationMs = result.durationMs,
                    album = result.album,
                )
                graph.queue.insert(listOf(ContentOccurrence(occurrenceId = menuOccurrenceId(result.trackId), item = item)), UserQueuedKind.PLAY_NEXT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("search result was not queued: ${failure.javaClass.simpleName}")
            }
        }
    }

    // Library, share and navigation.

    override fun addToPlaylist(track: PlayerQueueTrack) {
        menu.playlistPick = track
    }

    override fun toggleSpeedDialPin(track: PlayerQueueTrack) {
        graph.scope.launch {
            try {
                graph.settings.edit {
                    val pins = parseSpeedDialPins(get(SpeedDialSongIdsKey) ?: "")
                    set(SpeedDialSongIdsKey, serializeSpeedDialPins(toggleSpeedDialPin(pins, SpeedDialPin(SpeedDialPinType.SONG, track.mediaId))))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("speed dial pin was not saved: ${failure.javaClass.simpleName}")
            }
        }
    }

    override fun copyLink(url: String) = copyToDesktopClipboard(url)

    /** The local song's file in the OS file manager; the share sheet has no desktop counterpart. */
    override fun shareLocalAudio(track: PlayerQueueTrack) {
        val file = File(track.mediaId)
        try {
            if (file.isFile && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browseFileDirectory(file)
            } else if (file.parentFile?.isDirectory == true && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file.parentFile)
            } else {
                graph.report("no local file is on disk for ${track.mediaId}")
            }
        } catch (failure: Exception) {
            graph.report("the local file was not shown: ${failure.javaClass.simpleName}")
        }
    }

    override fun showDetails(track: PlayerQueueTrack) {
        menu.details = track
    }

    override fun openArtist(artist: PlayerQueueArtist, collapse: PlayerQueueSheetCollapse) {
        val id = artist.id ?: return
        val provider = menu.target?.ref?.provider ?: ContentProvider.YOUTUBE.id
        navigate(ArchiveRoutes.content(ContentRef(provider = provider, kind = ContentKind.ARTIST, id = id)))
    }

    override fun openAlbum(album: PlayerQueueAlbum) {
        val provider = menu.target?.ref?.provider ?: ContentProvider.YOUTUBE.id
        navigate(ArchiveRoutes.content(ContentRef(provider = provider, kind = ContentKind.ALBUM, id = album.id)))
    }

    // Sleep timer, on the one desktop owner.

    override fun startSleepTimer(minutes: Int) = graph.sleepTimer.startSleepTimer(minutes)
    override fun startSleepTimerEndOfSong() = graph.sleepTimer.startSleepTimerEndOfSong()
    override fun clearSleepTimer() = graph.sleepTimer.clearSleepTimer()

    override fun startRadio(track: PlayerQueueTrack) {
        // The shared menu dismisses immediately after this call; keep its selected item across the launch.
        val item = menu.target?.takeIf { it.ref.id == track.mediaId } ?: return
        graph.scope.launch {
            try {
                graph.radio.start(item.ref)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("the radio could not be started: ${failure.javaClass.simpleName}")
            }
        }
    }

    override fun downloadTrack(track: PlayerQueueTrack) {
        val item = menu.target?.takeIf { it.ref.id == track.mediaId } ?: return
        graph.scope.launch {
            try {
                graph.downloads.start(item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("the download could not be started: ${failure.javaClass.simpleName}")
            }
        }
    }

    override fun removeDownload(track: PlayerQueueTrack) {
        val ref = menu.target?.ref?.takeIf { it.id == track.mediaId } ?: return
        graph.scope.launch {
            try {
                graph.downloads.remove(ref.desktopDownloadId())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("the download could not be removed: ${failure.javaClass.simpleName}")
            }
        }
    }

    // Rows hidden on this host: unreachable, but logged rather than silent if a trigger still reaches them.

    override fun setAodMode() = unsupported("setAodMode")
    override fun openEqualizer() = graph.equalizer.openSheet()
    override fun openAcousticQuality(track: PlayerQueueTrack) {
        graph.openAcousticAudit(
            AcousticAuditTarget(track.mediaId, track.title, track.artists.joinToString { it.name }),
        )
    }
    override fun cast(track: PlayerQueueTrack) = unsupported("cast")
    override fun openWithExternalDownloader(url: String) = unsupported("openWithExternalDownloader")
    /** The canvas owner's own resolve; a stale menu target refetches nothing. */
    override suspend fun refetchCanvas(track: PlayerQueueTrack, requireVertical: Boolean): PlayerQueueCanvasRefetchResult {
        val item = menu.target?.takeIf { it.ref.id == track.mediaId }
            ?: return PlayerQueueCanvasRefetchResult.FAILURE
        return canvas.refetch(item, requireVertical)
    }

    /** The dialog mounts the shared picker and intercepts this row, so an owner stub stays unreachable. */
    override fun saveCanvas(track: PlayerQueueTrack) = unsupported("saveCanvas")
    override fun cancelPlaylistLoading() = unsupported("cancelPlaylistLoading")
    override fun retryPlaylistLoading() = unsupported("retryPlaylistLoading")
    override fun reloadFinitePlaylist() = unsupported("reloadFinitePlaylist")
    override fun dismissPlaylistLoadError() = unsupported("dismissPlaylistLoadError")
}

/** The semantic icon binding for the desktop host: the Material icon each menu key maps to. */
@Composable
private fun desktopPlayerQueueIcon(icon: PlayerQueueMenuIcon, tint: Color, modifier: Modifier) {
    val image = when (icon) {
        PlayerQueueMenuIcon.RADIO -> Icons.Filled.Podcasts
        PlayerQueueMenuIcon.SYNC -> Icons.Filled.Sync
        PlayerQueueMenuIcon.SHARE -> Icons.Filled.Share
        PlayerQueueMenuIcon.LINK -> Icons.Filled.Link
        PlayerQueueMenuIcon.TUNE -> Icons.Filled.Tune
        PlayerQueueMenuIcon.BEDTIME -> Icons.Filled.Bedtime
        PlayerQueueMenuIcon.MOTION_PHOTOS -> Icons.Filled.MotionPhotosOn
        PlayerQueueMenuIcon.PLAYLIST_ADD -> Icons.AutoMirrored.Outlined.PlaylistAdd
        PlayerQueueMenuIcon.BOOKMARK -> Icons.Filled.BookmarkBorder
        PlayerQueueMenuIcon.BOOKMARK_FILLED -> Icons.Filled.Bookmark
        PlayerQueueMenuIcon.ARTIST -> Icons.Filled.Person
        PlayerQueueMenuIcon.ALBUM -> Icons.Filled.Album
        PlayerQueueMenuIcon.OFFLINE -> Icons.Filled.OfflinePin
        PlayerQueueMenuIcon.DOWNLOAD -> Icons.Filled.Download
        PlayerQueueMenuIcon.PLAYLIST_PLAY -> Icons.AutoMirrored.Filled.PlaylistPlay
        PlayerQueueMenuIcon.DELETE -> Icons.Filled.Delete
        PlayerQueueMenuIcon.QUEUE_MUSIC -> Icons.AutoMirrored.Filled.QueueMusic
        PlayerQueueMenuIcon.GRAPHIC_EQ -> Icons.Filled.GraphicEq
        PlayerQueueMenuIcon.INFO -> Icons.Filled.Info
        PlayerQueueMenuIcon.EQUALIZER -> Icons.Filled.Equalizer
        PlayerQueueMenuIcon.SPEED -> Icons.Filled.Speed
        PlayerQueueMenuIcon.DISCOVER_TUNE -> Icons.Filled.Tune
        PlayerQueueMenuIcon.EDIT -> Icons.Filled.Edit
        PlayerQueueMenuIcon.REMOVE -> Icons.Filled.Remove
        PlayerQueueMenuIcon.ADD -> Icons.Filled.Add
        PlayerQueueMenuIcon.SKIP_NEXT -> Icons.Filled.SkipNext
        PlayerQueueMenuIcon.CLOSE -> Icons.Filled.Close
        PlayerQueueMenuIcon.MUSIC_NOTE -> Icons.Filled.MusicNote
        PlayerQueueMenuIcon.PROVIDER_GENERIC -> Icons.Filled.MusicNote
        PlayerQueueMenuIcon.SEARCH -> Icons.Filled.Search
        PlayerQueueMenuIcon.PROVIDER_TIDAL -> Icons.Filled.MusicNote
        PlayerQueueMenuIcon.PROVIDER_QOBUZ -> Icons.Filled.MusicNote
        PlayerQueueMenuIcon.PROVIDER_DEEZER -> Icons.Filled.MusicNote
        PlayerQueueMenuIcon.PROVIDER_JIOSAAVN -> Icons.Filled.MusicNote
        PlayerQueueMenuIcon.SPOTIFY -> Icons.Filled.MusicNote
        PlayerQueueMenuIcon.PLAY -> Icons.Filled.PlayArrow
        PlayerQueueMenuIcon.DONE -> Icons.Filled.Check
        PlayerQueueMenuIcon.CAST -> Icons.Filled.Cast
        PlayerQueueMenuIcon.CAST_CONNECTED -> Icons.Filled.CastConnected
    }
    Icon(image, contentDescription = null, tint = tint, modifier = modifier)
}

@Composable
internal fun DesktopPlayerMenuDialog(
    menu: DesktopPlayerMenuState,
    sleepSheet: DesktopSleepTimerSheetState,
    graph: DesktopSessionGraph,
    canvas: DesktopCanvasOwner,
    navigate: (String) -> Unit = {},
) {
    // The pick outlives the menu's dismiss, so the save state and its dialogs mount before the target check.
    val canvasHost = remember(graph, canvas) { DesktopCanvasMenuHost(canvas) { menu.canvasItem } }
    val canvasScope = rememberCoroutineScope()
    val canvasSave = rememberCanvasSaveMenuState(menu.canvasItem?.ref?.id ?: "", canvasHost) { resource ->
        canvasScope.launch { menu.canvasNotice = resource }
    }
    CanvasSourcePicker(canvasSave)
    menu.canvasNotice?.let { resource ->
        AlertDialog(
            onDismissRequest = { menu.canvasNotice = null },
            text = { Text(stringResource(resource)) },
            confirmButton = { TextButton(onClick = { menu.canvasNotice = null }) { Text(DesktopStrings["ok"]) } },
        )
    }
    val item = menu.target ?: return
    val prefs by graph.settings.snapshots.collectAsState()
    val sleep by graph.sleepTimer.state.collectAsState()
    val actions = remember(graph, menu, navigate, canvas) { DesktopPlayerQueueActions(graph, menu, canvas, navigate) }
    val menuActions = remember(actions, canvasSave) {
        object : PlayerQueueMenuActions by actions {
            override fun saveCanvas(track: PlayerQueueTrack) = canvasSave.request()
        }
    }
    val speed by actions.playbackSpeed.collectAsState()
    val pitch by actions.playbackPitch.collectAsState()
    val transfers by produceState(emptyList<ContentDownload>(), graph) {
        try {
            graph.downloads.observe().collect { value = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("download menu state is unavailable: ${failure.javaClass.simpleName}")
        }
    }
    val download = desktopPlayerDownloadState(transfers, item.ref)

    val track = remember(item) { desktopPlayerMenuTrack(item) }
    val canvasTarget by canvas.target.collectAsState()
    val canvasRefetching by canvas.refetching.collectAsState()
    val hasCanvasArtwork by produceState(false, item.ref.id, canvasTarget) {
        value = try {
            graph.customArtwork.canvasUri(item.ref.id) != null ||
                canvasTarget?.occurrenceId?.let { shown ->
                    graph.queue.state.value.entries.firstOrNull { it.occurrenceId == shown }?.item?.ref?.id == item.ref.id
                } == true
        } catch (_: Exception) {
            false
        }
    }
    val snapshot = prefs
    val pin = snapshot?.get(SongSourceOverrideKey)?.let { SongSourceOverride.get(it, track.mediaId) }
    val state = PlayerQueueMenuState(
        style = snapshot?.get(PlayerDesignStyleKey).toEnum(PlayerDesignStyle.Default),
        appleMusicExperience = snapshot?.appleMusicMenus() ?: (PlayerMenuStyle.Default == PlayerMenuStyle.APPLE_MUSIC),
        download = download,
        pinnedToSpeedDial = snapshot?.get(SpeedDialSongIdsKey)?.let { raw ->
            parseSpeedDialPins(raw).any { it.type == SpeedDialPinType.SONG && it.id == track.mediaId }
        } ?: false,
        externalDownloaderEnabled = false,
        canvasFeatureEnabled = snapshot?.canvasPlaybackEnabled() ?: false,
        lowDataModeActive = snapshot?.lowDataMode() ?: false,
        hasCanvasArtwork = hasCanvasArtwork,
        canvasRefetching = canvasRefetching,
        castAvailable = false,
        castConnected = false,
        artistSeparators = snapshot?.get(ArtistSeparatorsKey) ?: "",
        playlistLoad = PlayerQueuePlaylistLoadState(showDialog = false, loading = false, incomplete = false, wholeAction = false),
        playbackSpeed = speed,
        playbackPitch = pitch,
        sleepTimer = sleep,
        sourceChoices = desktopSourceSnapshot(prefs).enabled.toList(),
        selectedSource = pin,
        amazonSearchEnabled = graph.audioSources.amazonSearchEnabled,
        searchableSources = graph.audioSources.searchableSources,
        alwaysOnDisplaySupported = false,
        radioSupported = desktopRadioSeedSupported(item.ref),
        acousticQualitySupported = true,
        equalizerSupported = true,
        // The engine graph has no pitch stage, so the dialog hides the control instead of publishing a no-op value.
        pitchSupported = false,
        downloadsSupported = desktopDownloadSupported(item.ref) || download != PlayerQueueDownloadState.NOT_DOWNLOADED,
        tempoPitchPresets = parseTempoPitchPresets(snapshot?.get(TempoPitchPresetsJsonKey)),
        tempoPitchRemembered = snapshot?.get(TempoPitchRememberedKey) ?: false,
        menuStyle = snapshot?.playerMenuStyle() ?: PlayerMenuStyle.Default,
    )

    Dialog(onDismissRequest = menu::dismiss) {
        PlayerQueueMenu(
            trigger = PlayerQueueMenuTrigger.Player,
            track = track,
            state = state,
            actions = menuActions,
            clock = DesktopPlayerQueueClock,
            icon = ::desktopPlayerQueueIcon,
            artwork = { url, modifier -> DesktopRemoteArtwork(url, graph.artworkLoader, modifier) },
            onDismiss = menu::dismiss,
            modifier = Modifier.widthIn(max = 560.dp).padding(16.dp),
        )
    }

    menu.playlistPick?.let { picked ->
        DesktopPlaylistPickDialog(
            track = picked,
            graph = graph,
            onDone = { menu.playlistPick = null },
        )
    }

    menu.details?.let { shown ->
        DesktopTrackDetailsDialog(
            track = shown,
            item = item,
            onDone = { menu.details = null },
        )
    }

}

/**
 * The add-to-playlist picker: the local playlists of the library plus a create row, then the committed
 * `LibraryMutation.AddToPlaylist` write, as the baseline's AddToPlaylistDialog did.
 */
@Composable
private fun DesktopPlaylistPickDialog(
    track: PlayerQueueTrack,
    graph: DesktopSessionGraph,
    onDone: () -> Unit,
) {
    var playlists by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(track) {
        playlists = graph.libraryContent.playlistRows().first()
            .filter { it.editable }
            .map { it.item.ref.id to it.item.title }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(DesktopStrings["player_menu_add_to_playlist"]) },
        text = {
            Column {
                playlists?.forEach { (id, title) ->
                    ListItem(
                        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth().clickable {
                            commitToPlaylist(graph, id, track, onDone)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                if (creating) {
                    TextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text(DesktopStrings["player_menu_new_playlist"]) },
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    val title = newName.trim()
                                    if (title.isEmpty()) return@TextButton
                                    createAndCommit(graph, title, track, onDone)
                                },
                            ) { Text(DesktopStrings["player_menu_create"]) }
                        },
                    )
                } else {
                    TextButton(
                        onClick = { creating = true },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text(DesktopStrings["player_menu_new_playlist"]) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDone) { Text(DesktopStrings["cancel"]) } },
    )
}

private fun commitToPlaylist(graph: DesktopSessionGraph, playlistId: String, track: PlayerQueueTrack, onDone: () -> Unit) {
    graph.scope.launch {
        try {
            val item = graph.queue.state.value.entries
                .firstOrNull { it.item.ref.id == track.mediaId }?.item
                ?: return@launch onDone()
            graph.library.mutate(
                LibraryMutation.AddToPlaylist(
                    playlistId = playlistId,
                    occurrences = listOf(ContentOccurrence(occurrenceId = menuOccurrenceId(track.mediaId), item = item)),
                ),
            )
            onDone()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("playlist add was not saved: ${failure.javaClass.simpleName}")
        }
    }
}

private fun createAndCommit(graph: DesktopSessionGraph, title: String, track: PlayerQueueTrack, onDone: () -> Unit) {
    graph.scope.launch {
        try {
            val id = randomDesktopPlaylistId()
            graph.libraryContent.createPlaylist(id, title)
            commitToPlaylist(graph, id, track, onDone)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("playlist create was not saved: ${failure.javaClass.simpleName}")
        }
    }
}

/** The track details dialog: the fields the baseline's details sheet listed, from the queue item itself. */
@Composable
private fun DesktopTrackDetailsDialog(track: PlayerQueueTrack, item: ContentItem, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(DesktopStrings["player_menu_details"]) },
        text = {
            Column {
                Text(track.title)
                track.artists.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }?.let { Text(it) }
                track.album?.let { Text(it.title) }
                item.durationMs?.let { Text("%d:%02d".format(it / 60000, (it / 1000) % 60)) }
                Text(item.ref.provider)
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(DesktopStrings["cancel"]) } },
    )
}

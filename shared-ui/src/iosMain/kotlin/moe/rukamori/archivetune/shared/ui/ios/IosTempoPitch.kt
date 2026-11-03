package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticAuditTarget
import moe.rukamori.archivetune.shared.domain.settings.PreferenceEditor
import moe.rukamori.archivetune.shared.domain.settings.PreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.PlaybackPitchKey
import moe.rukamori.archivetune.shared.domain.settings.PlaybackSpeedKey
import moe.rukamori.archivetune.shared.domain.settings.TempoPitchPreset
import moe.rukamori.archivetune.shared.domain.settings.TempoPitchPresetsJsonKey
import moe.rukamori.archivetune.shared.domain.settings.TempoPitchRememberedKey
import moe.rukamori.archivetune.shared.domain.settings.encodeTempoPitchPresets
import moe.rukamori.archivetune.shared.domain.settings.newTempoPitchPresetId
import moe.rukamori.archivetune.shared.domain.settings.parseTempoPitchPresets
import moe.rukamori.archivetune.shared.domain.settings.removeTempoPitchPreset
import moe.rukamori.archivetune.shared.domain.settings.renameTempoPitchPreset
import moe.rukamori.archivetune.shared.domain.settings.upsertTempoPitchPreset
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueAlbum
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueArtist
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueCanvasRefetchResult
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueMenuActions
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueMenuIcon
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueSheetCollapse
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueTempoPitchDialog
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueTrack
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerSourceOverrideRequest
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerSourceSearchResult
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m4_icon_album
import moe.rukamori.archivetune.shared.ui.resources.m4_icon_info
import moe.rukamori.archivetune.shared.ui.resources.m4_icon_link
import moe.rukamori.archivetune.shared.ui.resources.m4_icon_motion_photos_on
import moe.rukamori.archivetune.shared.ui.resources.m4_icon_music_note
import moe.rukamori.archivetune.shared.ui.resources.m4_icon_search
import moe.rukamori.archivetune.shared.ui.resources.m4c_icon_add
import moe.rukamori.archivetune.shared.ui.resources.m4c_icon_close
import moe.rukamori.archivetune.shared.ui.resources.m4c_icon_delete
import moe.rukamori.archivetune.shared.ui.resources.m4c_icon_edit
import moe.rukamori.archivetune.shared.ui.resources.m4hv_icon_discover_tune
import moe.rukamori.archivetune.shared.ui.resources.m4hv_icon_done
import moe.rukamori.archivetune.shared.ui.resources.m4hv_icon_person
import moe.rukamori.archivetune.shared.ui.resources.m4lf_icon_skip_next
import moe.rukamori.archivetune.shared.ui.resources.m6_icon_cast
import moe.rukamori.archivetune.shared.ui.resources.m6_icon_cast_connected
import moe.rukamori.archivetune.shared.ui.resources.m6_icon_sync
import moe.rukamori.archivetune.shared.ui.resources.m6yp_icon_bookmark
import moe.rukamori.archivetune.shared.ui.resources.m6yp_icon_bookmark_filled
import moe.rukamori.archivetune.shared.ui.resources.m6yp_icon_download
import moe.rukamori.archivetune.shared.ui.resources.m6yp_icon_offline
import moe.rukamori.archivetune.shared.ui.resources.m6yp_icon_play
import moe.rukamori.archivetune.shared.ui.resources.m6yp_icon_playlist_add
import moe.rukamori.archivetune.shared.ui.resources.m6yp_icon_playlist_play
import moe.rukamori.archivetune.shared.ui.resources.m6yp_icon_queue_music
import moe.rukamori.archivetune.shared.ui.resources.m6yp_icon_radio
import moe.rukamori.archivetune.shared.ui.resources.m6yp_icon_share
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_graphic_eq
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_speed
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_timer
import moe.rukamori.archivetune.shared.ui.resources.m9_spotify_icon
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** Whether the player's more button or a menu row has the tempo/pitch dialog open. One per host root. */
internal class IosTempoPitchSheetState {
    var open: Boolean by mutableStateOf(false)
}

/**
 * The iOS owner of the shared tempo/pitch dialog's commands. The five commands the dialog uses are real:
 * tempo and pitch reach the engine through the one player — the port shifts pitch through the output
 * graph's time-pitch unit while the engine keeps owning tempo; presets and the remembered flag persist
 * exactly like the desktop owner. The three sleep commands delegate to the session's timer so a preset
 * row never loses them, and every command a dialog this owner renders cannot reach is reported as
 * unconnected, never shown as done.
 */
internal class IosTempoPitchActions(private val graph: IosSessionGraph) : PlayerQueueMenuActions {
    // The applied tempo and pitch of this session, as the baseline's live playback parameters. The
    // engine owns the true tempo; the stored value only seeds a reopened dialog.
    private val appliedSpeed = MutableStateFlow(storedValue(PlaybackSpeedKey))
    private val appliedPitch = MutableStateFlow(storedValue(PlaybackPitchKey))
    val playbackPitch: StateFlow<Float> = appliedPitch.asStateFlow()

    init {
        // With "Remember" on, the stored tempo restores at startup through the one player, as the
        // Android session restores it; the port holds the value until an output exists. Pitch restores
        // through the same player call, which the port holds until its first output too.
        if (graph.settings.snapshots.value?.get(TempoPitchRememberedKey) == true) {
            graph.player.setPlaybackSpeed(appliedSpeed.value)
            graph.player.setPlaybackPitch(appliedPitch.value)
        }
    }

    private fun storedValue(key: PreferenceKey<Float>): Float =
        graph.settings.snapshots.value?.get(key)?.takeIf { it.isFinite() && it > 0f } ?: 1f

    private fun unconnected(command: String) = graph.report("player menu $command has no owner on the iOS host")

    // Tempo and pitch: the baseline's live playback parameters. While the opt-in "remember" flag
    // holds, every applied pair is persisted for the next launch's restore, like the desktop owner.

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
                graph.report("tempo/pitch preference was not saved: ${failure::class.simpleName}")
            }
        }
    }

    // The sleep sheet's three commands stay connected through the session's shared timer.

    override fun startSleepTimer(minutes: Int) = graph.sleepTimer.startSleepTimer(minutes)
    override fun startSleepTimerEndOfSong() = graph.sleepTimer.startSleepTimerEndOfSong()
    override fun clearSleepTimer() = graph.sleepTimer.clearSleepTimer()

    // Every other menu command has no owner on this host: the surfaces that would call them are not
    // mounted here, and answering them as if they had run would lie about the result.

    override fun startRadio(track: PlayerQueueTrack) = unconnected("startRadio")
    override fun playNextFromQueue(occurrenceId: String) = unconnected("playNextFromQueue")
    override fun removeFromQueue(occurrenceId: String) = unconnected("removeFromQueue")
    override fun playNext(track: PlayerQueueTrack) = unconnected("playNext")
    override fun addToQueue(track: PlayerQueueTrack) = unconnected("addToQueue")
    override fun setAodMode() = unconnected("setAodMode")
    override fun copyLinkAtCurrentPosition(url: String) = unconnected("copyLinkAtCurrentPosition")
    override fun openEqualizer() = graph.equalizer.openSheet()
    override fun openAcousticQuality(track: PlayerQueueTrack) = graph.openAcousticAudit(
        AcousticAuditTarget(track.mediaId, track.title, track.artists.joinToString { it.name }),
    )
    override fun cast(track: PlayerQueueTrack) = unconnected("cast")
    override fun refreshSongSources(track: PlayerQueueTrack) = unconnected("refreshSongSources")
    override suspend fun searchSongSources(
        query: String,
        sourceFilter: AudioSourceType?,
        includeAmazon: Boolean,
    ): List<PlayerSourceSearchResult> = emptyList()
    override fun chooseSongSource(track: PlayerQueueTrack, source: AudioSourceType?) = unconnected("chooseSongSource")
    override fun chooseSongSourceSearchResult(track: PlayerQueueTrack, request: PlayerSourceOverrideRequest) =
        unconnected("chooseSongSourceSearchResult")
    override fun playYouTubeSearchResult(track: PlayerQueueTrack, result: PlayerSourceSearchResult) =
        unconnected("playYouTubeSearchResult")
    override fun addToPlaylist(track: PlayerQueueTrack) = unconnected("addToPlaylist")
    override fun toggleSpeedDialPin(track: PlayerQueueTrack) = unconnected("toggleSpeedDialPin")
    override fun downloadTrack(track: PlayerQueueTrack) = unconnected("downloadTrack")
    override fun removeDownload(track: PlayerQueueTrack) = unconnected("removeDownload")
    override fun openWithExternalDownloader(url: String) = unconnected("openWithExternalDownloader")
    override fun copyLink(url: String) = unconnected("copyLink")
    override fun shareLocalAudio(track: PlayerQueueTrack) = unconnected("shareLocalAudio")
    override fun showDetails(track: PlayerQueueTrack) = unconnected("showDetails")
    override fun openArtist(artist: PlayerQueueArtist, collapse: PlayerQueueSheetCollapse) = unconnected("openArtist")
    override fun openAlbum(album: PlayerQueueAlbum) = unconnected("openAlbum")
    override suspend fun refetchCanvas(track: PlayerQueueTrack, requireVertical: Boolean): PlayerQueueCanvasRefetchResult =
        PlayerQueueCanvasRefetchResult.FAILURE
    override fun saveCanvas(track: PlayerQueueTrack) = unconnected("saveCanvas")
    override fun cancelPlaylistLoading() = unconnected("cancelPlaylistLoading")
    override fun retryPlaylistLoading() = unconnected("retryPlaylistLoading")
    override fun reloadFinitePlaylist() = unconnected("reloadFinitePlaylist")
    override fun dismissPlaylistLoadError() = unconnected("dismissPlaylistLoadError")
}

/**
 * The shared tempo/pitch dialog over the root, opened by the song menu's tempo/pitch row or the
 * player's more button. Presets and the remembered flag mirror the stored settings live, and the
 * initial values seed from the playing state once, so a reopened dialog starts where playback is.
 */
@Composable
internal fun IosTempoPitchOverlay(sheet: IosTempoPitchSheetState, graph: IosSessionGraph, actions: IosTempoPitchActions) {
    if (!sheet.open) return
    val preferences by graph.settings.snapshots.collectAsState()
    PlayerQueueTempoPitchDialog(
        initialSpeed = graph.player.snapshot.value?.playbackSpeed?.takeIf { it.isFinite() && it > 0f } ?: 1f,
        initialPitch = actions.playbackPitch.value,
        presets = parseTempoPitchPresets(preferences?.get(TempoPitchPresetsJsonKey)),
        remembered = preferences?.get(TempoPitchRememberedKey) ?: false,
        actions = actions,
        icon = ::iosPlayerQueueIcon,
        onDismiss = { sheet.open = false },
    )
}

/** The host drawable binding of a semantic menu icon; provider marks without an asset fall back to the note. */
@Composable
private fun iosPlayerQueueIcon(icon: PlayerQueueMenuIcon, tint: Color, modifier: Modifier) {
    Icon(painter = painterResource(icon.iosDrawable), contentDescription = null, tint = tint, modifier = modifier)
}

private val PlayerQueueMenuIcon.iosDrawable: DrawableResource
    get() = when (this) {
        PlayerQueueMenuIcon.RADIO -> Res.drawable.m6yp_icon_radio
        PlayerQueueMenuIcon.SYNC -> Res.drawable.m6_icon_sync
        PlayerQueueMenuIcon.SHARE -> Res.drawable.m6yp_icon_share
        PlayerQueueMenuIcon.LINK -> Res.drawable.m4_icon_link
        PlayerQueueMenuIcon.TUNE -> Res.drawable.m4hv_icon_discover_tune
        PlayerQueueMenuIcon.BEDTIME -> Res.drawable.m9_media_info_timer
        PlayerQueueMenuIcon.MOTION_PHOTOS -> Res.drawable.m4_icon_motion_photos_on
        PlayerQueueMenuIcon.PLAYLIST_ADD -> Res.drawable.m6yp_icon_playlist_add
        PlayerQueueMenuIcon.BOOKMARK -> Res.drawable.m6yp_icon_bookmark
        PlayerQueueMenuIcon.BOOKMARK_FILLED -> Res.drawable.m6yp_icon_bookmark_filled
        PlayerQueueMenuIcon.ARTIST -> Res.drawable.m4hv_icon_person
        PlayerQueueMenuIcon.ALBUM -> Res.drawable.m4_icon_album
        PlayerQueueMenuIcon.OFFLINE -> Res.drawable.m6yp_icon_offline
        PlayerQueueMenuIcon.DOWNLOAD -> Res.drawable.m6yp_icon_download
        PlayerQueueMenuIcon.PLAYLIST_PLAY -> Res.drawable.m6yp_icon_playlist_play
        PlayerQueueMenuIcon.DELETE -> Res.drawable.m4c_icon_delete
        PlayerQueueMenuIcon.QUEUE_MUSIC -> Res.drawable.m6yp_icon_queue_music
        PlayerQueueMenuIcon.GRAPHIC_EQ -> Res.drawable.m9_media_info_graphic_eq
        PlayerQueueMenuIcon.INFO -> Res.drawable.m4_icon_info
        PlayerQueueMenuIcon.EQUALIZER -> Res.drawable.m9_media_info_graphic_eq
        PlayerQueueMenuIcon.SPEED -> Res.drawable.m9_media_info_solar_speed
        PlayerQueueMenuIcon.DISCOVER_TUNE -> Res.drawable.m4hv_icon_discover_tune
        PlayerQueueMenuIcon.EDIT -> Res.drawable.m4c_icon_edit
        // No remove mark exists in the host asset set; the close mark reads as the same dismissal.
        PlayerQueueMenuIcon.REMOVE -> Res.drawable.m4c_icon_close
        PlayerQueueMenuIcon.ADD -> Res.drawable.m4c_icon_add
        PlayerQueueMenuIcon.SKIP_NEXT -> Res.drawable.m4lf_icon_skip_next
        PlayerQueueMenuIcon.CLOSE -> Res.drawable.m4c_icon_close
        PlayerQueueMenuIcon.MUSIC_NOTE -> Res.drawable.m4_icon_music_note
        PlayerQueueMenuIcon.PROVIDER_GENERIC -> Res.drawable.m4_icon_music_note
        PlayerQueueMenuIcon.SEARCH -> Res.drawable.m4_icon_search
        PlayerQueueMenuIcon.PROVIDER_TIDAL -> Res.drawable.m4_icon_music_note
        PlayerQueueMenuIcon.PROVIDER_QOBUZ -> Res.drawable.m4_icon_music_note
        PlayerQueueMenuIcon.PROVIDER_DEEZER -> Res.drawable.m4_icon_music_note
        PlayerQueueMenuIcon.PROVIDER_JIOSAAVN -> Res.drawable.m4_icon_music_note
        PlayerQueueMenuIcon.SPOTIFY -> Res.drawable.m9_spotify_icon
        PlayerQueueMenuIcon.PLAY -> Res.drawable.m6yp_icon_play
        PlayerQueueMenuIcon.DONE -> Res.drawable.m4hv_icon_done
        PlayerQueueMenuIcon.CAST -> Res.drawable.m6_icon_cast
        PlayerQueueMenuIcon.CAST_CONNECTED -> Res.drawable.m6_icon_cast_connected
    }

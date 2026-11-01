/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.androidauto.AndroidAutoActionSlot
import moe.rukamori.archivetune.androidauto.AndroidAutoCustomAction
import moe.rukamori.archivetune.androidauto.AndroidAutoPinnedPlaylistKind
import moe.rukamori.archivetune.androidauto.AndroidAutoPlaylistOption
import moe.rukamori.archivetune.androidauto.AndroidAutoPlaylistPickerState
import moe.rukamori.archivetune.androidauto.AndroidAutoSettingsSnapshot
import moe.rukamori.archivetune.androidauto.AndroidAutoSettingsUseCases
import moe.rukamori.archivetune.constants.PlaylistSortType
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.utils.completed
import timber.log.Timber
import javax.inject.Inject

@Immutable
sealed interface AndroidAutoSettingsState {
    data object Loading : AndroidAutoSettingsState
    data class Success(val model: AndroidAutoSettingsUiModel) : AndroidAutoSettingsState
    data object Empty : AndroidAutoSettingsState
    data class Error(@param:StringRes val messageRes: Int) : AndroidAutoSettingsState
}

@Immutable
data class AndroidAutoSettingsUiModel(
    val snapshot: AndroidAutoSettingsSnapshot,
    val busy: Boolean,
    val picker: AndroidAutoPlaylistPickerState,
)

sealed interface AndroidAutoSettingsAction {
    data class SetOnlineRecommendations(val enabled: Boolean) : AndroidAutoSettingsAction
    data class SetOnlineVoiceSearch(val enabled: Boolean) : AndroidAutoSettingsAction
    data class SetLocalSongs(val enabled: Boolean) : AndroidAutoSettingsAction
    data class SetMeteredPlayback(val enabled: Boolean) : AndroidAutoSettingsAction
    data class SetMeteredArtwork(val enabled: Boolean) : AndroidAutoSettingsAction
    data class SetAction(
        val slot: AndroidAutoActionSlot,
        val action: AndroidAutoCustomAction,
    ) : AndroidAutoSettingsAction
    data object RequestAudioPermission : AndroidAutoSettingsAction
    data object OpenAppPermissions : AndroidAutoSettingsAction
    data object ExternalActionFailed : AndroidAutoSettingsAction
    data object PermissionResult : AndroidAutoSettingsAction
    data object Retry : AndroidAutoSettingsAction
    data object LoadPlaylistCandidates : AndroidAutoSettingsAction
    data class TogglePinnedPlaylist(val option: AndroidAutoPlaylistOption) : AndroidAutoSettingsAction
    data class SetPinnedPlayInOrder(val playInOrder: Boolean) : AndroidAutoSettingsAction
}

sealed interface AndroidAutoSettingsEvent {
    data object RequestAudioPermission : AndroidAutoSettingsEvent
    data object OpenAppPermissions : AndroidAutoSettingsEvent
}

@HiltViewModel
class AndroidAutoSettingsViewModel @Inject constructor(
    private val useCases: AndroidAutoSettingsUseCases,
    private val database: MusicDatabase,
) : ViewModel() {
    private data class Controls(
        val busy: Boolean = false,
        @param:StringRes val error: Int? = null,
    )

    private val controls = MutableStateFlow(Controls())
    private val permissionVersion = MutableStateFlow(0L)
    private val pickerState = MutableStateFlow(AndroidAutoPlaylistPickerState())
    private val eventChannel = Channel<AndroidAutoSettingsEvent>(Channel.BUFFERED)
    private var updateJob: Job? = null
    private var candidatesJob: Job? = null

    val events = eventChannel.receiveAsFlow()

    val state = combine(
        useCases.observeSettings(),
        controls,
        permissionVersion,
        pickerState,
    ) { snapshot, controls, _, picker ->
        controls.error?.let(AndroidAutoSettingsState::Error)
            ?: AndroidAutoSettingsState.Success(
                AndroidAutoSettingsUiModel(
                    snapshot = snapshot.copy(hasLocalAudioPermission = useCases.hasLocalAudioPermission()),
                    busy = controls.busy,
                    picker = picker,
                ),
            )
    }.catch { error ->
        if (error is CancellationException) throw error
        Timber.e(error, "Failed to observe Android Auto settings")
        emit(AndroidAutoSettingsState.Error(R.string.android_auto_settings_load_failed))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AndroidAutoSettingsState.Loading)

    fun onAction(action: AndroidAutoSettingsAction) {
        when (action) {
            AndroidAutoSettingsAction.RequestAudioPermission ->
                eventChannel.trySend(AndroidAutoSettingsEvent.RequestAudioPermission)
            AndroidAutoSettingsAction.OpenAppPermissions ->
                eventChannel.trySend(AndroidAutoSettingsEvent.OpenAppPermissions)
            AndroidAutoSettingsAction.ExternalActionFailed -> controls.update {
                it.copy(error = R.string.open_app_settings_error)
            }
            AndroidAutoSettingsAction.PermissionResult -> permissionVersion.update { it + 1 }
            AndroidAutoSettingsAction.Retry -> controls.update { it.copy(error = null) }
            AndroidAutoSettingsAction.LoadPlaylistCandidates -> loadPlaylistCandidates()
            else -> update(action)
        }
    }

    /**
     * Fills the driving-playlist picker once per open: local library playlists first, then the
     * signed-in account's YouTube playlists. An offline or signed-out YouTube fetch only drops
     * the online section — it is not an error for the picker itself.
     */
    private fun loadPlaylistCandidates() {
        if (candidatesJob?.isActive == true) return
        candidatesJob = viewModelScope.launch {
            pickerState.update { it.copy(loading = true, failed = false) }
            try {
                val local =
                    withContext(Dispatchers.IO) {
                        database.playlists(PlaylistSortType.NAME, descending = false).first()
                    }.map { playlist ->
                        AndroidAutoPlaylistOption(
                            kind = AndroidAutoPinnedPlaylistKind.LOCAL,
                            id = playlist.id,
                            title = playlist.playlist.name,
                            songCount = playlist.songCount,
                            subtitle = null,
                        )
                    }
                var onlineFailed = false
                val online =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            YouTube
                                .library("FEmusic_liked_playlists")
                                .completed()
                                .getOrThrow()
                                .items
                                .filterIsInstance<PlaylistItem>()
                                .filterNot { it.id == "SE" }
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        Timber.w(error, "Failed to load YouTube playlists for driving playlists picker")
                        onlineFailed = true
                    }.getOrDefault(emptyList())
                        .map { playlist ->
                            AndroidAutoPlaylistOption(
                                kind = AndroidAutoPinnedPlaylistKind.ONLINE,
                                id = playlist.id,
                                title = playlist.title,
                                songCount = null,
                                subtitle = playlist.songCountText ?: playlist.author?.name,
                            )
                        }
                pickerState.update {
                    it.copy(
                        options = local + online,
                        loading = false,
                        failed = onlineFailed && local.isEmpty(),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.e(error, "Failed to load driving playlist candidates")
                pickerState.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    private fun update(action: AndroidAutoSettingsAction) {
        if (updateJob?.isActive == true) return
        val model = (state.value as? AndroidAutoSettingsState.Success)?.model ?: return
        controls.update { it.copy(busy = true, error = null) }
        updateJob = viewModelScope.launch {
            try {
                when (action) {
                    is AndroidAutoSettingsAction.SetOnlineRecommendations ->
                        useCases.setOnlineRecommendations(action.enabled)
                    is AndroidAutoSettingsAction.SetOnlineVoiceSearch -> useCases.setOnlineVoiceSearch(action.enabled)
                    is AndroidAutoSettingsAction.SetLocalSongs -> useCases.setLocalSongs(action.enabled)
                    is AndroidAutoSettingsAction.SetMeteredPlayback -> useCases.setMeteredPlayback(action.enabled)
                    is AndroidAutoSettingsAction.SetMeteredArtwork -> useCases.setMeteredArtwork(action.enabled)
                    is AndroidAutoSettingsAction.SetAction ->
                        useCases.setAction(action.slot, action.action, model.snapshot.configuration)
                    is AndroidAutoSettingsAction.TogglePinnedPlaylist ->
                        useCases.togglePinnedPlaylist(action.option.asPin())
                    is AndroidAutoSettingsAction.SetPinnedPlayInOrder ->
                        useCases.setPinnedPlaylistShuffle(!action.playInOrder)
                    else -> Unit
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.e(error, "Android Auto settings action failed")
                controls.update { it.copy(error = R.string.android_auto_settings_update_failed) }
            } finally {
                controls.update { it.copy(busy = false) }
            }
        }
    }
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.androidauto

import androidx.compose.runtime.Immutable

enum class AndroidAutoCustomAction {
    LIKE,
    START_RADIO,
    SHUFFLE,
    REPEAT,
    NONE,
    ;

    companion object {
        fun fromPreference(value: String?, defaultValue: AndroidAutoCustomAction): AndroidAutoCustomAction =
            entries.firstOrNull { it.name == value } ?: defaultValue
    }
}

enum class AndroidAutoActionSlot { PRIMARY, SECONDARY }

enum class AndroidAutoConnectionStatus { DISCONNECTED, PROJECTION, NATIVE }

@Immutable
data class AndroidAutoConfiguration(
    val onlineRecommendations: Boolean = true,
    val onlineVoiceSearch: Boolean = true,
    val localSongs: Boolean = true,
    val meteredPlayback: Boolean = true,
    val meteredArtwork: Boolean = true,
    val primaryAction: AndroidAutoCustomAction = AndroidAutoCustomAction.LIKE,
    val secondaryAction: AndroidAutoCustomAction = AndroidAutoCustomAction.START_RADIO,
    val pinnedPlaylistShuffle: Boolean = false,
)

@Immutable
data class AndroidAutoNetworkState(
    val online: Boolean = false,
    val metered: Boolean = true,
)

@Immutable
data class AndroidAutoSettingsSnapshot(
    val configuration: AndroidAutoConfiguration,
    val connectionStatus: AndroidAutoConnectionStatus,
    val hasLocalAudioPermission: Boolean,
    val pinnedPlaylists: List<AndroidAutoPinnedPlaylist> = emptyList(),
)

/**
 * One row in the driving-playlists picker: a local library playlist or a YouTube (online)
 * account playlist that can be pinned to the car home screen.
 */
@Immutable
data class AndroidAutoPlaylistOption(
    val kind: AndroidAutoPinnedPlaylistKind,
    val id: String,
    val title: String,
    val songCount: Int?,
    val subtitle: String?,
) {
    fun asPin(): AndroidAutoPinnedPlaylist = AndroidAutoPinnedPlaylist(kind, id)
}

@Immutable
data class AndroidAutoPlaylistPickerState(
    val options: List<AndroidAutoPlaylistOption> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

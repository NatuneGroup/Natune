package moe.rukamori.archivetune.constants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Android Auto driving playlists: up to [moe.rukamori.archivetune.androidauto.AndroidAutoPinnedPlaylists.MAX_PINNED_PLAYLISTS]
 * pinned playlists shown first on the car browse screen, pinned order preserved.
 * The shuffle flag selects the pinned-playlist playback mode (false = shuffle,
 * true = play in order).
 */
val AndroidAutoPinnedPlaylistsJsonKey = stringPreferencesKey("androidAutoPinnedPlaylistsJson")
val AndroidAutoPinnedPlaylistShuffleKey = booleanPreferencesKey("androidAutoPinnedPlaylistShuffle")

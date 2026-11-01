package moe.rukamori.archivetune.spotify

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

// The Android DataStore mirrors shared-domain PersistedPreferenceKeys by name, so the dual-playback
// writes either side makes are visible to both. The engine choice is stored for the shared settings
// surface; the product observations back the Librespot Premium gate.
internal val SpotifyProductKey = stringPreferencesKey("spotify_product")
internal val SpotifyProductCheckedAtKey = longPreferencesKey("spotify_product_checked_at")
internal val SpotifyStreamEngineKey = stringPreferencesKey("spotifyStreamEngine")
internal val SpotifyStreamProductKey = stringPreferencesKey("spotifyStreamProduct")
internal val SpotifyStreamProductCheckedAtKey = longPreferencesKey("spotifyStreamProductCheckedAt")

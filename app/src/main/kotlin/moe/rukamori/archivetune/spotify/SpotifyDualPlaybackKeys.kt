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

// The Librespot OAuth grant. It binds the library account, so the names live in the catalog family;
// the refresh token alone is the session — the access token is a cache for it.
internal val SpotifyLibrespotRefreshTokenKey = stringPreferencesKey("spotify_librespot_refresh_token")
internal val SpotifyLibrespotAccessTokenKey = stringPreferencesKey("spotify_librespot_access_token")
internal val SpotifyLibrespotAccessTokenExpiresAtKey = longPreferencesKey("spotify_librespot_access_token_expires_at")
internal val SpotifyLibrespotUserIdKey = stringPreferencesKey("spotify_librespot_user_id")
internal val SpotifyLibrespotAccountNameKey = stringPreferencesKey("spotify_librespot_account_name")

internal val SpotifyLibrespotCatalogSpDcKey = stringPreferencesKey("spotify_librespot_catalog_sp_dc")

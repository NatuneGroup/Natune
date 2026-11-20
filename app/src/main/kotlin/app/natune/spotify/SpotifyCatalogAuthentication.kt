package app.natune.spotify

import android.content.Context
import kotlinx.coroutines.flow.first
import app.natune.R
import app.natune.constants.SpotifyAccessTokenKey
import app.natune.constants.SpotifyAccessTokenExpiresAtKey
import app.natune.constants.SpotifySpDcKey
import app.natune.constants.SpotifySpKeyKey
import app.natune.shared.domain.source.SpotifyProviderIds
import app.natune.utils.dataStore

/** Catalog reads use the catalog account only; playback token owners keep their own guards. */
internal suspend fun ensureSpotifyCatalogAuthenticated(context: Context, refresh: suspend (String, String) -> Unit) {
    val prefs = context.dataStore.data.first()
    check(SpotifyProviderPreferences.enabled(SpotifyProviderIds.CATALOG, prefs)) { context.getString(R.string.extensions_disabled) }
    val token = prefs[SpotifyAccessTokenKey].orEmpty()
    val expiresAt = prefs[SpotifyAccessTokenExpiresAtKey] ?: 0L
    if (token.isNotBlank() && expiresAt > System.currentTimeMillis() + 60_000L) {
        Spotify.accessToken = token
        return
    }
    val spDc = prefs[SpotifySpDcKey].orEmpty()
    if (spDc.isBlank()) throw IllegalStateException(context.getString(R.string.spotify_not_connected))
    refresh(spDc, prefs[SpotifySpKeyKey].orEmpty())
}

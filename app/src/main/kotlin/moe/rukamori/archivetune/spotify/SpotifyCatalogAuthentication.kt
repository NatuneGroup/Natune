package moe.rukamori.archivetune.spotify

import android.content.Context
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.SpotifyAccessTokenKey
import moe.rukamori.archivetune.constants.SpotifyAccessTokenExpiresAtKey
import moe.rukamori.archivetune.constants.SpotifySpDcKey
import moe.rukamori.archivetune.constants.SpotifySpKeyKey
import moe.rukamori.archivetune.shared.domain.source.SpotifyProviderIds
import moe.rukamori.archivetune.utils.dataStore

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

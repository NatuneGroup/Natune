package app.natune.shared.ui.ios

import io.ktor.client.HttpClient
import app.natune.shared.data.provider.KtorSpotifyAuth
import app.natune.shared.domain.settings.*
import app.natune.shared.ui.settings.VaultSettingsStore

/** Catalog-owned lookup token; fresh minting here is read-only and never mutates a playback account. */
internal suspend fun iosCatalogLyricsToken(settings: VaultSettingsStore, http: HttpClient): String {
    val prefs = settings.snapshot()
    val cookie = prefs[SpotifySpDcKey]?.takeIf { it.isNotBlank() } ?: error("Spotify catalog account required")
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val cached = prefs[SpotifyAccessTokenKey]?.takeIf { (prefs[SpotifyAccessTokenExpiresAtKey] ?: 0L) > now }
    val token = cached ?: KtorSpotifyAuth(http).fetchAccessToken(cookie, prefs[SpotifySpKeyKey].orEmpty(), { now })
        .getOrThrow().takeUnless { it.isAnonymous }?.accessToken ?: error("Spotify catalog account required")
    check(settings.snapshots.value?.get(SpotifySpDcKey) == cookie) { "Spotify catalog account changed" }
    return token
}

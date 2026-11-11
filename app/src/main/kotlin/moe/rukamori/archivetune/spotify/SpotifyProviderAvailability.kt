package moe.rukamori.archivetune.spotify

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import moe.rukamori.archivetune.constants.DefaultSearchSourceKey
import moe.rukamori.archivetune.constants.SearchProvider
import moe.rukamori.archivetune.constants.SearchSource
import moe.rukamori.archivetune.constants.SpotifyAccessTokenKey
import moe.rukamori.archivetune.constants.SpotifySpDcKey
import moe.rukamori.archivetune.shared.domain.source.SpotifyProvider
import moe.rukamori.archivetune.shared.domain.source.SpotifyProviderPolicy
import moe.rukamori.archivetune.shared.domain.source.SpotifyProviderSnapshot
import moe.rukamori.archivetune.utils.PreferenceSnapshot
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreferenceSnapshot

@Composable
fun rememberSpotifyCatalogEnabled(): Boolean = rememberPreferenceSnapshot().spotifyCatalogEnabled()

@Composable
fun rememberAvailableSearchProvider(): Pair<SearchProvider, (SearchSource, SearchProvider) -> Unit> {
    var stored by rememberEnumPreference(DefaultSearchSourceKey, SearchProvider.YOUTUBE)
    val enabled = rememberSpotifyCatalogEnabled()
    val effective = if (stored == SearchProvider.SPOTIFY && !enabled) SearchProvider.YOUTUBE else stored
    return effective to { _, provider -> stored = provider }
}

fun PreferenceSnapshot.spotifyCatalogEnabled(): Boolean = SpotifyProviderPreferences.enabled(SpotifyProvider.CATALOG.id, current)

fun Context.spotifyCatalogEnabled(): Boolean = SpotifyProviderPolicy.enabled(SpotifyProvider.CATALOG, SpotifyProviderSnapshot(
    catalogEnabled = dataStore[SpotifyCatalogEnabledKey],
    catalogCookiePresent = dataStore.get(SpotifySpDcKey, "").isNotBlank(),
    catalogTokenPresent = dataStore.get(SpotifyAccessTokenKey, "").isNotBlank(),
))

/** Same session/effective-engine gate as playback; it does not probe the native engine or account. */
@Composable
fun rememberSpotifyPlaybackAvailable(): Boolean = SpotifyProviderPreferences.playbackAvailable(rememberPreferenceSnapshot().current)

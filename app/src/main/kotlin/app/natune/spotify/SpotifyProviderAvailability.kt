package app.natune.spotify

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.natune.constants.DefaultSearchSourceKey
import app.natune.constants.SearchProvider
import app.natune.constants.SearchSource
import app.natune.constants.SpotifyAccessTokenKey
import app.natune.constants.SpotifySpDcKey
import app.natune.shared.domain.source.SpotifyProvider
import app.natune.shared.domain.source.SpotifyProviderPolicy
import app.natune.shared.domain.source.SpotifyProviderSnapshot
import app.natune.utils.PreferenceSnapshot
import app.natune.utils.dataStore
import app.natune.utils.get
import app.natune.utils.rememberEnumPreference
import app.natune.utils.rememberPreferenceSnapshot

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

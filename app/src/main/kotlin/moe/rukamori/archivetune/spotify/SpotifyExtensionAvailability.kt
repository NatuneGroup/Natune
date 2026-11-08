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
import moe.rukamori.archivetune.constants.SpotifyStreamSpDcKey
import moe.rukamori.archivetune.constants.SpotifyStreamEnabledKey
import moe.rukamori.archivetune.modules.SpotifyMetadataExtensionEnabledKey
import moe.rukamori.archivetune.modules.SpotifyAlternateExtensionEnabledKey
import moe.rukamori.archivetune.modules.SpotifyOfficialExtensionEnabledKey
import moe.rukamori.archivetune.modules.NativeExtensionIds
import moe.rukamori.archivetune.modules.NativeExtensionPolicy
import moe.rukamori.archivetune.shared.domain.source.SpotifyStreamEngine
import moe.rukamori.archivetune.shared.domain.source.SpotifyPremiumPolicy
import moe.rukamori.archivetune.shared.domain.source.SpotifyProduct
import moe.rukamori.archivetune.utils.PreferenceSnapshot
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreferenceSnapshot

@Composable
fun rememberSpotifyMetadataExtensionEnabled(): Boolean {
    return NativeExtensionPolicy.enabled(NativeExtensionIds.SPOTIFY_METADATA, rememberPreferenceSnapshot().current)
}

@Composable
fun rememberAvailableSearchProvider(): Pair<SearchProvider, (SearchSource, SearchProvider) -> Unit> {
    var stored by rememberEnumPreference(DefaultSearchSourceKey, SearchProvider.YOUTUBE)
    val enabled = rememberSpotifyMetadataExtensionEnabled()
    val effective = if (stored == SearchProvider.SPOTIFY && !enabled) SearchProvider.YOUTUBE else stored
    return effective to { _, provider -> stored = provider }
}

fun PreferenceSnapshot.spotifyMetadataExtensionEnabled(): Boolean = this[SpotifyMetadataExtensionEnabledKey,
    this[SpotifySpDcKey, ""].isNotBlank() || this[SpotifyAccessTokenKey, ""].isNotBlank()]

fun Context.spotifyMetadataExtensionEnabled(): Boolean = dataStore.get(SpotifyMetadataExtensionEnabledKey,
    dataStore.get(SpotifySpDcKey, "").isNotBlank() || dataStore.get(SpotifyAccessTokenKey, "").isNotBlank())

/** Mirrors the existing effective-engine source gate without doing a network/account probe. */
@Composable
fun rememberSpotifyPlaybackExtensionAvailable(): Boolean {
    val prefs = rememberPreferenceSnapshot()
    val stored = SpotifyStreamEngine.fromPreference(prefs[SpotifyStreamEngineKey, ""])
    val product = if (stored == SpotifyStreamEngine.LIBRESPOT) prefs[SpotifyProductKey, ""] else prefs[SpotifyStreamProductKey, ""]
    return when (SpotifyPremiumPolicy.effectiveEngine(stored, SpotifyProduct.of(product))) {
        SpotifyStreamEngine.UNOFFICIAL -> prefs[SpotifyStreamSpDcKey, ""].isNotBlank() &&
            prefs[SpotifyAlternateExtensionEnabledKey, prefs[SpotifyStreamSpDcKey, ""].isNotBlank() || prefs[SpotifyStreamEnabledKey, false]]
        SpotifyStreamEngine.LIBRESPOT -> prefs[SpotifyLibrespotRefreshTokenKey, ""].isNotBlank() &&
            prefs[SpotifyOfficialExtensionEnabledKey, prefs[SpotifyLibrespotRefreshTokenKey, ""].isNotBlank() || prefs[SpotifyStreamEnabledKey, false]]
    }
}

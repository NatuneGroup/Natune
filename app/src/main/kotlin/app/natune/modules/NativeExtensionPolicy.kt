package app.natune.modules

import app.natune.shared.domain.source.SpotifyProviderIds
import app.natune.spotify.SpotifyAlternateEnabledKey
import app.natune.spotify.SpotifyCatalogEnabledKey
import app.natune.spotify.SpotifyLibrespotEnabledKey
import app.natune.spotify.SpotifyProviderPreferences

// Compatibility surface for registry clients; ordinary Spotify code owns the keys and policy.
val SpotifyMetadataExtensionEnabledKey = SpotifyCatalogEnabledKey
val SpotifyAlternateExtensionEnabledKey = SpotifyAlternateEnabledKey
val SpotifyOfficialExtensionEnabledKey = SpotifyLibrespotEnabledKey

typealias NativeExtensionPolicy = SpotifyProviderPreferences

object NativeExtensionIds {
    const val SPOTIFY_METADATA = SpotifyProviderIds.CATALOG
    const val SPOTIFY_ALTERNATE = SpotifyProviderIds.ALTERNATE
    const val SPOTIFY_OFFICIAL = SpotifyProviderIds.LIBRESPOT
}

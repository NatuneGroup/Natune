package moe.rukamori.archivetune.modules

import moe.rukamori.archivetune.shared.domain.source.SpotifyProviderIds
import moe.rukamori.archivetune.spotify.SpotifyAlternateEnabledKey
import moe.rukamori.archivetune.spotify.SpotifyCatalogEnabledKey
import moe.rukamori.archivetune.spotify.SpotifyLibrespotEnabledKey
import moe.rukamori.archivetune.spotify.SpotifyProviderPreferences

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

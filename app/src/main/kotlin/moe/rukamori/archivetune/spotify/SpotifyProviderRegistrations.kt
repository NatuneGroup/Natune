package moe.rukamori.archivetune.spotify

import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.shared.domain.provider.CompiledProviderDescriptor
import moe.rukamori.archivetune.shared.domain.provider.CompiledProviderId
import moe.rukamori.archivetune.shared.domain.provider.ProviderPlatform
import moe.rukamori.archivetune.shared.domain.provider.ProviderPurpose
import moe.rukamori.archivetune.shared.domain.provider.ProviderSettingsDestination
import moe.rukamori.archivetune.shared.domain.provider.ProviderSettingsRoute

/** The Android registry adapts these ordinary built-in descriptors to its temporary manager UI. */
object SpotifyProviderRegistrations {
    val descriptors: List<CompiledProviderDescriptor> = listOf(
        CompiledProviderDescriptor(CompiledProviderId.SPOTIFY_CATALOG, "extensions_spotify_metadata",
            setOf(ProviderPurpose.CATALOG), setOf(ProviderPlatform.ANDROID),
            ProviderSettingsRoute(ProviderSettingsDestination.SPOTIFY_CATALOG, "extensions_spotify_metadata")),
        CompiledProviderDescriptor(CompiledProviderId.SPOTIFY_ALTERNATE, "extensions_spotify_alternate",
            setOf(ProviderPurpose.PLAYBACK), setOf(ProviderPlatform.ANDROID),
            ProviderSettingsRoute(ProviderSettingsDestination.SPOTIFY_STREAMING, "extension_alternate")),
        CompiledProviderDescriptor(CompiledProviderId.SPOTIFY_LIBRESPOT, "extensions_spotify_official",
            setOf(ProviderPurpose.PLAYBACK), setOf(ProviderPlatform.ANDROID),
            ProviderSettingsRoute(ProviderSettingsDestination.SPOTIFY_STREAMING, "extension_official")),
    )

    fun nameResource(provider: CompiledProviderId): Int = when (provider) {
        CompiledProviderId.SPOTIFY_CATALOG -> R.string.extensions_spotify_metadata
        CompiledProviderId.SPOTIFY_ALTERNATE -> R.string.extensions_spotify_alternate
        CompiledProviderId.SPOTIFY_LIBRESPOT -> R.string.extensions_spotify_official
        else -> error("Not an Android Spotify registration")
    }

    fun settingsPath(route: ProviderSettingsRoute): String {
        val path = when (route.destination) {
            ProviderSettingsDestination.SPOTIFY_CATALOG -> "settings/spotify"
            ProviderSettingsDestination.SPOTIFY_STREAMING -> "settings/spotify_streaming"
            else -> error("Not a Spotify settings destination")
        }
        return path + route.anchor?.let { "?scrollTo=$it" }.orEmpty()
    }
}

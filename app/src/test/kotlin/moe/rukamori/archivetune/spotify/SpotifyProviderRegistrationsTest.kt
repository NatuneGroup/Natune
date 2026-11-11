package moe.rukamori.archivetune.spotify

import moe.rukamori.archivetune.shared.domain.provider.ProviderPlatform
import moe.rukamori.archivetune.shared.domain.provider.ProviderPurpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpotifyProviderRegistrationsTest {
    @Test fun registryAdapterKeepsTheThreeNativeIdsPurposesAndSettingsAnchors() {
        val descriptors = SpotifyProviderRegistrations.descriptors
        assertEquals(listOf("app.natune.native.spotify.metadata", "app.natune.native.spotify.alternate",
            "app.natune.native.spotify.librespot"), descriptors.map { it.id.stableId })
        assertEquals(listOf(setOf(ProviderPurpose.CATALOG), setOf(ProviderPurpose.PLAYBACK), setOf(ProviderPurpose.PLAYBACK)),
            descriptors.map { it.purposes })
        assertEquals(listOf("settings/spotify?scrollTo=extensions_spotify_metadata",
            "settings/spotify_streaming?scrollTo=extension_alternate", "settings/spotify_streaming?scrollTo=extension_official"),
            descriptors.map { SpotifyProviderRegistrations.settingsPath(it.settingsRoute) })
        descriptors.forEach {
            assertEquals(setOf(ProviderPlatform.ANDROID), it.platforms)
            assertFalse(ProviderPurpose.DOWNLOAD in it.purposes)
        }
    }
}

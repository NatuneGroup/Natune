package moe.rukamori.archivetune.shared.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import moe.rukamori.archivetune.shared.domain.platform.PlatformSecret
import moe.rukamori.archivetune.shared.domain.platform.PrivateCookie
import moe.rukamori.archivetune.shared.ui.home.HomeSource

class SettingsArrangePolicyTest {
    @Test fun blankArtworkOrderIsTheDefaultOrder() {
        assertEquals(DefaultArtworkProviderOrder, deserializeArtworkProviderOrder(null))
        assertEquals(DefaultArtworkProviderOrder, deserializeArtworkProviderOrder(""))
        assertEquals(DefaultArtworkProviderOrder, deserializeArtworkProviderOrder("   "))
    }

    @Test fun artworkOrderDropsUnknownAndRepeatsAndAppendsMissing() {
        val stored = "TIDAL,unknown,TIDAL, LOCAL_EMBEDDED "
        assertEquals(
            listOf(
                PreferredArtworkProvider.TIDAL,
                PreferredArtworkProvider.LOCAL_EMBEDDED,
                PreferredArtworkProvider.ORIGINAL_METADATA,
                PreferredArtworkProvider.SPOTIFY_CANVAS,
                PreferredArtworkProvider.ARCHIVETUNE_CANVAS,
            ),
            deserializeArtworkProviderOrder(stored),
        )
    }

    @Test fun artworkOrderRoundTrips() {
        val order = listOf(PreferredArtworkProvider.SPOTIFY_CANVAS, PreferredArtworkProvider.TIDAL)
        assertEquals(
            listOf(
                PreferredArtworkProvider.SPOTIFY_CANVAS,
                PreferredArtworkProvider.TIDAL,
                PreferredArtworkProvider.LOCAL_EMBEDDED,
                PreferredArtworkProvider.ORIGINAL_METADATA,
                PreferredArtworkProvider.ARCHIVETUNE_CANVAS,
            ),
            deserializeArtworkProviderOrder(order.serializeArtworkProviderOrder()),
        )
    }

    @Test fun tagOrderDropsUnknownIdsAndAppendsNewTags() {
        val available = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), "b, gone ,a,b".toPlaylistTagOrder(available))
        assertEquals(available, "".toPlaylistTagOrder(available))
    }

    @Test fun tagPreferenceRoundTripsWithoutRepeats() {
        val available = listOf("a", "b", "c")
        assertEquals(listOf("c", "a", "b"), listOf("c", "a").toPlaylistTagPreference().toPlaylistTagOrder(available))
    }

    @Test fun homeSourcesParseCaseInsensitivelyAndDistinctly() {
        assertEquals(
            listOf(HomeSource.SPOTIFY, HomeSource.YOUTUBE),
            homeSourcesFromPreference(" spotify ,SPOTIFY,youtube,bogus"),
        )
        assertEquals(emptyList(), homeSourcesFromPreference(""))
    }

    @Test fun canonicalHomeSourcesKeepEnumOrder() {
        assertEquals(
            listOf(HomeSource.YOUTUBE, HomeSource.QQ),
            canonicalHomeSources(listOf(HomeSource.QQ, HomeSource.YOUTUBE)),
        )
    }

    @Test fun theMostSpecificCookieDomainWins() {
        val shared = cookie("sp_dc", "shared", ".spotify.com")
        val hostOnly = cookie("sp_dc", "host", "open.spotify.com")
        assertEquals("host", preferredCookieValue(listOf(shared, hostOnly), "sp_dc"))
        assertEquals("host", preferredCookieValue(listOf(hostOnly, shared), "sp_dc"))
        assertEquals("shared", preferredCookieValue(listOf(shared), "sp_dc"))
        assertEquals("", preferredCookieValue(listOf(shared), "sp_key"))
    }

    private fun cookie(name: String, value: String, domain: String) = PrivateCookie(
        name = name,
        value = PlatformSecret(value),
        domain = domain,
        path = "/",
        expiresAtUnixMillis = null,
        secure = true,
        httpOnly = true,
        sameSite = null,
    )
}

package moe.rukamori.archivetune.shared.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Build-only endpoint resolution for Lyprio: the bundled pair baked into the build is the only
 * endpoint the provider can have. A missing or malformed value leaves Lyprio unconfigured — the
 * provider is then never created and never called.
 */
class LyprioBuildEndpointTest {

    @Test
    fun validBuildPairResolvesTheBundledEndpoint() {
        assertEquals(BUILD_URL, resolveLyprioEndpoint(BUILD_URL, BUILD_KEY)?.baseUrl)
        // Trailing slashes and surrounding whitespace still normalize.
        assertEquals(BUILD_URL, resolveLyprioEndpoint("$BUILD_URL/", BUILD_KEY)?.baseUrl)
        assertEquals(BUILD_URL, resolveLyprioEndpoint("  $BUILD_URL  ", "  $BUILD_KEY  ")?.baseUrl)
    }

    @Test
    fun missingBuildValueLeavesTheProviderUnconfigured() {
        assertNull(resolveLyprioEndpoint(BUILD_URL, ""))
        assertNull(resolveLyprioEndpoint("", BUILD_KEY))
        assertNull(resolveLyprioEndpoint("", ""))
        assertNull(resolveLyprioEndpoint("   ", "   "))
    }

    @Test
    fun malformedBuildValuesAreRejected() {
        assertNull(resolveLyprioEndpoint("notaurl", BUILD_KEY))
        assertNull(resolveLyprioEndpoint("https://", BUILD_KEY))
        assertNull(resolveLyprioEndpoint(BUILD_URL, "%%%not-base64%%%"))
        // Valid base64 of the wrong length is not a Lyprio signing key either.
        assertNull(resolveLyprioEndpoint(BUILD_URL, "AAAA"))
    }

    @Test
    fun onlyTheBundledServerShapeParses() {
        // Metrolist's bundled endpoint is HTTPS on port 443 with no credentials embedded in the URL.
        assertNull(resolveLyprioEndpoint("http://ly.meowery.eu", BUILD_KEY))
        assertNull(resolveLyprioEndpoint("https://ly.meowery.eu:8443", BUILD_KEY))
        assertNull(resolveLyprioEndpoint("https://user:pass@ly.meowery.eu", BUILD_KEY))
        assertEquals(BUILD_URL, resolveLyprioEndpoint("https://ly.meowery.eu:443", BUILD_KEY)?.baseUrl)
    }

    private companion object {
        // 44 characters of standard base64 carrying 32 bytes, the shape LyprioEndpoint.parse accepts.
        const val BUILD_URL = "https://ly.meowery.eu"
        const val BUILD_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
    }
}

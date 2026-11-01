package moe.rukamori.archivetune.shared.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Build-only endpoint resolution for Bundled: the bundled pair baked into the build is the only
 * endpoint the provider can have. A missing or malformed value leaves Bundled unconfigured — the
 * provider is then never created and never called.
 */
class BundledBuildEndpointTest {

    @Test
    fun validBuildPairResolvesTheBundledEndpoint() {
        assertEquals(BUILD_URL, resolveBundledEndpoint(BUILD_URL, BUILD_KEY)?.baseUrl)
        // Trailing slashes and surrounding whitespace still normalize.
        assertEquals(BUILD_URL, resolveBundledEndpoint("$BUILD_URL/", BUILD_KEY)?.baseUrl)
        assertEquals(BUILD_URL, resolveBundledEndpoint("  $BUILD_URL  ", "  $BUILD_KEY  ")?.baseUrl)
    }

    @Test
    fun missingBuildValueLeavesTheProviderUnconfigured() {
        assertNull(resolveBundledEndpoint(BUILD_URL, ""))
        assertNull(resolveBundledEndpoint("", BUILD_KEY))
        assertNull(resolveBundledEndpoint("", ""))
        assertNull(resolveBundledEndpoint("   ", "   "))
    }

    @Test
    fun malformedBuildValuesAreRejected() {
        assertNull(resolveBundledEndpoint("notaurl", BUILD_KEY))
        assertNull(resolveBundledEndpoint("https://", BUILD_KEY))
        assertNull(resolveBundledEndpoint(BUILD_URL, "%%%not-base64%%%"))
        // Valid base64 of the wrong length is not a Bundled signing key either.
        assertNull(resolveBundledEndpoint(BUILD_URL, "AAAA"))
    }

    @Test
    fun onlyTheBundledServerShapeParses() {
        // Metrolist's bundled endpoint is HTTPS on port 443 with no credentials embedded in the URL.
        assertNull(resolveBundledEndpoint("http://lyrics.example.invalid", BUILD_KEY))
        assertNull(resolveBundledEndpoint("https://lyrics.example.invalid:8443", BUILD_KEY))
        assertNull(resolveBundledEndpoint("https://user:pass@lyrics.example.invalid", BUILD_KEY))
        assertEquals(BUILD_URL, resolveBundledEndpoint("https://lyrics.example.invalid:443", BUILD_KEY)?.baseUrl)
    }

    private companion object {
        // 44 characters of standard base64 carrying 32 bytes, the shape BundledEndpoint.parse accepts.
        const val BUILD_URL = "https://lyrics.example.invalid"
        const val BUILD_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
    }
}

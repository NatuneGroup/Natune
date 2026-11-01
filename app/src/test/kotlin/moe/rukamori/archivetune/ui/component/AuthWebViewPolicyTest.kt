package moe.rukamori.archivetune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthWebViewPolicyTest {
    @Test
    fun darkeningOverridesUseWebViewCapabilitiesIndependentlyOfTheOs() {
        for (sdk in listOf(26, 28, 29, 32, 33, 37)) {
            for (algorithmic in listOf(false, true)) {
                for (legacy in listOf(false, true)) {
                    val policy = authWebViewDarkeningPolicy(sdk, algorithmic, legacy)
                    assertEquals(sdk >= 29, policy.disableViewForceDark)
                    assertEquals(algorithmic, policy.disableAlgorithmicDarkening)
                    assertEquals(legacy, policy.disableLegacyForceDark)
                }
            }
        }
    }

    @Test
    fun keyboardShrinkRevealsTheFocusedControl() {
        assertTrue(shouldRevealFocusedAuthInput(400, 800, 400, 420))
    }

    @Test
    fun widthReflowRevealsTheFocusedControl() {
        assertTrue(shouldRevealFocusedAuthInput(800, 400, 400, 800))
    }

    @Test
    fun unchangedOrExpandedViewportDoesNotScrollThePage() {
        assertFalse(shouldRevealFocusedAuthInput(400, 420, 400, 420))
        assertFalse(shouldRevealFocusedAuthInput(400, 420, 400, 800))
    }

    @Test
    fun initialAndEmptyLayoutsDoNotScheduleDomWork() {
        assertFalse(shouldRevealFocusedAuthInput(0, 0, 400, 800))
        assertFalse(shouldRevealFocusedAuthInput(400, 800, 400, 0))
        assertFalse(shouldRevealFocusedAuthInput(400, 800, 0, 420))
        assertFalse(shouldRevealFocusedAuthInput(400, 0, 400, 420))
    }

    @Test
    fun webViewUserAgentLosesTheInAppMarkers() {
        val webViewUa =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9 Build/AP4A.250105.002; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/132.0.6834.163 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 15; Pixel 9 Build/AP4A.250105.002) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.6834.163 Mobile Safari/537.36",
            sanitizeAuthWebViewUserAgent(webViewUa),
        )
    }

    @Test
    fun plainChromeUserAgentPassesThroughUntouched() {
        val chromeUa =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.6834.163 Mobile Safari/537.36"
        assertEquals(chromeUa, sanitizeAuthWebViewUserAgent(chromeUa))
    }

    @Test
    fun onlyGesturePopupsOpen() {
        assertTrue(authPopupAllowed(isUserGesture = true))
        assertFalse(authPopupAllowed(isUserGesture = false))
    }

    @Test
    fun onlyBlankPopupsAreDead() {
        assertTrue(isBlankPopupUrl(null))
        assertTrue(isBlankPopupUrl(""))
        assertTrue(isBlankPopupUrl("   "))
        assertTrue(isBlankPopupUrl("about:blank"))
        assertFalse(isBlankPopupUrl("https://accounts.spotify.com/en/login"))
    }

    @Test
    fun flowHostsMatchTheProviderDomainAndSubdomains() {
        val hosts = setOf("spotify.com")
        assertTrue(hostInLoginFlow("spotify.com", hosts))
        assertTrue(hostInLoginFlow("accounts.spotify.com", hosts))
        assertTrue(hostInLoginFlow("SPOTIFY.COM.", hosts))
        assertFalse(hostInLoginFlow("notspotify.com", hosts))
        assertFalse(hostInLoginFlow("spotify.com.evil.example", hosts))
        assertFalse(hostInLoginFlow(null, hosts))
    }

    @Test
    fun loggedUrlsKeepOnlySchemeHostAndPath() {
        assertEquals(
            "https://accounts.spotify.com/en/login",
            authLogUrl("https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com#frag"),
        )
        assertEquals("https://open.spotify.com/", authLogUrl("https://user:pw@open.spotify.com/"))
        assertEquals("http://deezer.com/login", authLogUrl("HTTP://deezer.com/login"))
    }

    @Test
    fun loggedUrlsNeverLeakCredentials() {
        val sensitive = "https://accounts.spotify.com/callback?code=SECRET&state=S"
        assertFalse(authLogUrl(sensitive).contains("SECRET"))
        assertEquals("(none)", authLogUrl(null))
        assertEquals("(none)", authLogUrl(""))
        assertEquals(
            "archivetune://oauth/callback",
            authLogUrl("archivetune://oauth/callback?token=SECRET"),
        )
    }

    @Test
    fun logHostIsLowercaseOrNull() {
        assertEquals("Accounts.Spotify.com".lowercase(), authLogHost("https://Accounts.Spotify.com/x"))
        assertEquals(null, authLogHost(null))
        assertEquals(null, authLogHost(""))
        assertEquals(null, authLogHost("mailto:test@example.com"))
        assertEquals("oauth", authLogHost("archivetune://oauth"))
    }
}

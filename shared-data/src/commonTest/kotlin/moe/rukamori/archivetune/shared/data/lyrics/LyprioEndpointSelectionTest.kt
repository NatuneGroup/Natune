package moe.rukamori.archivetune.shared.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Endpoint selection for Lyprio: the user's own pair always wins once either field is entered; the
 * build-time pair applies only while both user fields are blank and only when both build values are
 * present and parse. The built-in key must never sign a request to a user-chosen server.
 */
class LyprioEndpointSelectionTest {

    @Test
    fun userEntryWinsOverTheBuildDefault() {
        val endpoint = resolveLyprioEndpoint(USER_URL, USER_KEY, BUILD_URL, BUILD_KEY)
        assertEquals(USER_URL, endpoint?.baseUrl)
    }

    @Test
    fun buildDefaultAppliesOnlyWhileBothUserFieldsAreBlank() {
        assertEquals(BUILD_URL, resolveLyprioEndpoint("", "", BUILD_URL, BUILD_KEY)?.baseUrl)
        // A partial user entry is still the user's own: the built-in key is not lent to their server.
        assertNull(resolveLyprioEndpoint(USER_URL, "", BUILD_URL, BUILD_KEY))
        assertNull(resolveLyprioEndpoint("", USER_KEY, BUILD_URL, BUILD_KEY))
        // Whitespace counts as blank, not as an entry.
        assertEquals(BUILD_URL, resolveLyprioEndpoint("  ", " ", BUILD_URL, BUILD_KEY)?.baseUrl)
    }

    @Test
    fun buildDefaultNeedsBothValues() {
        assertNull(resolveLyprioEndpoint("", "", BUILD_URL, ""))
        assertNull(resolveLyprioEndpoint("", "", "", BUILD_KEY))
        assertNull(resolveLyprioEndpoint("", "", "", ""))
    }

    @Test
    fun malformedBuildValuesAreRejected() {
        assertNull(resolveLyprioEndpoint("", "", BUILD_URL, "%%%not-base64%%%"))
        // Valid base64 of the wrong length is not a Lyprio signing key either.
        assertNull(resolveLyprioEndpoint("", "", BUILD_URL, "AAAA"))
        assertNull(resolveLyprioEndpoint("", "", "not a url", BUILD_KEY))
    }

    @Test
    fun malformedUserPairNeverFallsBackToTheBuildDefault() {
        assertNull(resolveLyprioEndpoint(USER_URL, "%%%not-base64%%%", BUILD_URL, BUILD_KEY))
        assertNull(resolveLyprioEndpoint("not a url", USER_KEY, BUILD_URL, BUILD_KEY))
    }

    private companion object {
        // 44 characters of standard base64 carrying 32 bytes, the shape LyprioEndpoint.parse accepts.
        const val USER_URL = "https://lyrics.user.example"
        const val USER_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        const val BUILD_URL = "https://ly.meowery.eu"
        const val BUILD_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
    }
}

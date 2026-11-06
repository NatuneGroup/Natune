package moe.rukamori.archivetune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the provider-cookie semantics of [expireWebAuthCookies].
 *
 * Every sign-in WebView shares the default WebView profile, so expiring the wrong scope signs the
 * other providers — above all the YouTube Music WebView the login recovery keeps signed in — out.
 * These tests treat the jar as the observable and assert both what is expired and what is not.
 */
class WebAuthSessionCleanerTest {
    private class FakeJar(initial: Map<String, String>) {
        private val cookies = initial.mapValues { (_, header) -> header.split("; ").toMutableList() }.toMutableMap()
        val writes = mutableListOf<Pair<String, String>>()
        var flushes = 0

        fun getCookie(origin: String): String? = cookies[origin]?.takeIf { it.isNotEmpty() }?.joinToString("; ")

        fun setCookie(origin: String, cookie: String) {
            writes += origin to cookie
            val name = cookie.substringBefore('=')
            cookies[origin]?.removeAll { it.substringBefore('=').trim() == name }
        }

        fun flush() {
            flushes++
        }
    }

    private fun FakeJar.expire(scope: WebAuthCookieScope) =
        expireWebAuthCookies(
            scope,
            getCookie = { origin -> getCookie(origin) },
            setCookie = { origin, cookie -> setCookie(origin, cookie) },
            flush = { flush() },
        )

    @Test
    fun theYoutubeLogoutStaysScopedToTheOfficialYoutubeHosts() {
        // Playback sign-out is the one logout the fork invariant names as YouTube-scoped; a later
        // provider scope must never grow into it (nor it into a process-wide wipe).
        assertEquals(
            listOf("music.youtube.com", "www.youtube.com", "youtube.com"),
            WebAuthCookieScopes.YOUTUBE.hosts,
        )
        assertEquals(listOf(".youtube.com"), WebAuthCookieScopes.YOUTUBE.domains)
    }

    @Test
    fun deezerLoginExpiresDeezerCookiesAndLeavesEveryOtherJarIntact() {
        val youtube = "SAPISID=synthetic-youtube; SID=synthetic-youtube"
        val google = "SID=synthetic-google"
        val spotify = "sp_dc=synthetic-spotify"
        val lastFm = "session=synthetic-lastfm"
        val jar =
            FakeJar(
                mapOf(
                    "https://www.deezer.com" to "arl=synthetic-deezer; sid=synthetic-deezer",
                    "https://deezer.com" to "arl=synthetic-deezer",
                    "https://music.youtube.com" to youtube,
                    "https://accounts.google.com" to google,
                    "https://accounts.spotify.com" to spotify,
                    "https://www.last.fm" to lastFm,
                ),
            )

        jar.expire(WebAuthCookieScopes.DEEZER)

        assertEquals(youtube, jar.getCookie("https://music.youtube.com"))
        assertEquals(google, jar.getCookie("https://accounts.google.com"))
        assertEquals(spotify, jar.getCookie("https://accounts.spotify.com"))
        assertEquals(lastFm, jar.getCookie("https://www.last.fm"))
        assertNull(jar.getCookie("https://www.deezer.com"))
        assertNull(jar.getCookie("https://deezer.com"))
        assertTrue(jar.writes.isNotEmpty())
        assertTrue(jar.writes.all { (origin, _) -> origin == "https://www.deezer.com" || origin == "https://deezer.com" })
        assertTrue(jar.writes.all { (_, cookie) -> cookie.contains("Max-Age=0; Path=/; Secure") })
        assertTrue(jar.writes.any { (_, cookie) -> cookie.endsWith("Domain=.deezer.com") })
        assertTrue(jar.writes.none { (_, cookie) -> cookie.contains("youtube") || cookie.contains("spotify") })
        assertEquals(1, jar.flushes)
    }

    @Test
    fun spotifyCatalogLogoutExpiresOnlySpotifyCookies() {
        val youtube = "SAPISID=synthetic-youtube; __Secure-3PAPISID=synthetic"
        val google = "SID=synthetic-google"
        val jar =
            FakeJar(
                mapOf(
                    "https://music.youtube.com" to youtube,
                    "https://accounts.google.com" to google,
                    "https://open.spotify.com" to "sp_dc=synthetic-spotify; sp_key=synthetic-spotify",
                    "https://accounts.spotify.com" to "sp_dc=synthetic-spotify",
                ),
            )

        jar.expire(WebAuthCookieScopes.SPOTIFY)

        // The YouTube Music jar is the one the login recovery deliberately preserves.
        assertEquals(youtube, jar.getCookie("https://music.youtube.com"))
        assertEquals(google, jar.getCookie("https://accounts.google.com"))
        assertNull(jar.getCookie("https://open.spotify.com"))
        assertNull(jar.getCookie("https://accounts.spotify.com"))
        assertTrue(jar.writes.isNotEmpty())
        assertTrue(jar.writes.all { (origin, _) -> origin.endsWith("spotify.com") })
        assertTrue(jar.writes.any { (_, cookie) -> cookie.endsWith("Domain=.spotify.com") })
        assertEquals(1, jar.flushes)
    }

    @Test
    fun everyProviderScopeOwnsItsOwnDomainOnly() {
        val scopes =
            listOf(
                WebAuthCookieScopes.YOUTUBE,
                WebAuthCookieScopes.DEEZER,
                WebAuthCookieScopes.TIDAL,
                WebAuthCookieScopes.QOBUZ,
                WebAuthCookieScopes.APPLE_MUSIC,
                WebAuthCookieScopes.LAST_FM,
                WebAuthCookieScopes.LIBRE_FM,
                WebAuthCookieScopes.SPOTIFY,
            )

        scopes.forEach { scope ->
            assertEquals(1, scope.domains.size)
            val domain = scope.domains.single().removePrefix(".")
            scope.hosts.forEach { host ->
                assertTrue(
                    "$host must belong to .$domain",
                    host == domain || host.endsWith(".$domain"),
                )
            }
        }
        // No two providers may share a domain: that is the whole point of the scoped expiry.
        assertEquals(scopes.size, scopes.map { it.domains.single() }.toSet().size)
        val nonYoutube = scopes - WebAuthCookieScopes.YOUTUBE
        assertTrue(
            "only the YouTube scope may expire YouTube or Google cookies",
            nonYoutube.none { scope ->
                scope.hosts.any { host -> host.endsWith("youtube.com") || host.endsWith("google.com") }
            },
        )
    }

    @Test
    fun emptyJarsCostOneFlushAndWriteNothing() {
        val jar = FakeJar(emptyMap())

        jar.expire(WebAuthCookieScopes.TIDAL)

        assertTrue(jar.writes.isEmpty())
        assertEquals(1, jar.flushes)
    }
}

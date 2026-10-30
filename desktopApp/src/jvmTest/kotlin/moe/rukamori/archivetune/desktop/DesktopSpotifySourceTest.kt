package moe.rukamori.archivetune.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.desktop.provider.DesktopSpotifyStreamingTransport
import moe.rukamori.archivetune.desktop.provider.EmptySourceAccounts
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.source.*
import moe.rukamori.archivetune.spotify.Spotify

class DesktopSpotifySourceTest {
    private val request = AudioResolveRequest("song", TrackMatching.Target("Song", listOf("Artist"), null, 180_000))
    private fun preferences() = PreferenceSnapshot.of(SpotifyStreamEnabledKey to true,
        SpotifyStreamSpDcKey to "stream-cookie", SpotifyStreamUserIdKey to "stream-user",
        SpotifyStreamResolveMethodKey to SpotifyStreamResolveMethod.ECHO.name,
        SpotifySpDcKey to "catalog-cookie", SpotifyAccessTokenKey to "catalog-comparison-only",
        SpotifyAccessTokenExpiresAtKey to Long.MAX_VALUE)

    private class Transport : DesktopSpotifyStreamingTransport {
        var userId = "stream-user"
        val cookies = mutableListOf<String>()
        var resolves = 0
        var scope: String? = null
        var active: (() -> Boolean)? = null
        var cancel = false
        override suspend fun userId(spDc: String, active: () -> Boolean): String {
            cookies += spDc
            if (cancel) throw CancellationException("fixture")
            assertTrue(active())
            return userId
        }
        override suspend fun resolve(spDc: String, request: AudioResolveRequest, scope: String,
            active: () -> Boolean): PreparedAudio? {
            cookies += spDc
            resolves++
            this.scope = scope
            this.active = active
            assertTrue(active())
            return null
        }
        override suspend fun close() = Unit
    }

    @Test fun streamingTransportSeesOnlyItsCookieWhileCatalogTokenIsComparisonOnlyAndGlobalIsUnchanged() = runTest {
        val http = HttpClient(MockEngine { error("no real network") })
        val previous = Spotify.accessToken
        try {
            Spotify.accessToken = "catalog-global-marker"
            var prefs = preferences()
            val admissions = EmptySourceAccounts.admissions()
            val transport = Transport()
            val source = DesktopSpotifySource(http, { prefs }, admissions, transport, catalogProfile = { token ->
                assertEquals("catalog-comparison-only", token)
                "different-catalog-user"
            })
            assertNull(source.resolve(request))
            assertEquals(listOf("stream-cookie", "stream-cookie"), transport.cookies)
            assertEquals("catalog-global-marker", Spotify.accessToken)
            val scope = assertNotNull(transport.scope)
            val admitted = assertNotNull(admissions.admit("spotify", scope))
            assertEquals("stream-user", admitted.accepted.identity.accountId)
            prefs = prefs.toEditor().apply { this[SpotifyStreamEnabledKey] = false }.snapshot()
            assertFalse(assertNotNull(transport.active).invoke())
            assertNull(admissions.admit("spotify", scope))
            source.close()
        } finally {
            Spotify.accessToken = previous
            http.close()
        }
    }

    @Test fun sameCookieIsRefusedBeforeTransportAndDifferentCookiesWithSameUserRefuseBeforeAudio() = runTest {
        val http = HttpClient(MockEngine { respond("{}") })
        try {
            var prefs = preferences().toEditor().apply { this[SpotifySpDcKey] = "stream-cookie" }.snapshot()
            val transport = Transport()
            val source = DesktopSpotifySource(http, { prefs }, EmptySourceAccounts.admissions(), transport,
                catalogProfile = { "stream-user" })
            assertFailsWith<AudioProviderUnavailable> { source.resolve(request) }
            assertTrue(transport.cookies.isEmpty())
            prefs = preferences()
            val failure = assertFailsWith<AudioProviderUnavailable> { source.resolve(request) }
            assertEquals(AudioProviderUnavailableReason.ACCOUNT_CHANGED, failure.reason)
            assertEquals(0, transport.resolves)
        } finally { http.close() }
    }

    @Test fun defaultWebMethodAndMissingClientConfigurationAreHonestUnavailableNotAnImplicitMethodSwitch() = runTest {
        val http = HttpClient(MockEngine { error("unavailable routes do not use network") })
        try {
            var prefs = preferences().toEditor().apply { this[SpotifyStreamResolveMethodKey] = null }.snapshot()
            val source = DesktopSpotifySource(http, { prefs }, EmptySourceAccounts.admissions(), null)
            assertEquals(AudioProviderUnavailableReason.PLATFORM_DECRYPTION_REQUIRED,
                assertFailsWith<AudioProviderUnavailable> { source.resolve(request) }.reason)
            prefs = preferences()
            assertEquals(AudioProviderUnavailableReason.CLIENT_CONFIGURATION_REQUIRED,
                assertFailsWith<AudioProviderUnavailable> { source.resolve(request) }.reason)
            assertEquals(SpotifyStreamResolveMethod.ECHO.name, prefs[SpotifyStreamResolveMethodKey])
        } finally { http.close() }
    }

    @Test fun profileMismatchDownloadsAndCallerCancellationNeverBecomeSuccessfulStreaming() = runTest {
        val http = HttpClient(MockEngine { error("no real network") })
        try {
            val transport = Transport().apply { userId = "other-user" }
            val source = DesktopSpotifySource(http, ::preferences, EmptySourceAccounts.admissions(), transport,
                catalogProfile = { "catalog-user" })
            assertEquals(AudioProviderUnavailableReason.ACCOUNT_CHANGED,
                assertFailsWith<AudioProviderUnavailable> { source.resolve(request) }.reason)
            assertEquals(0, transport.resolves)
            val calls = transport.cookies.size
            assertFailsWith<AudioProviderUnavailable> { source.resolve(request.copy(purpose = AudioPurpose.DOWNLOAD)) }
            assertEquals(calls, transport.cookies.size)
            transport.cancel = true
            assertFailsWith<CancellationException> { source.resolve(request) }
        } finally { http.close() }
    }
}

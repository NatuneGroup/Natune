package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.headersOf
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.data.provider.ProviderRequestTimeout
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderUnavailable

internal fun echoAuthReply(path: String): String? = when (path) {
    "/oauth2/device/authorize" ->
        """{"device_code":"device","user_code":"user","verification_uri_complete":"https://accounts.spotify.com/pair?flow_ctx=fixture:1"}"""
    "/pair" -> """<script id="__NEXT_DATA__" type="application/json">{"props":{"initialToken":"csrf"}}</script>"""
    "/pair/api/resolve" -> """{"result":"ok"}"""
    "/api/token" -> """{"access_token":"stream-bearer","expires_in":3600}"""
    "/v1/clienttoken" -> """{"granted_token":{"token":"stream-client-token"}}"""
    else -> null
}

internal fun echoConfiguration() = DesktopSpotifyEchoConfiguration("desktop-client", "web-client",
    ByteArray(16) { it.toByte() }.joinToString("") { "%02x".format(it.toInt() and 255) })

class DesktopSpotifyEchoAuthTest {
    @Test fun pairingUsesOnlyTheStreamingJarAndNeverSendsItToClientTokenOrReusesItForAnotherCookie() = runTest {
        var cookie = "stream-cookie"
        var starts = 0
        val http = HttpClient(MockEngine { call ->
            assertNull(call.headers["Authorization"])
            if (call.url.host == "accounts.spotify.com") {
                assertTrue(call.headers["Cookie"].orEmpty().contains("sp_dc=$cookie"))
                if (call.url.encodedPath == "/oauth2/device/authorize") {
                    starts++
                    respond(assertNotNull(echoAuthReply(call.url.encodedPath)),
                        headers = headersOf("Set-Cookie", "flow=fixture; Path=/; Secure; HttpOnly"))
                } else {
                    assertTrue(call.headers["Cookie"].orEmpty().contains("flow=fixture"))
                    respond(assertNotNull(echoAuthReply(call.url.encodedPath)))
                }
            } else {
                assertEquals("clienttoken.spotify.com", call.url.host)
                assertNull(call.headers["Cookie"])
                respond(assertNotNull(echoAuthReply(call.url.encodedPath)))
            }
        })
        try {
            val auth = DesktopSpotifyEchoAuth(http, echoConfiguration())
            val first = auth.session(cookie) { true }
            assertEquals("stream-bearer", first.bearer)
            assertSame(first, auth.session(cookie) { true })
            assertEquals(1, starts)
            cookie = "other-stream-cookie"
            assertNotSame(first, auth.session(cookie) { true })
            assertEquals(2, starts)
            auth.close()
            assertFailsWith<AudioProviderUnavailable> { auth.session(cookie) { true } }
        } finally { http.close() }
    }

    @Test fun verificationCannotRedirectTheStreamingCookieOffTheOfficialAccountsOrigin() = runTest {
        val hosts = mutableListOf<String>()
        val http = HttpClient(MockEngine { call ->
            hosts += call.url.host
            respond("""{"device_code":"device","user_code":"user","verification_uri_complete":"https://other.invalid/pair"}""")
        })
        try {
            val auth = DesktopSpotifyEchoAuth(http, echoConfiguration())
            assertFailsWith<DesktopSpotifyProtocolFailure> { auth.session("stream-cookie") { true } }
            assertEquals(listOf("accounts.spotify.com"), hosts)
        } finally { http.close() }
    }

    @Test fun ownRequestTimeoutIsNotCallerCancellation() = runTest {
        val http = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { delay(DesktopSpotifyHttp.REQUEST_TIMEOUT_MS + 1); respond("{}") }
        } }
        try {
            assertFailsWith<ProviderRequestTimeout> {
                DesktopSpotifyEchoAuth(http, echoConfiguration()).session("stream-cookie") { true }
            }
        } finally { http.close() }
    }

    @Test fun callerCancellationAndRevocationDuringPairingEscapeWithoutPublishingAToken() = runTest {
        val cancelled = HttpClient(MockEngine { throw CancellationException("fixture") })
        try {
            assertFailsWith<CancellationException> {
                DesktopSpotifyEchoAuth(cancelled, echoConfiguration()).session("stream-cookie") { true }
            }
        } finally { cancelled.close() }
        var active = true
        val revoked = HttpClient(MockEngine { active = false; respond(assertNotNull(echoAuthReply("/oauth2/device/authorize"))) })
        try {
            assertFailsWith<AudioProviderUnavailable> {
                DesktopSpotifyEchoAuth(revoked, echoConfiguration()).session("stream-cookie") { active }
            }
        } finally { revoked.close() }
    }
}

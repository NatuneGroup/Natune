package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import moe.rukamori.archivetune.shared.data.bytesource.Md5
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.source.*

class QobuzAccountExchangeTest {
    private val secret = "0123456789abcdef0123456789abcdef"

    @Test fun emailExchangeProducesSavedCredentialsThatReallySignBuiltInPlayback() = runTest {
        val calls = mutableListOf<String>()
        val http = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                calls += it.url.encodedPath.substringAfterLast('/')
                assertEquals("123456", it.headers["X-App-Id"])
                if (it.url.encodedPath.endsWith("user/login")) {
                    assertEquals(HttpMethod.Get, it.method)
                    assertEquals("user@example.invalid", it.url.parameters["email"])
                    assertEquals("b42a07e2d9e28e18c28cd5990130a37e", it.url.parameters["password"])
                    assertNull(it.headers["X-User-Auth-Token"])
                    respond("""{"user_auth_token":"fixture-user-token","user":{"id":42}}""")
                } else {
                    assertEquals("fixture-user-token", it.headers["X-User-Auth-Token"])
                    if (it.url.encodedPath.endsWith("getFileUrl")) {
                        assertEquals("0", it.url.parameters["request_ts"])
                        val sig = Md5.hexDigest("trackgetFileUrlformat_id6intentstreamtrack_id70$secret".encodeToByteArray())
                        assertEquals(sig, it.url.parameters["request_sig"])
                        respond("""{"url":"https://cdn.invalid/full.flac","mime_type":"audio/flac"}""")
                    } else respond("""{"id":7,"title":"Song","performer":{"name":"Artist"},"duration":180}""")
                }
            }
        } }
        try {
            val entry = assertNotNull(QobuzAccountExchange(http).signIn(" user@example.invalid ", "fixture password", "123456", secret))
            assertEquals("42", entry.userId)
            // Exercise the existing serialized token list, not a new password/profile store.
            val saved = PreferenceSnapshot.EMPTY.toEditor().apply { addQobuzTokens(listOf(entry)) }.snapshot().qobuzTokens().single()
            val repo = KtorQobuzRepository(http, { 0 }, QobuzAccess("personal", saved.appId, saved.token, saved.appSecret),
                QobuzRequestDigest { Md5.hexDigest(it.encodeToByteArray()) })
            val audio = assertIs<PreparedAudio.Progressive>(repo.resolve(AudioResolveRequest("media",
                TrackMatching.Target("Song", listOf("Artist"), null, 180_000), directIds = mapOf(AudioSourceType.QOBUZ to "7"))))
            assertEquals("https://cdn.invalid/full.flac", audio.stream.uri)
            assertEquals(listOf("login", "get", "getFileUrl"), calls)
        } finally { http.close() }
    }

    @Test fun refusalMalformedAndRedirectRepliesNeverYieldAnAccountOrFollowCredentials() = runTest {
        var status = HttpStatusCode.Unauthorized
        var body = "{}"
        var calls = 0
        val http = HttpClient(MockEngine) { followRedirects = true; engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                calls++
                assertEquals("www.qobuz.com", it.url.host)
                respond(body, status, headersOf(HttpHeaders.Location, "https://foreign.invalid/stolen"))
            }
        } }
        try {
            val exchange = QobuzAccountExchange(http)
            for (refused in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized)) {
                status = refused
                assertNull(exchange.signIn("user@example.invalid", "fixture password", "123456", secret))
            }
            status = HttpStatusCode.OK
            for (malformed in listOf("{}", """{"user_auth_token":123}""", "not-json")) {
                body = malformed
                assertFailsWith<ProviderProtocolFailure> { exchange.signIn("user@example.invalid", "fixture password", "123456", secret) }
            }
            status = HttpStatusCode.Found
            assertEquals(302, assertFailsWith<ProviderHttpFailure> {
                exchange.signIn("user@example.invalid", "fixture password", "123456", secret)
            }.status)
            assertEquals(6, calls)
        } finally { http.close() }
    }

    @Test fun callerCancellationDuringLoginDoesNotProduceASession() = runTest {
        val entered = CompletableDeferred<Unit>()
        var saved = false
        val http = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { entered.complete(Unit); awaitCancellation() }
        } }
        try {
            val job = launch { QobuzAccountExchange(http).signIn("user@example.invalid", "fixture password", "123456", secret); saved = true }
            entered.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertFalse(saved)
        } finally { http.close() }
    }
}

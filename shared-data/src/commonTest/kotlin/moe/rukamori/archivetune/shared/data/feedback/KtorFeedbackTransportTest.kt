package moe.rukamori.archivetune.shared.data.feedback

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The hosted-feedback contract: an unconfigured build reports itself unavailable and never opens a
 * connection, and a configured one posts the reviewed envelope to `<base>/api/report` with the ingest key,
 * surfacing the server's own error text on rejection.
 */
class KtorFeedbackTransportTest {

    private fun transport(
        engine: MockEngine,
        apiUrl: String = API_URL,
        ingestKey: String = INGEST_KEY,
    ) = KtorFeedbackTransport(HttpClient(engine), apiUrl, ingestKey)

    @Test
    fun `blank api url is unavailable and sends nothing`() = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK) }
        val transport = transport(engine, apiUrl = "  ")
        assertFalse(transport.available)
        val result = transport.send(ENVELOPE)
        assertTrue(result.isFailure)
        assertEquals("Feedback service not configured", result.exceptionOrNull()?.message)
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `blank ingest key is unavailable and sends nothing`() = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK) }
        val transport = transport(engine, ingestKey = " ")
        assertFalse(transport.available)
        val result = transport.send(ENVELOPE)
        assertTrue(result.isFailure)
        assertEquals("Feedback service not configured", result.exceptionOrNull()?.message)
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `accepted report posts the envelope with the ingest key`() = runBlocking {
        var request: HttpRequestData? = null
        val engine = MockEngine { received ->
            request = received
            respond("""{"ok":true}""", HttpStatusCode.OK, JSON_HEADERS)
        }
        val transport = transport(engine)
        assertTrue(transport.available)
        assertTrue(transport.send(ENVELOPE).isSuccess)
        val sent = request ?: error("no request reached the server")
        assertEquals("$API_URL/api/report", sent.url.toString())
        assertEquals(INGEST_KEY, sent.headers["X-Ingest-Key"])
    }

    @Test
    fun `trailing slash on the api url is not doubled`() = runBlocking {
        var request: HttpRequestData? = null
        val engine = MockEngine { received ->
            request = received
            respond("""{"ok":true}""", HttpStatusCode.OK, JSON_HEADERS)
        }
        val transport = transport(engine, apiUrl = "$API_URL/")
        assertTrue(transport.send(ENVELOPE).isSuccess)
        assertEquals("$API_URL/api/report", request?.url.toString())
    }

    @Test
    fun `rate limited surfaces as a rejection`() = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.TooManyRequests, JSON_HEADERS) }
        val result = transport(engine).send(ENVELOPE)
        assertEquals("Rate limited, try again later", result.exceptionOrNull()?.message)
    }

    @Test
    fun `rejected report carries the server error text`() = runBlocking {
        val engine = MockEngine {
            respond("""{"ok":false,"error":"invalid ingest key"}""", HttpStatusCode.Unauthorized, JSON_HEADERS)
        }
        val result = transport(engine).send(ENVELOPE)
        assertTrue(result.exceptionOrNull() is KtorFeedbackTransport.FeedbackRejection)
        assertEquals("invalid ingest key", result.exceptionOrNull()?.message)
    }

    @Test
    fun `rejected report without a readable error falls back to the code`() = runBlocking {
        val engine = MockEngine { respond("nope", HttpStatusCode.Forbidden, JSON_HEADERS) }
        val result = transport(engine).send(ENVELOPE)
        assertEquals("Report rejected (403)", result.exceptionOrNull()?.message)
    }

    @Test
    fun `server failure reports the service unavailable`() = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.InternalServerError, JSON_HEADERS) }
        val result = transport(engine).send(ENVELOPE)
        assertEquals("Service unavailable (500)", result.exceptionOrNull()?.message)
    }

    private companion object {
        const val API_URL = "https://feedback.test"
        const val INGEST_KEY = "ingest-key"
        const val ENVELOPE = """{"kind":"playback","report":"x"}"""
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
    }
}

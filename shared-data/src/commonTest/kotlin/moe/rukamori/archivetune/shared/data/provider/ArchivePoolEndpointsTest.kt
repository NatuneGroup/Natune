package moe.rukamori.archivetune.shared.data.provider

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

class ArchivePoolEndpointsTest {

    @AfterTest
    fun reset() {
        ArchivePoolEndpoints.lastGood = null
    }

    @Test
    fun blankOrInvalidConfigFallsBackToThePublicEndpoint() {
        for (configured in listOf(null, "", "   ", "http://pool.internal", "ftp://x")) {
            assertEquals(
                listOf(ArchivePoolEndpoints.FALLBACK_BASE_URL),
                ArchivePoolEndpoints.candidates(configured),
                "configured=$configured",
            )
        }
    }

    @Test
    fun configuredPrimaryComesFirstAndTrimsSlashes() {
        assertEquals("https://archivepool.example.workers.dev", ArchivePoolEndpoints.primary(" https://archivepool.example.workers.dev/ "))
        assertEquals(
            listOf("https://archivepool.example.workers.dev", ArchivePoolEndpoints.FALLBACK_BASE_URL),
            ArchivePoolEndpoints.candidates("https://archivepool.example.workers.dev/"),
        )
    }

    @Test
    fun theVercelDefaultDedupesToASingleEndpoint() {
        assertEquals(
            listOf(ArchivePoolEndpoints.FALLBACK_BASE_URL),
            ArchivePoolEndpoints.candidates(ArchivePoolEndpoints.FALLBACK_BASE_URL),
        )
    }

    @Test
    fun theRememberedEndpointLeadsTheOrderWhileFresh() {
        ArchivePoolEndpoints.remember(ArchivePoolEndpoints.FALLBACK_BASE_URL)
        assertEquals(
            listOf(ArchivePoolEndpoints.FALLBACK_BASE_URL, "https://archivepool.example.workers.dev"),
            ArchivePoolEndpoints.candidates("https://archivepool.example.workers.dev"),
        )
    }

    @Test
    fun theRememberedEndpointExpiresAfterTheWindow() {
        ArchivePoolEndpoints.lastGood =
            ArchivePoolEndpoints.FALLBACK_BASE_URL to (TimeSource.Monotonic.markNow() - 11.minutes)
        assertEquals(
            listOf("https://archivepool.example.workers.dev", ArchivePoolEndpoints.FALLBACK_BASE_URL),
            ArchivePoolEndpoints.candidates("https://archivepool.example.workers.dev"),
        )
    }

    @Test
    fun failoverStatusesAreHostFailuresOnly() {
        for (status in listOf(402, 500, 502, 503, 599)) {
            assertTrue(ArchivePoolEndpoints.isFailoverStatus(status), "HTTP $status should fail over")
        }
        for (status in listOf(200, 204, 301, 400, 401, 403, 404, 408, 418, 429)) {
            assertFalse(ArchivePoolEndpoints.isFailoverStatus(status), "HTTP $status is definitive")
        }
    }

    @Test
    fun failoverAnswersAreNeverRemembered() = kotlinx.coroutines.test.runTest {
        for (status in listOf(402, 503)) {
            ArchivePoolEndpoints.lastGood = null
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev") { Pair(status, "") }
            assertNull(ArchivePoolEndpoints.lastGood, "HTTP $status must never be remembered")
        }
    }

    @Test
    fun aDefinitiveAnswerIsRememberedWhateverItsBody() = kotlinx.coroutines.test.runTest {
        val tried = mutableListOf<String>()
        val answer =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev") { endpoint ->
                tried += endpoint
                Pair(200, "not-json-at-all")
            }
        assertEquals(Pair(200, "not-json-at-all"), answer)
        assertEquals(listOf("https://archivepool.example.workers.dev"), tried)
        assertEquals("https://archivepool.example.workers.dev", ArchivePoolEndpoints.lastGood?.first)
    }

    @Test
    fun aReportStopsOnATransportFailure() = kotlinx.coroutines.test.runTest {
        val tried = mutableListOf<String>()
        val answer =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev", report = true) { endpoint ->
                tried += endpoint
                null
            }
        assertNull(answer)
        assertEquals(listOf("https://archivepool.example.workers.dev"), tried)
    }

    @Test
    fun aReportAdvancesOnlyOnAnExplicit402Or5xx() = kotlinx.coroutines.test.runTest {
        val tried = mutableListOf<String>()
        val answer =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev", report = true) { endpoint ->
                tried += endpoint
                if (endpoint.endsWith("workers.dev")) Pair(402, "") else Pair(200, "ok")
            }
        assertEquals(Pair(200, "ok"), answer)
        assertEquals(2, tried.size)

        ArchivePoolEndpoints.lastGood = null
        tried.clear()
        val denied =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev", report = true) { endpoint ->
                tried += endpoint
                Pair(401, "")
            }
        assertEquals(Pair(401, ""), denied)
        assertEquals(listOf("https://archivepool.example.workers.dev"), tried)
    }

    @Test
    fun acrossEndpointsReturnsTheFirstDefinitiveAnswer() = kotlinx.coroutines.test.runTest {
        val tried = mutableListOf<String>()
        val answer =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev") { endpoint ->
                tried += endpoint
                if (tried.size == 1) Pair(402, "") else Pair(200, "ok")
            }
        assertEquals(Pair(200, "ok"), answer)
        assertEquals(
            listOf("https://archivepool.example.workers.dev", ArchivePoolEndpoints.FALLBACK_BASE_URL),
            tried,
        )
        assertEquals(ArchivePoolEndpoints.FALLBACK_BASE_URL, ArchivePoolEndpoints.lastGood?.first)
    }

    @Test
    fun acrossEndpointsWalksOnlyTransportFailures() = kotlinx.coroutines.test.runTest {
        val tried = mutableListOf<String>()
        val answer =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev") { endpoint ->
                tried += endpoint
                if (endpoint.endsWith("workers.dev")) null else Pair(401, "")
            }
        assertEquals(Pair(401, ""), answer)
        assertEquals(
            listOf("https://archivepool.example.workers.dev", ArchivePoolEndpoints.FALLBACK_BASE_URL),
            tried,
        )
    }

    @Test
    fun acrossEndpointsStopsBeforeTheFallbackOnApiErrors() = kotlinx.coroutines.test.runTest {
        val tried = mutableListOf<String>()
        val answer =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev") { endpoint ->
                tried += endpoint
                Pair(429, "")
            }
        assertEquals(Pair(429, ""), answer)
        assertEquals(listOf("https://archivepool.example.workers.dev"), tried)
    }

    @Test
    fun acrossEndpointsReturnsTheLastAnswerWhenEveryEndpointFails() = kotlinx.coroutines.test.runTest {
        val answer =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev") { endpoint ->
                if (endpoint.endsWith("workers.dev")) Pair(402, "") else null
            }
        assertEquals(Pair(402, ""), answer)
        val allTransport = ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev") { null }
        assertNull(allTransport)
    }

    @Test
    fun aReportIsNeverSentToTheFallbackAfterADefinitiveAnswer() = kotlinx.coroutines.test.runTest {
        val tried = mutableListOf<String>()
        val answer =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev", report = true) { endpoint ->
                tried += endpoint
                Pair(404, "")
            }
        assertEquals(Pair(404, ""), answer)
        assertEquals(listOf("https://archivepool.example.workers.dev"), tried)
        tried.clear()
        ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev", report = true) { endpoint ->
            tried += endpoint
            if (endpoint.endsWith("workers.dev")) Pair(503, "") else Pair(200, "ok")
        }
        assertEquals(2, tried.size)
    }
}

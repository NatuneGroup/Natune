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
    fun theConfiguredDeploymentIsTheWholeOrder() {
        assertEquals("https://archivepool.example.workers.dev", ArchivePoolEndpoints.primary(" https://archivepool.example.workers.dev/ "))
        // The retired Vercel origin is not appended any more: it only ever answered 402.
        assertEquals(
            listOf("https://archivepool.example.workers.dev"),
            ArchivePoolEndpoints.candidates("https://archivepool.example.workers.dev/"),
        )
    }

    @Test
    fun thePublicDefaultDedupesToASingleEndpoint() {
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
            listOf("https://archivepool.example.workers.dev"),
            ArchivePoolEndpoints.candidates("https://archivepool.example.workers.dev"),
        )
    }

    @Test
    fun failoverStatusesAreHostFailuresOnly() {
        // 503 is the pool's own refusal with Retry-After, so it is definitive, not a host failure.
        for (status in listOf(402, 500, 502, 504, 599)) {
            assertTrue(ArchivePoolEndpoints.isFailoverStatus(status), "HTTP $status should fail over")
        }
        for (status in listOf(200, 204, 301, 400, 401, 403, 404, 408, 418, 429, 503)) {
            assertFalse(ArchivePoolEndpoints.isFailoverStatus(status), "HTTP $status is definitive")
        }
    }

    @Test
    fun refusalsAreDefinitiveForAKeyRateOrAvailabilityProblem() {
        for (status in listOf(401, 403, 429, 503)) {
            assertTrue(ArchivePoolEndpoints.isRefusal(status), "HTTP $status must earn a backoff")
        }
        for (status in listOf(200, 204, 304, 400, 404, 402, 500)) {
            assertFalse(ArchivePoolEndpoints.isRefusal(status), "HTTP $status is not a refusal")
        }
    }

    @Test
    fun failoverAndRefusalAnswersAreNeverRemembered() = kotlinx.coroutines.test.runTest {
        for (status in listOf(402, 503, 429, 401, 403)) {
            ArchivePoolEndpoints.lastGood = null
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev") { PoolResponse(status) }
            assertNull(ArchivePoolEndpoints.lastGood, "HTTP $status must never be remembered")
        }
    }

    @Test
    fun aDefinitiveAnswerIsRememberedWhateverItsBody() = kotlinx.coroutines.test.runTest {
        val tried = mutableListOf<String>()
        val answer =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev") { endpoint ->
                tried += endpoint
                PoolResponse(200, "not-json-at-all")
            }
        assertEquals(200, answer?.status)
        assertEquals("not-json-at-all", answer?.body)
        assertEquals(listOf("https://archivepool.example.workers.dev"), tried)
        assertEquals("https://archivepool.example.workers.dev", ArchivePoolEndpoints.lastGood?.first)
    }

    @Test
    fun aNotModifiedAnswerRevalidatesAndIsRemembered() = kotlinx.coroutines.test.runTest {
        val answer =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev") { PoolResponse(304, etag = "\"v1\"") }
        assertEquals(304, answer?.status)
        assertTrue(answer?.notModified == true)
        assertEquals("https://archivepool.example.workers.dev", ArchivePoolEndpoints.lastGood?.first)
    }

    @Test
    fun aThrottledAnswerCarriesItsRetryAfterToTheCaller() = kotlinx.coroutines.test.runTest {
        val answer =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev") {
                PoolResponse(429, retryAfterMs = PoolResponse.retryAfterMs("120"))
            }
        assertEquals(429, answer?.status)
        assertEquals(120_000L, answer?.retryAfterMs)
        assertTrue(answer?.refused == true)
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
    fun aReportAdvancesOnlyOnAnExplicit402OrFailover5xx() = kotlinx.coroutines.test.runTest {
        val tried = mutableListOf<String>()
        ArchivePoolEndpoints.remember("https://backup.example")
        val answer =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev", report = true) { endpoint ->
                tried += endpoint
                if (endpoint.endsWith("backup.example")) PoolResponse(402) else PoolResponse(200, "ok")
            }
        assertEquals("ok", answer?.body)
        assertEquals(2, tried.size)

        ArchivePoolEndpoints.lastGood = null
        tried.clear()
        val denied =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev", report = true) { endpoint ->
                tried += endpoint
                PoolResponse(401)
            }
        assertEquals(401, denied?.status)
        assertEquals(listOf("https://archivepool.example.workers.dev"), tried)
    }

    @Test
    fun acrossEndpointsReturnsTheFirstDefinitiveAnswer() = kotlinx.coroutines.test.runTest {
        val tried = mutableListOf<String>()
        ArchivePoolEndpoints.remember("https://backup.example")
        val answer =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev") { endpoint ->
                tried += endpoint
                if (tried.size == 1) PoolResponse(402) else PoolResponse(200, "ok")
            }
        assertEquals(200, answer?.status)
        assertEquals("https://archivepool.example.workers.dev", ArchivePoolEndpoints.lastGood?.first)
    }

    @Test
    fun acrossEndpointsWalksOnlyTransportFailures() = kotlinx.coroutines.test.runTest {
        val tried = mutableListOf<String>()
        ArchivePoolEndpoints.remember("https://backup.example")
        val answer =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev") { endpoint ->
                tried += endpoint
                if (endpoint.endsWith("backup.example")) null else PoolResponse(401)
            }
        assertEquals(401, answer?.status)
        assertEquals(
            listOf("https://backup.example", "https://archivepool.example.workers.dev"),
            tried,
        )
    }

    @Test
    fun a503StopsTheWalkInsteadOfRepeatingTheRefusalThroughTheDeadFallback() = kotlinx.coroutines.test.runTest {
        val tried = mutableListOf<String>()
        ArchivePoolEndpoints.remember("https://backup.example")
        val answer =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev") { endpoint ->
                tried += endpoint
                PoolResponse(503, retryAfterMs = 60_000L)
            }
        assertEquals(503, answer?.status)
        assertEquals(listOf("https://backup.example"), tried, "the remembered deployment leads and the 503 stops there")
    }

    @Test
    fun acrossEndpointsReturnsTheLastAnswerWhenEveryEndpointFails() = kotlinx.coroutines.test.runTest {
        ArchivePoolEndpoints.remember("https://backup.example")
        val answer =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev") { endpoint ->
                if (endpoint.endsWith("backup.example")) PoolResponse(402) else null
            }
        assertEquals(402, answer?.status)
        val allTransport = ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev") { null }
        assertNull(allTransport)
    }

    @Test
    fun aReportIsNeverSentTwiceAfterADefinitiveAnswer() = kotlinx.coroutines.test.runTest {
        val tried = mutableListOf<String>()
        val answer =
            ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev", report = true) { endpoint ->
                tried += endpoint
                PoolResponse(404)
            }
        assertEquals(404, answer?.status)
        assertEquals(listOf("https://archivepool.example.workers.dev"), tried)
        tried.clear()
        ArchivePoolEndpoints.acrossEndpoints("https://archivepool.example.workers.dev", report = true) { endpoint ->
            tried += endpoint
            if (endpoint.endsWith("workers.dev")) PoolResponse(503) else PoolResponse(200, "ok")
        }
        assertEquals(listOf("https://archivepool.example.workers.dev"), tried, "a 503 is the refusal, not a lost report")
    }

    @Test
    fun retryAfterIsDeltaSecondsAndIgnoresAnythingElse() {
        assertEquals(120_000L, PoolResponse.retryAfterMs("120"))
        assertEquals(60_000L, PoolResponse.retryAfterMs(" 60 "))
        assertNull(PoolResponse.retryAfterMs(null))
        assertNull(PoolResponse.retryAfterMs(""))
        assertNull(PoolResponse.retryAfterMs("0"))
        assertNull(PoolResponse.retryAfterMs("-5"))
        assertNull(PoolResponse.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(PoolResponse.MAX_RETRY_AFTER_MS, PoolResponse.retryAfterMs("99999999"))
    }
}

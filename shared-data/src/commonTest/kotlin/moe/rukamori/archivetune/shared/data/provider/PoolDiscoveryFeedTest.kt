package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest

/**
 * The discovery feed's HTTP behavior, driven by a fake Ktor engine: a refusal stops the retries,
 * `Retry-After` bounds the quiet period exactly, a `304` serves the stored body without one,
 * concurrent callers coalesce, and a backgrounded host does not poll at all.
 */
class PoolDiscoveryFeedTest {

    @AfterTest
    fun reset() {
        PoolPolling.foreground = true
        ArchivePoolEndpoints.lastGood = null
    }

    private class Harness(
        val store: PoolDiscoveryStore,
        val now: () -> Long,
        val requests: MutableList<String> = mutableListOf(),
    ) {
        fun feed(engine: MockEngine): PoolDiscoveryFeed {
            val client = HttpClient(engine)
            return PoolDiscoveryFeed(
                service = "test",
                nowMs = now,
                candidateFeeds = { listOf("https://pool.example/api/discovery/tidal") },
                request = { url, headers ->
                    requests += url
                    client.get(url) { headers.forEach { (name, value) -> header(name, value) } }.let { response ->
                        PoolResponse(
                            status = response.status.value,
                            body = if (response.status.value == 200) response.bodyAsText() else "",
                            retryAfterMs = PoolResponse.retryAfterMs(response.headers[HttpHeaders.RetryAfter]),
                            etag = response.headers["ETag"],
                        )
                    }
                },
                store = store,
            )
        }
    }

    @Test
    fun anUnauthorizedKeyStopsTheRetriesForHours() = runTest {
        var now = 1_000_000L
        val harness = Harness(MemoryPoolDiscoveryStore(), { now })
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        try {
            val feed = harness.feed(engine)
            assertEquals(emptyList(), feed.instances())
            assertEquals(1, harness.requests.size)

            // Still well inside the six-hour configuration backoff: no second attempt.
            now += 60L * 60L * 1000L
            assertEquals(emptyList(), feed.instances())
            assertEquals(1, harness.requests.size)

            // A day later the refusal has expired and the feed is asked exactly once more.
            now += 24L * 60L * 60L * 1000L
            feed.instances()
            assertEquals(2, harness.requests.size)
        } finally {
            engine.close()
        }
    }

    @Test
    fun aThrottledFeedHonoursRetryAfterExactly() = runTest {
        var now = 1_000_000L
        val store = MemoryPoolDiscoveryStore(PoolDiscoveryState(instances = listOf("https://instance.example")))
        val harness = Harness(store, { now })
        val engine = MockEngine {
            respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "120"))
        }
        try {
            val feed = harness.feed(engine)
            // The refusal keeps serving the stored list and asks nobody again.
            assertEquals(listOf("https://instance.example"), feed.instances())
            assertEquals(1, harness.requests.size)

            now += 60_000L
            assertEquals(listOf("https://instance.example"), feed.instances())
            assertEquals(1, harness.requests.size, "half of Retry-After is still quiet")

            now += 61_000L
            feed.instances()
            assertEquals(2, harness.requests.size, "past Retry-After the feed is asked again")
        } finally {
            engine.close()
        }
    }

    @Test
    fun aNotModifiedAnswerServesTheStoredBodyWithoutDownloading() = runTest {
        var now = 1_000_000L
        val stored = listOf("https://a.example", "https://b.example")
        val store = MemoryPoolDiscoveryStore(PoolDiscoveryState(instances = stored, etag = "\"v1\""))
        val harness = Harness(store, { now })
        var sentIfNoneMatch: String? = null
        val engine = MockEngine { request ->
            sentIfNoneMatch = request.headers["If-None-Match"]
            respond("", HttpStatusCode.NotModified, headersOf("ETag", "\"v1\""))
        }
        try {
            val feed = harness.feed(engine)
            assertEquals(stored, feed.instances())
            assertEquals("\"v1\"", sentIfNoneMatch, "the stored ETag must revalidate the feed")
            assertEquals(1, harness.requests.size)

            // The 304 refreshed the window, so the next caller inside the TTL costs no request.
            now += 60_000L
            assertEquals(stored, feed.instances())
            assertEquals(1, harness.requests.size)
        } finally {
            engine.close()
        }
    }

    @Test
    fun concurrentCallersCoalesceIntoOneRequest() = runTest {
        val now = 1_000_000L
        val store = MemoryPoolDiscoveryStore()
        val harness = Harness(store, { now })
        val engine = MockEngine {
            respond("""{"streaming":["https://a.example"]}""", HttpStatusCode.OK, headersOf("ETag", "\"v2\""))
        }
        try {
            val feed = harness.feed(engine)
            val results = coroutineScope { (1..8).map { async { feed.instances() } }.awaitAll() }
            assertTrue(results.all { it == listOf("https://a.example") })
            assertEquals(1, harness.requests.size, "eight concurrent startup callers share one request")
        } finally {
            engine.close()
        }
    }

    @Test
    fun aBackgroundedHostDoesNotPoll() = runTest {
        val now = 1_000_000L
        val store = MemoryPoolDiscoveryStore(PoolDiscoveryState(instances = listOf("https://a.example")))
        val harness = Harness(store, { now })
        val engine = MockEngine { respond("""{"streaming":["https://b.example"]}""", HttpStatusCode.OK) }
        try {
            val feed = harness.feed(engine)
            PoolPolling.foreground = false
            assertEquals(listOf("https://a.example"), feed.instances())
            assertEquals(emptyList(), harness.requests)

            PoolPolling.foreground = true
            assertEquals(listOf("https://b.example"), feed.instances())
            assertEquals(1, harness.requests.size)
        } finally {
            engine.close()
        }
    }

    @Test
    fun aSuccessfulAnswerPersistsTheListAndItsEtag() = runTest {
        val now = 1_000_000L
        val store = MemoryPoolDiscoveryStore()
        val harness = Harness(store, { now })
        val engine = MockEngine {
            respond("""{"api":["https://c.example"]}""", HttpStatusCode.OK, headersOf("ETag", "\"v3\""))
        }
        try {
            val feed = harness.feed(engine)
            assertEquals(listOf("https://c.example"), feed.instances())
            val persisted = store.load()
            assertEquals(listOf("https://c.example"), persisted?.instances)
            assertEquals("\"v3\"", persisted?.etag)
            assertEquals(now, persisted?.fetchedAtMs)
        } finally {
            engine.close()
        }
    }

    @Test
    fun aFailedReadKeepsThePreviousListAndPausesBeforeAskingAgain() = runTest {
        var now = 1_000_000L
        val store = MemoryPoolDiscoveryStore(PoolDiscoveryState(instances = listOf("https://a.example")))
        val harness = Harness(store, { now })
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        try {
            val feed = harness.feed(engine)
            assertEquals(listOf("https://a.example"), feed.instances())
            assertEquals(1, harness.requests.size)

            now += 30_000L
            assertEquals(listOf("https://a.example"), feed.instances())
            assertEquals(1, harness.requests.size, "a transport/host failure buys a short pause")
        } finally {
            engine.close()
        }
    }

    @Test
    fun theTtlIsSixHoursWithJitter() {
        assertEquals(6L * 60L * 60L * 1000L, PoolDiscoveryPolicy.TTL_MS)
        val ttl = PoolDiscoveryPolicy.ttlMs(seed = 1_000L)
        assertTrue(ttl in (PoolDiscoveryPolicy.TTL_MS * 80L / 100)..(PoolDiscoveryPolicy.TTL_MS * 120L / 100))
        val fresh = PoolDiscoveryState(instances = listOf("a"), fetchedAtMs = 1_000L)
        assertTrue(!PoolDiscoveryPolicy.shouldFetch(fresh, 1_000L + ttl - 1L, foreground = true), "inside the TTL")
        assertTrue(PoolDiscoveryPolicy.shouldFetch(fresh, 1_000L + ttl, foreground = true), "the TTL has elapsed")
        assertTrue(!PoolDiscoveryPolicy.shouldFetch(fresh, 1_000L + ttl, foreground = false), "and never while backgrounded")
    }
}

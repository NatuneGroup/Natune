/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW_MS = 1_800_000_000_000L
private const val NOW_SEC = NOW_MS / 1000L
private const val DAY_SEC = 86_400L
private const val HOUR_MS = 3_600_000L

/** Two valid-looking web player tokens; the signature is never checked, only the claims. */
private val TOKEN_A = webPlayJwt(expSec = NOW_SEC + 30 * DAY_SEC)
private val TOKEN_B = webPlayJwt(expSec = NOW_SEC + 31 * DAY_SEC)

private const val TTML =
    """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="0s">Hello</p></div></body></tt>"""
private const val SEARCH_HIT =
    """{"results":{"songs":{"data":[{"id":"111","attributes":{"name":"Song","artistName":"Artist","durationInMillis":200000}}]}}}"""

/**
 * A structurally valid `AMPWebPlay` token. The header and payload are base64url, as Apple's are, so
 * the regex and the claim readers see the same shape the live token has.
 */
private fun webPlayJwt(expSec: Long): String =
    urlSafe(base64Of("""{"alg":"ES256","typ":"JWT","kid":"WebPlayKid"}""")) + "." +
        urlSafe(base64Of("""{"iss":"AMPWebPlay","exp":$expSec}""")) + ".c2lnbmF0dXJlMTIz"

private fun urlSafe(standard: String): String = standard.replace('+', '-').replace('/', '_').trimEnd('=')

private fun landing(token: String): CannedReply =
    CannedReply(body = "<html><script>window.token=\"$token\";</script></html>", contentType = "text/html")

private val EMPTY_LANDING = CannedReply(body = "<html></html>", contentType = "text/html")

/** Advances only when a test asks, so token expiry and the refresh throttle are deterministic. */
private class TestClock(var nowMs: Long = NOW_MS) {
    fun now(): Long = nowMs
}

private fun answeringWith(
    seen: MutableList<HttpRequestData>,
    answer: (HttpRequestData) -> CannedReply,
): HttpClient =
    HttpClient(
        MockEngine { request ->
            seen += request
            val reply = answer(request)
            respond(
                content = reply.body,
                status = reply.status,
                headers = headersOf(HttpHeaders.ContentType, reply.contentType),
            )
        },
    )

/** One engine for the three hosts the Apple Music variant talks to, routed by host. */
private fun appleClient(
    seen: MutableList<HttpRequestData>,
    home: (HttpRequestData) -> CannedReply = { landing(TOKEN_A) },
    amp: (HttpRequestData) -> CannedReply = { CannedReply(SEARCH_HIT) },
    paxsenix: (HttpRequestData) -> CannedReply = { CannedReply("", HttpStatusCode.NotFound) },
): HttpClient =
    answeringWith(seen) { request ->
        when (request.url.host) {
            "music.apple.com" -> home(request)
            "amp-api.music.apple.com" -> amp(request)
            "lyrics.paxsenix.org" -> paxsenix(request)
            else -> CannedReply("", HttpStatusCode.NotFound)
        }
    }

private fun appleProvider(
    client: HttpClient,
    clock: TestClock = TestClock(),
    settings: PaxsenixSettings = PaxsenixSettings(),
): PaxsenixAppleMusicLyricsProvider =
    PaxsenixAppleMusicLyricsProvider(client, PaxsenixAppleMusicToken(client, nowMillis = clock::now), settings)

class LyricsProviderPaxsenixAppleMusicTest {
    @Test
    fun tokenIsScrapedFromTheLandingPageWithTheWebPlayerUserAgent() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val token = PaxsenixAppleMusicToken(answeringWith(seen) { landing(TOKEN_A) }, nowMillis = { NOW_MS })

            assertEquals(TOKEN_A, token.ensureFresh())

            val request = seen.single()
            assertEquals("https://music.apple.com/", request.url.toString())
            assertTrue(request.headers[HttpHeaders.UserAgent].orEmpty().startsWith("Mozilla/5.0"))
        }

    @Test
    fun tokenIsReadFromTheEntryBundleWhenTheLandingPageHasNoToken() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val client =
                answeringWith(seen) { request ->
                    when (request.url.encodedPath) {
                        "/" -> CannedReply("""<script src="/assets/index~3f9a.js"></script>""", contentType = "text/html")
                        "/assets/index~3f9a.js" -> CannedReply("""var t="$TOKEN_A";""", contentType = "application/javascript")
                        else -> CannedReply("", HttpStatusCode.NotFound)
                    }
                }

            assertEquals(TOKEN_A, PaxsenixAppleMusicToken(client, nowMillis = { NOW_MS }).ensureFresh())
            assertEquals(listOf("/", "/assets/index~3f9a.js"), seen.map { it.url.encodedPath })
        }

    @Test
    fun heldTokenIsReusedAcrossCallsWithoutAnotherScrape() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val token = PaxsenixAppleMusicToken(answeringWith(seen) { landing(TOKEN_A) }, nowMillis = { NOW_MS })

            assertEquals(TOKEN_A, token.ensureFresh())
            assertEquals(TOKEN_A, token.ensureFresh())

            assertEquals(1, seen.size)
        }

    @Test
    fun heldTokenInsideTheRefreshMarginIsReplacedByAScrape() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val clock = TestClock()
            val expiringSoon = webPlayJwt(expSec = NOW_SEC + 2 * DAY_SEC)
            var served = expiringSoon
            val token = PaxsenixAppleMusicToken(answeringWith(seen) { landing(served) }, nowMillis = clock::now)
            assertEquals(expiringSoon, token.ensureFresh())

            // 23 hours left is inside the 24-hour margin, so the next lookup scrapes again.
            clock.nowMs += 25 * HOUR_MS
            served = TOKEN_B
            assertEquals(TOKEN_B, token.ensureFresh())
            assertEquals(TOKEN_B, token.ensureFresh())

            assertEquals(2, seen.size)
        }

    @Test
    fun refreshAfterRejectionForcesAScrapeAndReturnsTheNewToken() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            var served = TOKEN_A
            val token = PaxsenixAppleMusicToken(answeringWith(seen) { landing(served) }, nowMillis = { NOW_MS })
            assertEquals(TOKEN_A, token.ensureFresh())

            served = TOKEN_B
            assertEquals(TOKEN_B, token.refreshAfterRejection(rejected = TOKEN_A))

            assertEquals(2, seen.size)
            assertEquals(TOKEN_B, token.ensureFresh())
        }

    @Test
    fun concurrentCallersShareOneScrape() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val inRequest = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        seen += request
                        inRequest.complete(Unit)
                        release.await()
                        respond(
                            content = landing(TOKEN_A).body,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/html"),
                        )
                    },
                )
            val token = PaxsenixAppleMusicToken(client, nowMillis = { NOW_MS })

            val callers = List(5) { async { token.ensureFresh() } }
            // The first caller is now inside the scrape. Draining the queue lets the others reach the lock
            // while it is held; a missing lock would show up here as a second request.
            inRequest.await()
            advanceUntilIdle()
            assertEquals(1, seen.size)

            release.complete(Unit)
            assertEquals(List(5) { TOKEN_A }, callers.awaitAll())
            assertEquals(1, seen.size)
        }

    @Test
    fun concurrentRejectionsShareOneRefresh() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val inRefresh = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        seen += request
                        if (seen.size == 2) {
                            inRefresh.complete(Unit)
                            release.await()
                        }
                        val html = if (seen.size == 1) landing(TOKEN_A) else landing(TOKEN_B)
                        respond(
                            content = html.body,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/html"),
                        )
                    },
                )
            val token = PaxsenixAppleMusicToken(client, nowMillis = { NOW_MS })
            assertEquals(TOKEN_A, token.ensureFresh())

            val rejections = List(3) { async { token.refreshAfterRejection(rejected = TOKEN_A) } }
            inRefresh.await()
            advanceUntilIdle()
            assertEquals(2, seen.size)

            release.complete(Unit)
            assertEquals(List(3) { TOKEN_B }, rejections.awaitAll())
            assertEquals(2, seen.size)
        }

    @Test
    fun failedRefreshServesTheStillValidHeldTokenAndIsThrottled() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val clock = TestClock()
            val expiringSoon = webPlayJwt(expSec = NOW_SEC + 2 * DAY_SEC)
            var served: String? = expiringSoon
            val home: (HttpRequestData) -> CannedReply = { served?.let { landing(it) } ?: EMPTY_LANDING }
            val token = PaxsenixAppleMusicToken(answeringWith(seen, home), nowMillis = clock::now)
            assertEquals(expiringSoon, token.ensureFresh())

            // The refresh window opens, Apple answers without a token, and the still-valid token is served.
            clock.nowMs += 25 * HOUR_MS
            served = null
            assertEquals(expiringSoon, token.ensureFresh())
            assertEquals(2, seen.size)

            // A lookup inside the one-minute throttle reuses the held token without another request.
            clock.nowMs += 1_000L
            assertEquals(expiringSoon, token.ensureFresh())
            assertEquals(2, seen.size)
        }

    @Test
    fun failedScrapeWithNothingHeldIsNullAndIsThrottled() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val clock = TestClock()
            val token = PaxsenixAppleMusicToken(answeringWith(seen) { EMPTY_LANDING }, nowMillis = clock::now)

            assertNull(token.ensureFresh())
            clock.nowMs += 1_000L
            assertNull(token.ensureFresh())
            assertEquals(1, seen.size)

            clock.nowMs += 61_000L
            assertNull(token.ensureFresh())
            assertEquals(2, seen.size)
        }

    @Test
    fun validUserDevBearerWinsWithoutAnyRequest() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val devBearer = webPlayJwt(expSec = NOW_SEC + DAY_SEC)
            val token =
                PaxsenixAppleMusicToken(
                    answeringWith(seen) { landing(TOKEN_A) },
                    userDevBearer = { "  $devBearer  " },
                    nowMillis = { NOW_MS },
                )

            assertEquals(devBearer, token.ensureFresh())
            assertTrue(seen.isEmpty())
        }

    @Test
    fun expiredUserDevBearerFallsBackToTheScrape() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val expiredBearer = webPlayJwt(expSec = NOW_SEC - 10)
            val token =
                PaxsenixAppleMusicToken(
                    answeringWith(seen) { landing(TOKEN_A) },
                    userDevBearer = { expiredBearer },
                    nowMillis = { NOW_MS },
                )

            assertEquals(TOKEN_A, token.ensureFresh())
            assertEquals(1, seen.size)
        }

    @Test
    fun unreadableUserDevBearerCountsAsUsable() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val token =
                PaxsenixAppleMusicToken(
                    answeringWith(seen) { landing(TOKEN_A) },
                    userDevBearer = { "not-a-jwt" },
                    nowMillis = { NOW_MS },
                )

            assertEquals("not-a-jwt", token.ensureFresh())
            assertTrue(seen.isEmpty())
        }

    @Test
    fun lyricsTtmlDocumentIsReturnedAndAmpCarriesOnlyTheAppleBearer() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val client =
                appleClient(
                    seen,
                    paxsenix = { request ->
                        if (request.url.parameters["ttml"] == "true") CannedReply(TTML) else CannedReply("", HttpStatusCode.NotFound)
                    },
                )
            val provider = appleProvider(client, settings = PaxsenixSettings(bearerValue = "paxsenix-key"))

            assertEquals(TTML, provider.getLyrics("id", "Song", "Artist", null, 200).getOrThrow())
            assertEquals("Paxsenix: Apple Music", provider.name)

            val amp = seen.single { it.url.host == "amp-api.music.apple.com" }
            assertEquals("https://amp-api.music.apple.com/v1/catalog/us/search", amp.url.toString().substringBefore("?"))
            assertEquals("Song Artist", amp.url.parameters["term"])
            assertEquals("songs", amp.url.parameters["types"])
            assertEquals("10", amp.url.parameters["limit"])
            assertEquals("Bearer $TOKEN_A", amp.headers[HttpHeaders.Authorization])
            assertEquals("https://music.apple.com", amp.headers["Origin"])
            assertEquals("https://music.apple.com/", amp.headers["Referer"])

            val lyrics = seen.single { it.url.host == "lyrics.paxsenix.org" }
            assertEquals("https://lyrics.paxsenix.org/apple-music/lyrics", lyrics.url.toString().substringBefore("?"))
            assertEquals("111", lyrics.url.parameters["id"])
            assertEquals("true", lyrics.url.parameters["ttml"])
            assertEquals("Bearer paxsenix-key", lyrics.headers[HttpHeaders.Authorization])
        }

    @Test
    fun jsonWrappedTtmlInsideTheContentFieldIsReturned() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val wrapped = """{"content":${jsonStringLiteral(TTML)}}"""
            val provider =
                appleProvider(
                    appleClient(seen, paxsenix = { request ->
                        if (request.url.parameters["ttml"] == "true") CannedReply(wrapped) else CannedReply("", HttpStatusCode.NotFound)
                    }),
                )

            assertEquals(TTML, provider.getLyrics("id", "Song", "Artist", null, 200).getOrThrow())
        }

    @Test
    fun ttmlMissFallsBackToTheTimedJsonAsLrc() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val timed =
                """{"type":"Line","content":[""" +
                    """{"timestamp":12345,"text":[{"text":"Hello","timestamp":12345},{"text":"world","timestamp":12800}]},""" +
                    """{"timestamp":61040,"text":[{"text":"Second"}]}]}"""
            val provider =
                appleProvider(
                    appleClient(seen, paxsenix = { request ->
                        if (request.url.parameters["ttml"] == "true") CannedReply("""{"type":"Line"}""") else CannedReply(timed)
                    }),
                )

            assertEquals(
                "[00:12.34]Hello world\n[01:01.04]Second",
                provider.getLyrics("id", "Song", "Artist", null, 200).getOrThrow(),
            )
            assertEquals(
                listOf("true", null),
                seen.filter { it.url.host == "lyrics.paxsenix.org" }.map { it.url.parameters["ttml"] },
            )
        }

    @Test
    fun noTokenFailsBeforeAnySearchRequest() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider = appleProvider(appleClient(seen, home = { EMPTY_LANDING }))

            val error = provider.getLyrics("id", "Song", "Artist", null, 200).exceptionOrNull()

            assertEquals("No Apple Music web player token available", error?.message)
            assertTrue(seen.all { it.url.host == "music.apple.com" })
        }

    @Test
    fun ampUnauthorizedRetriesOnceWithARefreshedToken() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            var homeCalls = 0
            val client =
                appleClient(
                    seen,
                    home = {
                        homeCalls += 1
                        landing(if (homeCalls == 1) TOKEN_A else TOKEN_B)
                    },
                    amp = { request ->
                        if (request.headers[HttpHeaders.Authorization] == "Bearer $TOKEN_A") {
                            CannedReply("", HttpStatusCode.Unauthorized)
                        } else {
                            CannedReply(SEARCH_HIT)
                        }
                    },
                    paxsenix = { CannedReply(TTML) },
                )

            assertEquals(TTML, appleProvider(client).getLyrics("id", "Song", "Artist", null, 200).getOrThrow())

            assertEquals(2, homeCalls)
            assertEquals(
                listOf("Bearer $TOKEN_A", "Bearer $TOKEN_B"),
                seen.filter { it.url.host == "amp-api.music.apple.com" }.map { it.headers[HttpHeaders.Authorization] },
            )
        }

    @Test
    fun ampUnauthorizedWithAFailedRefreshEndsTheLookup() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            var homeCalls = 0
            val client =
                appleClient(
                    seen,
                    home = {
                        homeCalls += 1
                        if (homeCalls == 1) landing(TOKEN_A) else EMPTY_LANDING
                    },
                    amp = { CannedReply("", HttpStatusCode.Unauthorized) },
                )

            val error = appleProvider(client).getLyrics("id", "Song", "Artist", null, 200).exceptionOrNull()

            assertEquals("Apple Music lyrics unavailable", error?.message)
            assertEquals(1, seen.count { it.url.host == "amp-api.music.apple.com" })
        }

    @Test
    fun weakCatalogMatchIsRejectedBeforeAnyLyricsRequest() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val weak =
                """{"results":{"songs":{"data":[{"id":"999","attributes":""" +
                    """{"name":"Other Song","artistName":"Other Band","durationInMillis":500000}}]}}}"""
            val provider = appleProvider(appleClient(seen, amp = { CannedReply(weak) }))

            assertEquals(
                "Apple Music lyrics unavailable",
                provider.getLyrics("id", "Song", "Artist", null, 200).exceptionOrNull()?.message,
            )
            assertTrue(seen.none { it.url.host == "lyrics.paxsenix.org" })
        }

    @Test
    fun missOnBothLyricsRequestsIsAFailure() =
        runTest {
            val provider = appleProvider(appleClient(mutableListOf()))

            assertEquals(
                "Apple Music lyrics unavailable",
                provider.getLyrics("id", "Song", "Artist", null, 200).exceptionOrNull()?.message,
            )
        }
}

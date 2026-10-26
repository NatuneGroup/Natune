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
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Word-timed Musixmatch requests carry `type=word`, so they get their own route key under any mirror path. */
private fun musixmatchRoute(request: HttpRequestData): String =
    request.url.encodedPath + if (request.url.parameters["type"] == "word") "?word" else ""

class LyricsProviderPaxsenixTest {
    private val notFound = CannedReply(body = "", status = HttpStatusCode.NotFound)

    @Test
    fun musixmatchWordTimedRequestComesFirstWithTheSearchParameters() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                PaxsenixMusixmatchLyricsProvider(
                    routedClient(
                        seen,
                        mapOf("/musixmatch/lyrics?word" to CannedReply("""{"lyrics":"Word line one"}""")),
                        ::musixmatchRoute,
                    ),
                )

            val result = provider.getLyrics("id", "Song", "Artist", null, 200)

            assertEquals("Word line one", result.getOrThrow())
            assertEquals("Paxsenix: Musixmatch", provider.name)
            val request = seen.single()
            assertEquals("https://lyrics.paxsenix.org/musixmatch/lyrics", request.url.toString().substringBefore("?"))
            assertEquals("Song Artist", request.url.parameters["q"])
            assertEquals("Song", request.url.parameters["t"])
            assertEquals("Artist", request.url.parameters["a"])
            assertEquals("200", request.url.parameters["d"])
            assertEquals("word", request.url.parameters["type"])
        }

    @Test
    fun musixmatchFallsBackToThePlainRequestWhenTheWordTypeMisses() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                PaxsenixMusixmatchLyricsProvider(
                    routedClient(
                        seen,
                        mapOf("/musixmatch/lyrics" to CannedReply("""{"lyrics":"Plain line"}""")),
                        ::musixmatchRoute,
                    ),
                )

            assertEquals("Plain line", provider.getLyrics("id", "Song", "Artist", null, 200).getOrThrow())
            assertEquals(2, seen.size)
            assertNull(seen.last().url.parameters["type"])
        }

    @Test
    fun musixmatchNotFoundIsAFailure() =
        runTest {
            val provider = PaxsenixMusixmatchLyricsProvider(routedClient(mutableListOf(), emptyMap(), ::musixmatchRoute))

            val error = provider.getLyrics("id", "Song", "Artist", null, 200).exceptionOrNull()

            assertEquals("Musixmatch lyrics unavailable", error?.message)
        }

    @Test
    fun neteaseKaraokeLyricIsPreferredToTheLrc() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                PaxsenixNeteaseLyricsProvider(
                    routedClient(
                        seen,
                        mapOf(
                            "/netease/search" to CannedReply("""{"result":{"songs":[{"id":42,"duration":200000}]}}"""),
                            "/netease/lyrics" to
                                CannedReply("""{"klyric":{"lyric":"Karaoke line"},"lrc":{"lyric":"Plain line"}}"""),
                        ),
                    ),
                )

            assertEquals("Karaoke line", provider.getLyrics("id", "Song", "Artist", null, 200).getOrThrow())
            assertEquals("Paxsenix: NetEase", provider.name)
            assertEquals("Song Artist", seen.first().url.parameters["q"])
            assertEquals("42", seen.last().url.parameters["id"])
            assertEquals("true", seen.last().url.parameters["word"])
        }

    @Test
    fun neteaseFallsBackToTheLrcWhenThereIsNoKaraoke() =
        runTest {
            val provider =
                PaxsenixNeteaseLyricsProvider(
                    routedClient(
                        mutableListOf(),
                        mapOf(
                            "/netease/search" to CannedReply("""{"result":{"songs":[{"id":42,"duration":200000}]}}"""),
                            "/netease/lyrics" to CannedReply("""{"lrc":{"lyric":"Plain line"}}"""),
                        ),
                    ),
                )

            assertEquals("Plain line", provider.getLyrics("id", "Song", "Artist", null, 200).getOrThrow())
        }

    @Test
    fun neteaseCandidateMoreThanTenSecondsOffIsNeverFetched() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                PaxsenixNeteaseLyricsProvider(
                    routedClient(
                        seen,
                        mapOf("/netease/search" to CannedReply("""{"result":{"songs":[{"id":42,"duration":230000}]}}""")),
                    ),
                )

            assertTrue(provider.getLyrics("id", "Song", "Artist", null, 200).isFailure)
            assertEquals(listOf("/netease/search"), seen.map { it.url.encodedPath })
        }

    @Test
    fun neteaseNotFoundWhenTheSearchHasNoSongs() =
        runTest {
            val provider =
                PaxsenixNeteaseLyricsProvider(
                    routedClient(mutableListOf(), mapOf("/netease/search" to CannedReply("""{"result":{"songs":[]}}"""))),
                )

            assertEquals("NetEase lyrics unavailable", provider.getLyrics("id", "Song", "Artist", null, 200).exceptionOrNull()?.message)
        }

    @Test
    fun spotifyPicksTheClosestDurationAndReadsItsLyrics() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                PaxsenixSpotifyLyricsProvider(
                    routedClient(
                        seen,
                        mapOf(
                            "/spotify/search" to
                                CannedReply("""[{"id":"far","duration":100000},{"id":"near","duration":200500}]"""),
                            "/spotify/lyrics" to CannedReply("""{"lyrics":"Spotify line"}"""),
                        ),
                    ),
                )

            assertEquals("Spotify line", provider.getLyrics("id", "Song", "Artist", null, 200).getOrThrow())
            assertEquals("Paxsenix: Spotify", provider.name)
            assertEquals("near", seen.last().url.parameters["id"])
        }

    @Test
    fun spotifySearchErrorIsNotFound() =
        runTest {
            val provider =
                PaxsenixSpotifyLyricsProvider(
                    routedClient(
                        mutableListOf(),
                        mapOf("/spotify/search" to CannedReply(body = "", status = HttpStatusCode.InternalServerError)),
                    ),
                )

            assertEquals("Spotify lyrics unavailable", provider.getLyrics("id", "Song", "Artist", null, 200).exceptionOrNull()?.message)
        }

    @Test
    fun youTubeUsesTheTrackIdWhenIdIsMissingAndReadsItsLyrics() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                PaxsenixYouTubeLyricsProvider(
                    routedClient(
                        seen,
                        mapOf(
                            "/youtube/search" to CannedReply("""[{"trackId":"vid9","duration":"3:20"}]"""),
                            "/youtube/lyrics" to CannedReply("""{"lyrics":"YouTube line"}"""),
                        ),
                    ),
                )

            assertEquals("YouTube line", provider.getLyrics("id", "Song", "Artist", null, 200).getOrThrow())
            assertEquals("Paxsenix: YouTube", provider.name)
            assertEquals("vid9", seen.last().url.parameters["id"])
        }

    @Test
    fun youTubeNotFoundWhenTheSearchAnswersNotFound() =
        runTest {
            val provider = PaxsenixYouTubeLyricsProvider(routedClient(mutableListOf(), mapOf("/youtube/search" to notFound)))

            assertTrue(provider.getLyrics("id", "Song", "Artist", null, 200).isFailure)
        }

    @Test
    fun customEndpointGetsATrailingSlashAndABlankBearerIsNotSent() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                PaxsenixMusixmatchLyricsProvider(
                    routedClient(
                        seen,
                        mapOf("/lyrics/musixmatch/lyrics?word" to CannedReply("""{"lyrics":"Mirror line"}""")),
                        ::musixmatchRoute,
                    ),
                    PaxsenixSettings(baseUrl = "https://mirror.example/lyrics", bearerValue = "   ", userAgent = "ArchiveTune/9.9"),
                )

            assertEquals("Mirror line", provider.getLyrics("id", "Song", "Artist", null, 200).getOrThrow())
            assertTrue(seen.single().url.toString().startsWith("https://mirror.example/lyrics/musixmatch/lyrics?"))
            assertEquals("ArchiveTune/9.9", seen.single().headers[HttpHeaders.UserAgent])
            assertFalse(seen.single().headers.contains(HttpHeaders.Authorization))
        }

    @Test
    fun configuredBearerIsSentAsAuthorizationOnPaxsenixRequests() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                PaxsenixMusixmatchLyricsProvider(
                    routedClient(seen, mapOf("/musixmatch/lyrics?word" to CannedReply("""{"lyrics":"Line"}""")), ::musixmatchRoute),
                    PaxsenixSettings(bearerValue = "  bearer-sample  "),
                )

            provider.getLyrics("id", "Song", "Artist", null, 200)

            assertEquals("Bearer bearer-sample", seen.single().headers[HttpHeaders.Authorization])
        }

    @Test
    fun settingsToStringDoesNotPrintTheBearer() {
        val rendered = PaxsenixSettings(bearerValue = "bearer-sample").toString()

        assertFalse(rendered.contains("bearer-sample"))
    }

    @Test
    fun probeReportsEachProviderOnceWithItsFirstPathAndDoesNotFallBack() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val probe = PaxsenixDiagnostics(probeClient(seen))

            val checks = probe.checkProviderPaths()

            assertEquals(
                listOf(
                    PaxsenixDiagnostics.PathCheck("Apple Music", "apple-music/lyrics", PaxsenixDiagnostics.PathStatus.AVAILABLE, 200),
                    PaxsenixDiagnostics.PathCheck("NetEase", "netease/search", PaxsenixDiagnostics.PathStatus.RETIRED, 403),
                    PaxsenixDiagnostics.PathCheck("Spotify", "spotify/search", PaxsenixDiagnostics.PathStatus.UNREACHABLE, 500),
                    PaxsenixDiagnostics.PathCheck("YouTube", "youtube/search", PaxsenixDiagnostics.PathStatus.AVAILABLE, 400),
                    PaxsenixDiagnostics.PathCheck("Musixmatch", "musixmatch/lyrics", PaxsenixDiagnostics.PathStatus.UNREACHABLE, null),
                ),
                checks,
            )
            // A miss on a provider's first path is reported, never retried on another path.
            assertEquals(
                listOf("/apple-music/lyrics", "/netease/search", "/spotify/search", "/youtube/search", "/musixmatch/lyrics"),
                seen.map { it.url.encodedPath },
            )
        }

    @Test
    fun probeSendsTheThrowawayParametersOnly() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()

            PaxsenixDiagnostics(probeClient(seen)).checkProviderPaths()

            assertEquals("1440857781", seen.first().url.parameters["id"])
            assertEquals("test", seen[1].url.parameters["q"])
            assertNull(seen[1].url.parameters["id"])
        }

    @Test
    fun probeRethrowsCancellationInsteadOfReportingUnreachable() =
        runTest {
            val cancelling = HttpClient(MockEngine { throw CancellationException("cancelled by test") })

            assertFailsWith<CancellationException> { PaxsenixDiagnostics(cancelling).checkProviderPaths() }
        }

    @Test
    fun getStatsDecodesTheDiagnosticsPayloadFromApiStats() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val body =
                """{"uptime_seconds":12.5,"started_at":"2026-01-01T00:00:00Z","total_requests":10,""" +
                    """"successful_requests":9,"failed_requests":1,"overall_success_rate":"90%",""" +
                    """"endpoints":{"apple-music/lyrics":{"hits":4,"errors":0,"success_rate":"100%",""" +
                    """"avg_response_time_ms":120.0,"last_accessed":"2026-01-01T00:00:01Z"}},""" +
                    """"providers":{"Apple Music":{"hits":4,"errors":0,"success_rate":"100%"}}}"""

            val stats = PaxsenixDiagnostics(routedClient(seen, mapOf("/api/stats" to CannedReply(body)))).getStats().getOrThrow()

            assertEquals(10, stats.totalRequests)
            assertEquals("90%", stats.overallSuccessRate)
            assertEquals(4, stats.endpoints.getValue("apple-music/lyrics").hits)
            assertEquals(4, stats.providers.getValue("Apple Music").hits)
            assertEquals("/api/stats", seen.single().url.encodedPath)
        }

    @Test
    fun getStatsNonOkIsAFailure() =
        runTest {
            val result =
                PaxsenixDiagnostics(routedClient(mutableListOf(), mapOf("/api/stats" to CannedReply("", HttpStatusCode.InternalServerError))))
                    .getStats()

            assertTrue(result.isFailure)
        }

    /**
     * Answers each probe path with a fixed status. Musixmatch throws, which the probe must report as
     * unreachable rather than as an error.
     */
    private fun probeClient(seen: MutableList<HttpRequestData>): HttpClient =
        HttpClient(
            MockEngine { request ->
                seen += request
                when (request.url.encodedPath) {
                    "/apple-music/lyrics" -> respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    "/netease/search" -> respond("", HttpStatusCode.Forbidden)
                    "/spotify/search" -> respond("", HttpStatusCode.InternalServerError)
                    "/youtube/search" -> respond("", HttpStatusCode.BadRequest)
                    else -> throw IllegalStateException("connection reset")
                }
            },
        )
}

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
import moe.rukamori.archivetune.shared.domain.lyrics.KaraokeLine
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsDetection
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsParser
import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsProviderBetterLyricsTest {
    /** Word-timed TTML, as the BetterLyrics TTML endpoint returns it. */
    private val karaokeTtml =
        """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal">""" +
            """<body><div><p begin="1.000" end="2.000">""" +
            """<span begin="1.000" end="1.500">Hello</span> <span begin="1.500" end="2.000">world</span>""" +
            """</p></div></body></tt>"""

    private val lineOnlyTtml =
        """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1.000" end="2.000">Hello world</p></div></body></tt>"""

    /** QRC as the Android client would receive it inside a JSON envelope. */
    private val qrc = "[1000,2000]Hello(1000,800) world(1800,1200)"

    private val ttmlContentType = "application/xml"

    private fun answering(
        seen: MutableList<HttpRequestData>,
        routes: Map<String, CannedReply>,
    ) = BetterLyricsProvider(routedClient(seen, routes))

    /** Word times come from decimal seconds, so compare with a tolerance rather than exact doubles. */
    private fun assertTimes(
        expected: List<Double>,
        actual: List<Double>,
    ) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (want, got) -> assertEquals(want, got, 0.0001) }
    }

    @Test
    fun ttmlBodyIsReturnedVerbatimFromTheTtmlEndpointWithTheSearchQuery() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider = answering(seen, mapOf("/getLyrics" to CannedReply(karaokeTtml, contentType = ttmlContentType)))

            val result = provider.getLyrics("id", "Song", "Artist", "Album", 200)

            assertEquals(karaokeTtml, result.getOrThrow())
            assertEquals("BetterLyrics", provider.name)
            assertEquals(1, seen.size)
            assertEquals("https://lyrics-api.boidu.dev/getLyrics?s=Song&a=Artist&al=Album&d=200", seen.single().url.toString())
        }

    @Test
    fun jsonEnvelopeReturnsTheTtmlOrItsLyricsAlias() =
        runTest {
            val envelope = """{"lyrics":${jsonStringLiteral(karaokeTtml)}}"""
            val provider = answering(mutableListOf(), mapOf("/getLyrics" to CannedReply(envelope)))

            assertEquals(karaokeTtml, provider.getLyrics("id", "Song", "Artist", null, 0).getOrThrow())
        }

    @Test
    fun emptyTtmlFallsThroughToTheKugouEndpointWithoutOptionalParameters() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                answering(
                    seen,
                    mapOf(
                        "/getLyrics" to CannedReply("""{"ttml":""}"""),
                        "/kugou/getLyrics" to CannedReply(karaokeTtml, contentType = ttmlContentType),
                    ),
                )

            val result = provider.getLyrics("id", "Song", "Artist", null, 0)

            assertEquals(karaokeTtml, result.getOrThrow())
            assertEquals(listOf("/getLyrics", "/kugou/getLyrics"), seen.map { it.url.encodedPath })
            assertEquals("https://lyrics-api.boidu.dev/kugou/getLyrics?s=Song&a=Artist", seen.last().url.toString())
        }

    @Test
    fun transportErrorOnTheTtmlEndpointFallsThroughToKugou() =
        runTest {
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath == "/getLyrics") throw IllegalStateException("transport down")
                        respond(karaokeTtml, headers = headersOf(HttpHeaders.ContentType, ttmlContentType))
                    },
                )

            val result = BetterLyricsProvider(client).getLyrics("id", "Song", "Artist", null, 200)

            assertEquals(karaokeTtml, result.getOrThrow())
        }

    @Test
    fun failureOnBothEndpointsIsLyricsUnavailable() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                answering(
                    seen,
                    mapOf(
                        "/getLyrics" to CannedReply("", HttpStatusCode.InternalServerError),
                        "/kugou/getLyrics" to CannedReply("", HttpStatusCode.InternalServerError),
                    ),
                )

            val result = provider.getLyrics("id", "Song", "Artist", null, 200)

            assertEquals("Lyrics unavailable", result.exceptionOrNull()?.message)
            assertEquals(2, seen.size)
        }

    @Test
    fun bareQrcBodyIsNotAcceptedAsADocument() =
        runTest {
            val provider =
                answering(
                    mutableListOf(),
                    mapOf(
                        "/getLyrics" to CannedReply(qrc, contentType = "text/plain"),
                        "/kugou/getLyrics" to CannedReply(qrc, contentType = "text/plain"),
                    ),
                )

            assertTrue(provider.getLyrics("id", "Song", "Artist", null, 200).isFailure)
        }

    @Test
    fun qrcInsideTheEnvelopeKeepsWordTimingThroughLyricsParse() =
        runTest {
            val envelope = """{"ttml":${jsonStringLiteral(qrc)}}"""
            val provider = answering(mutableListOf(), mapOf("/getLyrics" to CannedReply(envelope)))

            val result = provider.getLyrics("id", "Song", "Artist", null, 200).getOrThrow()

            assertEquals(qrc, result)
            val line = assertIs<KaraokeLine>(LyricsParser.parse(result).single())
            assertEquals(1_000L, line.time)
            assertTimes(listOf(1.0, 1.8), line.words.map { it.startTime })
            assertTimes(listOf(1.8, 3.0), line.words.map { it.endTime })
        }

    @Test
    fun wordTimedTtmlRoutesThroughParseTtmlWithItsWordTimingIntact() =
        runTest {
            val provider =
                answering(mutableListOf(), mapOf("/getLyrics" to CannedReply(karaokeTtml, contentType = ttmlContentType)))

            val result = provider.getLyrics("id", "Song", "Artist", null, 200).getOrThrow()

            assertTrue(LyricsDetection.isTtml(result))
            val line = assertIs<KaraokeLine>(LyricsParser.parseTtml(result).single())
            assertEquals(1_000L, line.time)
            assertEquals("Hello world", line.text)
            assertEquals(listOf("Hello", "world"), line.words.map { it.text.trim() })
            assertTimes(listOf(1.0, 1.5), line.words.map { it.startTime })
            assertTimes(listOf(1.5, 2.0), line.words.map { it.endTime })
        }

    @Test
    fun lineOnlyTtmlStaysSyncedWithoutInventedWordTiming() =
        runTest {
            val provider =
                answering(mutableListOf(), mapOf("/getLyrics" to CannedReply(lineOnlyTtml, contentType = ttmlContentType)))

            val result = provider.getLyrics("id", "Song", "Artist", null, 200).getOrThrow()

            val line = assertIs<SyncedLine>(LyricsParser.parseTtml(result).single())
            assertEquals(1_000L, line.time)
            assertEquals("Hello world", line.text)
            assertNull(line.words)
        }

    @Test
    fun getAllLyricsEmitsTheTtmlOnce() =
        runTest {
            val provider =
                answering(mutableListOf(), mapOf("/getLyrics" to CannedReply(karaokeTtml, contentType = ttmlContentType)))
            val emitted = mutableListOf<String>()

            provider.getAllLyrics("id", "Song", "Artist", null, 200) { emitted += it }

            assertEquals(listOf(karaokeTtml), emitted)
        }

    @Test
    fun portatoVariantUsesOnlyTheQqEndpointAndItsOwnName() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val client = routedClient(seen, mapOf("/qq/getLyrics" to CannedReply(karaokeTtml, contentType = ttmlContentType)))

            val provider = BetterLyricsProvider.portato(client)
            val result = provider.getLyrics("id", "Song", "Artist", "Album", 200)

            assertEquals("BetterLyrics Portato", provider.name)
            assertEquals(karaokeTtml, result.getOrThrow())
            assertEquals(1, seen.size)
            assertEquals("https://lyrics-api.boidu.dev/qq/getLyrics?s=Song&a=Artist&al=Album&d=200", seen.single().url.toString())
        }

    @Test
    fun portatoFailureUsesItsOwnMessage() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider = BetterLyricsProvider.portato(routedClient(seen, emptyMap()))

            val result = provider.getLyrics("id", "Song", "Artist", null, 200)

            assertEquals("Portato lyrics unavailable", result.exceptionOrNull()?.message)
            assertEquals(1, seen.size)
        }

    @Test
    fun blankArtistIsRejectedBeforeAnyRequest() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider = answering(seen, emptyMap())

            val result = provider.getLyrics("id", "Song", " ", null, 200)

            assertIs<IllegalArgumentException>(result.exceptionOrNull())
            assertTrue(seen.isEmpty())
        }
}

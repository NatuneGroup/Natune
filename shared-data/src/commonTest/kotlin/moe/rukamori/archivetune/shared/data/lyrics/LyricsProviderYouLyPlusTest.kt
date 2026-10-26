/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LyricsProviderYouLyPlusTest {
    private val hosts = listOf("lyricsplus.prjktla.my.id", "lyricsplus.prjktla.workers.dev", "lyricsplus-seven.vercel.app")
    private val ttmlPath = "/v1/ttml/get"
    private val lyricsPath = "/v2/lyrics/get"

    private val xml =
        """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1.000" end="2.000">Hello</p></div></body></tt>"""

    /** Answers [reply] on every mirror for [path], keyed by host plus path. */
    private fun everyMirror(
        path: String,
        reply: CannedReply,
    ): Map<String, CannedReply> = hosts.associate { "$it$path" to reply }

    private fun mirrorRoute(request: HttpRequestData): String = request.url.host + request.url.encodedPath

    private fun answering(
        seen: MutableList<HttpRequestData>,
        routes: Map<String, CannedReply>,
    ) = YouLyPlusLyricsProvider(routedClient(seen, routes, ::mirrorRoute))

    @Test
    fun ttmlIsReturnedFromTheFirstMirrorWithQueryAndHeaders() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider = answering(seen, everyMirror(ttmlPath, CannedReply(xml, contentType = "application/xml")))

            val result = provider.getLyrics("id", "Song", "Artist", "Album", 200)

            assertEquals(xml, result.getOrThrow())
            assertEquals("YouLyPlus", provider.name)
            assertEquals(1, seen.size)
            val request = seen.single()
            assertEquals(
                "https://lyricsplus.prjktla.my.id/v1/ttml/get?title=Song&artist=Artist&album=Album&duration=200",
                request.url.toString(),
            )
            assertEquals("application/json", request.headers[HttpHeaders.Accept])
            assertEquals("ArchiveTune", request.headers[HttpHeaders.UserAgent])
        }

    @Test
    fun ttmlJsonEnvelopeIsUnwrappedToItsXmlDocument() =
        runTest {
            val envelope = """{"ttml":${jsonStringLiteral(xml)}}"""
            val provider = answering(mutableListOf(), everyMirror(ttmlPath, CannedReply(envelope)))

            assertEquals(xml, provider.getLyrics("id", "Song", "Artist", null, -1).getOrThrow())
        }

    @Test
    fun ttmlWithoutXmlStartIsIgnoredAndTheLyricsPathAnswers() =
        runTest {
            val routes =
                everyMirror(ttmlPath, CannedReply("""{"ttml":"plain text"}""")) +
                    everyMirror(lyricsPath, CannedReply("""{"type":"Line","lyrics":[{"time":1000,"text":"hello"}]}"""))

            val result = answering(mutableListOf(), routes).getLyrics("id", "Song", "Artist", null, -1)

            assertEquals("[00:01.000]hello", result.getOrThrow())
        }

    @Test
    fun lineTypedLyricsBecomeOneLrcRowPerTimedLine() =
        runTest {
            val routes =
                everyMirror(ttmlPath, CannedReply("""{"ttml":null}""")) +
                    everyMirror(
                        lyricsPath,
                        CannedReply("""{"type":"Line","lyrics":[{"time":1000,"text":"hello"},{"time":2500,"text":"world"}]}"""),
                    )

            val result = answering(mutableListOf(), routes).getLyrics("id", "Song", "Artist", null, -1)

            assertEquals("[00:01.000]hello\n[00:02.500]world", result.getOrThrow())
        }

    @Test
    fun lineTypedLyricsIgnoreSyllablesAndWordTypedLyricsEmitThem() =
        runTest {
            val lineTyped =
                """{"type":"Line","lyrics":[{"time":1000,"text":"Hello","syllabus":[{"time":1000,"text":"Hel"}]}]}"""
            val wordTyped =
                """{"type":"Word","lyrics":[{"time":1000,"text":"Hello","syllabus":[""" +
                    """{"time":1000,"text":"Hel"},{"time":1500,"text":"lo"}]},{"time":65500,"text":"bye"}]}"""

            val lineResult =
                answering(mutableListOf(), everyMirror(lyricsPath, CannedReply(lineTyped)))
                    .getLyrics("id", "Song", "Artist", null, -1)
            val wordResult =
                answering(mutableListOf(), everyMirror(lyricsPath, CannedReply(wordTyped)))
                    .getLyrics("id", "Song", "Artist", null, -1)

            assertEquals("[00:01.000]Hello", lineResult.getOrThrow())
            assertEquals("[00:01.000]<00:01.000>Hel<00:01.500>lo\n[01:05.500]bye", wordResult.getOrThrow())
        }

    @Test
    fun untimedRowsAreTrimmedJoinedAndBlankRowsDropped() =
        runTest {
            val untimed = """{"lyrics":[{"text":"  a  "},{"text":"   "},{"text":"b"}]}"""
            val routes =
                everyMirror(ttmlPath, CannedReply("""{"ttml":null}""")) + everyMirror(lyricsPath, CannedReply(untimed))

            val result = answering(mutableListOf(), routes).getLyrics("id", "Song", "Artist", null, -1)

            assertEquals("a\nb", result.getOrThrow())
        }

    @Test
    fun failingMirrorMovesOnToTheNextMirror() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val routes =
                mapOf(
                    "lyricsplus.prjktla.my.id$ttmlPath" to CannedReply("", HttpStatusCode.InternalServerError),
                    "lyricsplus.prjktla.workers.dev$ttmlPath" to CannedReply(xml, contentType = "application/xml"),
                )

            val result = answering(seen, routes).getLyrics("id", "Song", "Artist", null, -1)

            assertEquals(xml, result.getOrThrow())
            assertTrue(seen.any { it.url.host == "lyricsplus.prjktla.workers.dev" })
        }

    @Test
    fun malformedBodyOnOneMirrorIsSkipped() =
        runTest {
            val routes =
                mapOf(
                    "lyricsplus.prjktla.my.id$ttmlPath" to CannedReply("{not json"),
                    "lyricsplus.prjktla.workers.dev$ttmlPath" to CannedReply(xml, contentType = "application/xml"),
                )

            assertEquals(xml, answering(mutableListOf(), routes).getLyrics("id", "Song", "Artist", null, -1).getOrThrow())
        }

    @Test
    fun notFoundOnEveryMirrorIsLyricsUnavailableAfterAllSixRequests() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()

            val result = answering(seen, emptyMap()).getLyrics("id", "Song", "Artist", null, -1)

            assertEquals("Lyrics unavailable", result.exceptionOrNull()?.message)
            assertEquals(hosts.size * 2, seen.size)
        }

    @Test
    fun blankTitleIsRejectedBeforeAnyRequest() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()

            val result = answering(seen, emptyMap()).getLyrics("id", "", "Artist", null, -1)

            assertIs<IllegalArgumentException>(result.exceptionOrNull())
            assertTrue(seen.isEmpty())
        }

    @Test
    fun getAllLyricsEmitsTheSameTextOnce() =
        runTest {
            val provider = answering(mutableListOf(), everyMirror(ttmlPath, CannedReply(xml, contentType = "application/xml")))
            val emitted = mutableListOf<String>()

            provider.getAllLyrics("id", "Song", "Artist", null, -1) { emitted += it }

            assertEquals(listOf(xml), emitted)
        }
}

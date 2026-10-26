package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LyricsProviderHttpTest {
    @Test
    fun creditsAreStrippedFromSearchTitles() {
        assertEquals("Track", "Track (Official Music Video)".forLyricsSearch())
        assertEquals("Song", "Song feat. Someone".forLyricsSearch())
        assertEquals("Song", "Song (feat. A)".forLyricsSearch())
        assertEquals("Song", "Song -".forLyricsSearch())
        assertEquals("Plain Title", "Plain   Title".forLyricsSearch())
    }

    @Test
    fun titleThatIsOnlyCreditsKeepsItsOriginalText() {
        assertEquals("(Official)", "(Official)".forLyricsSearch())
    }

    @Test
    fun topicSuffixIsRemovedFromArtistNames() {
        assertEquals("Artist", "Artist - Topic".artistForLyricsSearch())
        assertEquals("Band", "Band".artistForLyricsSearch())
    }

    @Test
    fun nonSuccessStatusHasNoBodyAndKeepsItsCode() =
        runTest {
            val client = HttpClient(MockEngine { jsonReply("nope", HttpStatusCode.ServiceUnavailable) })
            val response = LyricsHttp.getWithStatus(client, "https://example.test/lyrics")

            assertEquals(503, response.status)
            assertNull(response.body)
            assertEquals(503, assertFailsWith<LyricsHttpStatusException> { response.requireBody() }.status)
        }

    @Test
    fun successStatusCarriesItsBody() =
        runTest {
            val client = HttpClient(MockEngine { jsonReply("""{"ok":true}""") })
            val response = LyricsHttp.getWithStatus(client, "https://example.test/lyrics")

            assertEquals(200, response.status)
            assertEquals("""{"ok":true}""", response.requireBody())
        }

    @Test
    fun defaultUserAgentIsSentUnlessTheCallerSuppliesOne() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val client = HttpClient(MockEngine { request -> seen += request; jsonReply("{}") })

            LyricsHttp.getWithStatus(client, "https://example.test/a")
            LyricsHttp.getWithStatus(client, "https://example.test/b", defaultUserAgent = false)
            LyricsHttp.getWithStatus(client, "https://example.test/c", headers = mapOf(HttpHeaders.UserAgent to "custom/1"))

            assertEquals(LyricsHttp.USER_AGENT, seen[0].headers[HttpHeaders.UserAgent])
            assertNull(seen[1].headers[HttpHeaders.UserAgent])
            assertEquals("custom/1", seen[2].headers[HttpHeaders.UserAgent])
        }

    @Test
    fun transportFailuresPropagateToTheCaller() =
        runTest {
            val client = HttpClient(MockEngine { throw IllegalStateException("connection reset") })

            assertFailsWith<IllegalStateException> {
                LyricsHttp.getWithStatus(client, "https://example.test/lyrics")
            }
        }
}

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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsProviderLrcLibTest {
    private val syncedAndPlain =
        """[{"id":1,"trackName":"Song","artistName":"Artist","duration":200.0,""" +
            """"plainLyrics":"plain line","syncedLyrics":"[00:01.00]synced line","extraField":true}]"""

    private fun providerAnswering(
        body: String,
        seen: MutableList<HttpRequestData>,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): LrcLibLyricsProvider =
        LrcLibLyricsProvider(
            HttpClient(
                MockEngine { request ->
                    seen += request
                    jsonReply(body, status)
                },
            ),
        )

    @Test
    fun syncedLyricsWinOverPlainAndTheSearchQueryIsExact() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val result = providerAnswering(syncedAndPlain, seen).getLyrics("id-1", "Song", "Artist", null, 200)

            assertEquals("[00:01.00]synced line", result.getOrThrow())
            val request = seen.single()
            assertEquals("https://lrclib.net/api/search?track_name=Song&artist_name=Artist", request.url.toString())
            assertNull(request.headers[HttpHeaders.UserAgent])
        }

    @Test
    fun plainOnlyTrackReturnsPlainText() =
        runTest {
            val body =
                """[{"id":2,"trackName":"Song","artistName":"Artist","duration":200.0,""" +
                    """"plainLyrics":"plain only","syncedLyrics":null}]"""
            val result = providerAnswering(body, mutableListOf()).getLyrics("id", "Song", "Artist", null, 200)

            assertEquals("plain only", result.getOrThrow())
        }

    @Test
    fun albumIsNotForwardedBecauseTheAndroidWrapperNeverSentIt() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            providerAnswering(syncedAndPlain, seen).getLyrics("id", "Song", "Artist", "Album", 200)

            assertFalse(seen.single().url.toString().contains("album_name"))
        }

    @Test
    fun trackOutsideTheTwoSecondWindowIsNotAMatch() =
        runTest {
            val body =
                """[{"id":3,"trackName":"Song","artistName":"Artist","duration":300.0,""" +
                    """"plainLyrics":"late","syncedLyrics":"[00:01.00]late"}]"""
            val result = providerAnswering(body, mutableListOf()).getLyrics("id", "Song", "Artist", null, 200)

            assertEquals("Lyrics unavailable", result.exceptionOrNull()?.message)
        }

    @Test
    fun emptyAnswerIsAFailure() =
        runTest {
            val result = providerAnswering("[]", mutableListOf()).getLyrics("id", "Song", "Artist", null, 200)

            assertTrue(result.isFailure)
        }

    @Test
    fun emptyBodyIsAFailure() =
        runTest {
            val result = providerAnswering("", mutableListOf()).getLyrics("id", "Song", "Artist", null, 200)

            assertTrue(result.isFailure)
        }

    @Test
    fun serverErrorIsAFailureCarryingTheStatus() =
        runTest {
            val result =
                providerAnswering("[]", mutableListOf(), HttpStatusCode.ServiceUnavailable)
                    .getLyrics("id", "Song", "Artist", null, 200)

            assertEquals(503, assertIs<LyricsHttpStatusException>(result.exceptionOrNull()).status)
        }

    @Test
    fun unknownDurationPicksTheBestTitleAndArtistMatchWithoutSendingDuration() =
        runTest {
            val body =
                """[{"id":4,"trackName":"Other Song","artistName":"Other","duration":100.0,""" +
                    """"plainLyrics":null,"syncedLyrics":"[00:01.00]wrong"},""" +
                    """{"id":5,"trackName":"Song","artistName":"Artist","duration":250.0,""" +
                    """"plainLyrics":null,"syncedLyrics":"[00:01.00]right"}]"""
            val seen = mutableListOf<HttpRequestData>()
            val result = providerAnswering(body, seen).getLyrics("id", "Song", "Artist", null, -1)

            assertEquals("[00:01.00]right", result.getOrThrow())
            assertEquals("https://lrclib.net/api/search?track_name=Song&artist_name=Artist", seen.single().url.toString())
        }

    @Test
    fun getAllLyricsEmitsSyncedThenPlainOnceWithinTheBudget() =
        runTest {
            val body =
                """[{"id":6,"trackName":"Song","artistName":"Artist","duration":200.0,""" +
                    """"plainLyrics":"A plain","syncedLyrics":"[00:01.00]A synced"},""" +
                    """{"id":7,"trackName":"Song","artistName":"Artist","duration":201.0,""" +
                    """"plainLyrics":"B plain","syncedLyrics":"[00:01.00]B synced"}]"""
            val seen = mutableListOf<HttpRequestData>()
            val emitted = mutableListOf<String>()

            providerAnswering(body, seen).getAllLyrics("id", "Song", "Artist", null, 200) { emitted += it }

            assertEquals(listOf("[00:01.00]A synced", "A plain", "[00:01.00]B synced"), emitted)
            assertEquals("https://lrclib.net/api/search?track_name=Song&artist_name=Artist", seen.single().url.toString())
        }

    @Test
    fun getAllLyricsSkipsTracksOutsideTheDurationWindow() =
        runTest {
            val body =
                """[{"id":8,"trackName":"Song","artistName":"Artist","duration":230.0,""" +
                    """"plainLyrics":"far","syncedLyrics":"[00:01.00]far"}]"""
            val emitted = mutableListOf<String>()

            providerAnswering(body, mutableListOf()).getAllLyrics("id", "Song", "Artist", null, 200) { emitted += it }

            assertTrue(emitted.isEmpty())
        }
}

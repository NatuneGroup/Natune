package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The exact query is always sent first. A decorated title or multi-artist credit only costs a second query when the
 * exact answer cannot serve the request, and that second query carries the cleaned pair.
 */
class LyricsProviderLrcLibFallbackTest {
    private val track =
        """[{"id":1,"trackName":"Song","artistName":"Artist","duration":200.0,""" +
            """"plainLyrics":null,"syncedLyrics":"[00:01.00]the line"}]"""

    private val lateTrack =
        """[{"id":2,"trackName":"Song","artistName":"Artist","duration":300.0,""" +
            """"plainLyrics":null,"syncedLyrics":"[00:01.00]too late"}]"""

    /** Answers `answerFor` with the exact query's body and `fallbackBody` for everything the retry sends. */
    private fun provider(
        seen: MutableList<HttpRequestData>,
        answerFor: String,
        fallbackBody: String,
    ): LrcLibLyricsProvider =
        LrcLibLyricsProvider(
            HttpClient(
                MockEngine { request ->
                    seen += request
                    jsonReply(if (request.url.parameters["track_name"] == answerFor) "[]" else fallbackBody)
                },
            ),
        )

    private fun HttpRequestData.query(): String =
        "${url.parameters["track_name"]} by ${url.parameters["artist_name"]}"

    @Test
    fun aDecoratedTitleIsRetriedWithTheCleanedTitleAndPrimaryArtist() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val result =
                provider(seen, answerFor = "Song (feat. X)", fallbackBody = track)
                    .getLyrics("id", "Song (feat. X)", "A & B", null, 200)

            assertEquals("[00:01.00]the line", result.getOrThrow())
            assertEquals(listOf("Song (feat. X) by A & B", "Song by A"), seen.map { it.query() })
        }

    @Test
    fun theExactQueryWinsAndCostsNoSecondRequest() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                LrcLibLyricsProvider(
                    HttpClient(
                        MockEngine { request ->
                            seen += request
                            jsonReply(track)
                        },
                    ),
                )

            assertEquals("[00:01.00]the line", provider.getLyrics("id", "Song [ft. X]", "A, B", null, 200).getOrThrow())
            assertEquals(listOf("Song [ft. X] by A, B"), seen.map { it.query() })
        }

    @Test
    fun anExactAnswerOutsideTheDurationWindowFallsBackToTheCleanedQuery() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                LrcLibLyricsProvider(
                    HttpClient(
                        MockEngine { request ->
                            seen += request
                            jsonReply(if (request.url.parameters["track_name"] == "Song - feat. X") lateTrack else track)
                        },
                    ),
                )

            assertEquals("[00:01.00]the line", provider.getLyrics("id", "Song - feat. X", "Artist", null, 200).getOrThrow())
            assertEquals(listOf("Song - feat. X by Artist", "Song by Artist"), seen.map { it.query() })
        }

    @Test
    fun anUndecoratedPairThatMissesIsNotQueriedTwice() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val result =
                provider(seen, answerFor = "Song", fallbackBody = track)
                    .getLyrics("id", "Song", "Artist", null, 200)

            assertEquals("Lyrics unavailable", result.exceptionOrNull()?.message)
            assertEquals(listOf("Song by Artist"), seen.map { it.query() })
        }

    @Test
    fun bothQueriesMissIsOneFailureWithoutAThirdQuery() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val result =
                provider(seen, answerFor = "Song (feat. X)", fallbackBody = "[]")
                    .getLyrics("id", "Song (feat. X)", "A & B", null, 200)

            assertTrue(result.isFailure)
            assertEquals(2, seen.size)
        }

    @Test
    fun getAllLyricsAlsoRetriesTheCleanedQuery() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val emitted = mutableListOf<String>()

            provider(seen, answerFor = "Song (feat. X)", fallbackBody = track)
                .getAllLyrics("id", "Song (feat. X)", "A & B", null, 200) { emitted += it }

            assertEquals(listOf("[00:01.00]the line"), emitted)
            assertEquals(listOf("Song (feat. X) by A & B", "Song by A"), seen.map { it.query() })
        }

    @Test
    fun getAllLyricsKeepsTheExactQueryWhenItAlreadyServesTheDuration() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                LrcLibLyricsProvider(
                    HttpClient(
                        MockEngine { request ->
                            seen += request
                            jsonReply(track)
                        },
                    ),
                )

            provider.getAllLyrics("id", "Song (ft. X)", "A, B", null, 200) {}

            assertEquals(listOf("Song (ft. X) by A, B"), seen.map { it.query() })
        }
}

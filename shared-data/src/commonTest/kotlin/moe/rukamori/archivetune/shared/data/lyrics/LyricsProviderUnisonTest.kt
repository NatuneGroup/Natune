package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LyricsProviderUnisonTest {
    private fun summary(
        id: Long,
        lyrics: String?,
        videoId: String? = "abc",
    ): String {
        val lyricsField = lyrics?.let { "\"$it\"" } ?: "null"
        val videoField = videoId?.let { "\"$it\"" } ?: "null"
        return """{"id":$id,"videoId":$videoField,"song":"Song","artist":"Artist","lyrics":$lyricsField,""" +
            """"format":"lrc","syncType":"LINE_SYNCED"}"""
    }

    private fun search(vararg summaries: String): String = """{"success":true,"data":[${summaries.joinToString(",")}]}"""

    private fun entry(lyrics: String): String =
        """{"success":true,"data":{"id":9,"videoId":"abc","song":"Song","artist":"Artist",""" +
            """"lyrics":"$lyrics","format":"lrc","syncType":"LINE_SYNCED"}}"""

    @Test
    fun metadataSearchReturnsTheSummaryLyricsWithTheSearchQuery() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                UnisonLyricsProvider(
                    routedClient(seen, mapOf("/lyrics/search" to CannedReply(search(summary(7, "[00:01.00]first"))))),
                )

            val result = provider.getLyrics("abc", "Song", "Artist", "Album", 200)

            assertEquals("[00:01.00]first", result.getOrThrow())
            assertEquals("Unison", provider.name)
            assertEquals(1, seen.size)
            assertEquals(
                "https://unison.boidu.dev/lyrics/search?song=Song&artist=Artist&album=Album&duration=200&limit=5",
                seen.single().url.toString(),
            )
        }

    @Test
    fun albumAndDurationAreOmittedWhenAbsentOrZero() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                UnisonLyricsProvider(
                    routedClient(seen, mapOf("/lyrics/search" to CannedReply(search(summary(7, "[00:01.00]first"))))),
                )

            provider.getLyrics("abc", "Song", "Artist", null, 0)

            assertEquals(
                "https://unison.boidu.dev/lyrics/search?song=Song&artist=Artist&limit=5",
                seen.single().url.toString(),
            )
        }

    @Test
    fun summaryWithoutLyricsIsFetchedByItsVideoId() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                UnisonLyricsProvider(
                    routedClient(
                        seen,
                        mapOf(
                            "/lyrics/search" to CannedReply(search(summary(7, null, videoId = "abc"))),
                            "/lyrics" to CannedReply(entry("[00:02.00]by id")),
                        ),
                    ),
                )

            val result = provider.getLyrics("abc", "Song", "Artist", null, 200)

            assertEquals("[00:02.00]by id", result.getOrThrow())
            val lookup = seen.last()
            assertEquals("/lyrics", lookup.url.encodedPath)
            assertEquals("abc", lookup.url.parameters["v"])
        }

    @Test
    fun summaryWithoutLyricsOrVideoIdIsFetchedByItsNumericId() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                UnisonLyricsProvider(
                    routedClient(
                        seen,
                        mapOf(
                            "/lyrics/search" to CannedReply(search(summary(7, null, videoId = null))),
                            "/lyrics/7" to CannedReply(entry("[00:04.00]by numeric id")),
                        ),
                    ),
                )

            val result = provider.getLyrics("", "Song", "Artist", null, 200)

            assertEquals("[00:04.00]by numeric id", result.getOrThrow())
            assertEquals("/lyrics/7", seen.last().url.encodedPath)
        }

    @Test
    fun emptySearchFallsBackToTheVideoIdLookup() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                UnisonLyricsProvider(
                    routedClient(
                        seen,
                        mapOf(
                            "/lyrics/search" to CannedReply("""{"success":true,"data":[]}"""),
                            "/lyrics" to CannedReply(entry("[00:03.00]fallback")),
                        ),
                    ),
                )

            val result = provider.getLyrics("abc", "Song", "Artist", null, 200)

            assertEquals("[00:03.00]fallback", result.getOrThrow())
            assertEquals("abc", seen.last().url.parameters["v"])
        }

    @Test
    fun noMatchWithoutAVideoIdIsLyricsUnavailableAndAsksNoVideoIdLookup() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider = UnisonLyricsProvider(routedClient(seen, mapOf("/lyrics/search" to CannedReply(search()))))

            val result = provider.getLyrics("  ", "Song", "Artist", null, 200)

            assertEquals("Lyrics unavailable", result.exceptionOrNull()?.message)
            assertEquals(1, seen.size)
        }

    @Test
    fun malformedSearchBodyIsAFailure() =
        runTest {
            val provider =
                UnisonLyricsProvider(
                    routedClient(mutableListOf(), mapOf("/lyrics/search" to CannedReply("<html>not json</html>"))),
                )

            assertTrue(provider.getLyrics("", "Song", "Artist", null, 200).isFailure)
        }

    @Test
    fun serverErrorIsRetainedAndStopsTheLookup() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                UnisonLyricsProvider(
                    routedClient(
                        seen,
                        mapOf(
                            "/lyrics/search" to CannedReply("{}", HttpStatusCode.InternalServerError),
                            "/lyrics" to CannedReply("{}", HttpStatusCode.InternalServerError),
                        ),
                    ),
                )

            val result = provider.getLyrics("abc", "Song", "Artist", null, 200)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.isTransientLyricsFailure())
            assertEquals(1, seen.size)
        }

    @Test
    fun blankTitleIsRejectedBeforeAnyRequest() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider = UnisonLyricsProvider(routedClient(seen, emptyMap()))

            val result = provider.getLyrics("abc", "   ", "Artist", null, 200)

            assertIs<IllegalArgumentException>(result.exceptionOrNull())
            assertTrue(seen.isEmpty())
        }

    @Test
    fun allResultsReportTheSearchOutageAndStillEmitAvailableVariants() =
        runTest {
            val provider = UnisonLyricsProvider(
                routedClient(
                    mutableListOf(),
                    mapOf(
                        "/lyrics/search" to CannedReply("", HttpStatusCode.ServiceUnavailable),
                        "/lyrics/variants/abc" to CannedReply(search(summary(4, "variant lyrics"))),
                    ),
                ),
            )
            val failures = mutableListOf<Throwable>()
            val emitted = mutableListOf<String>()

            provider.getAllLyrics("abc", "Song", "Artist", null, 200, onFailure = failures::add) { emitted += it }

            assertEquals(listOf("variant lyrics"), emitted)
            assertEquals(1, failures.size)
            assertTrue(failures.single().isTransientLyricsFailure())
        }

    @Test
    fun getAllLyricsEmitsSearchResultsInOrderAndStopsAtFive() =
        runTest {
            val summaries = (1..6).map { summary(it.toLong(), "line $it") }
            val routes = mapOf("/lyrics/search" to CannedReply(search(*summaries.toTypedArray())))
            val provider = UnisonLyricsProvider(routedClient(mutableListOf(), routes))
            val emitted = mutableListOf<String>()

            provider.getAllLyrics("abc", "Song", "Artist", null, 200) { emitted += it }

            assertEquals(listOf("line 1", "line 2", "line 3", "line 4", "line 5"), emitted)
        }

    @Test
    fun getAllLyricsFallsBackToVariantsWhenSearchIsEmpty() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val variants = """{"success":true,"data":[${summary(4, "variant lyrics")}]}"""
            val provider =
                UnisonLyricsProvider(
                    routedClient(
                        seen,
                        mapOf(
                            "/lyrics/search" to CannedReply(search()),
                            "/lyrics/variants/abc" to CannedReply(variants),
                        ),
                    ),
                )
            val emitted = mutableListOf<String>()

            provider.getAllLyrics("abc", "Song", "Artist", null, 200) { emitted += it }

            assertEquals(listOf("variant lyrics"), emitted)
            assertEquals("/lyrics/variants/abc", seen.last().url.encodedPath)
        }
}

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
import kotlin.test.assertTrue

class LyricsProviderKuGouTest {
    private val songHit = """{"status":1,"errcode":0,"error":"","data":{"info":[{"duration":200,"hash":"HASH1"}]}}"""
    private val songMiss = """{"status":1,"errcode":0,"error":"","data":{"info":[{"duration":999,"hash":"HASH2"}]}}"""
    private val hashHit = """{"status":200,"info":"ok","errcode":0,"errmsg":"","expire":0,"candidates":[{"id":77,"product_from":"official","duration":200,"accesskey":"KEY77"}]}"""
    private val keywordHit = """{"status":200,"info":"ok","errcode":0,"errmsg":"","expire":0,"candidates":[{"id":88,"product_from":"official","duration":200,"accesskey":"KEY88"}]}"""
    private val noCandidates = """{"status":200,"info":"ok","errcode":0,"errmsg":"","expire":0,"candidates":[]}"""

    // The first and last lines are removed by the Android cut rules; the apostrophe is an HTML entity.
    private val rawLrc = "ti:Title\n[00:00.00]Lyrics by: Someone\n[00:01.00]It&apos;s Line one\n[00:05.00]Line two"
    private val normalizedLrc = "[00:01.00]It's Line one\n[00:05.00]Line two"

    private fun download(text: String): String = """{"content":"${base64Of(text)}"}"""

    /** Routes by a URL fragment, so each step of the flow gets its own canned answer. */
    private fun routed(
        seen: MutableList<HttpRequestData>,
        vararg routes: Pair<String, String>,
    ): KuGouLyricsProvider =
        KuGouLyricsProvider(
            HttpClient(
                MockEngine { request ->
                    seen += request
                    val url = request.url.toString()
                    val route = routes.firstOrNull { (fragment, _) -> url.contains(fragment) }
                    if (route == null) jsonReply("{}", HttpStatusCode.NotFound) else jsonReply(route.second)
                },
            ),
        )

    @Test
    fun songThenHashThenDownloadReturnsTheNormalisedLrc() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                routed(
                    seen,
                    "api/v3/search/song" to songHit,
                    "hash=HASH1" to hashHit,
                    "id=77" to download(rawLrc),
                )

            val result = provider.getLyrics("id", "Song", "Artist", null, 200)

            assertEquals(normalizedLrc, result.getOrThrow())
            assertEquals(
                listOf(
                    "https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=8&showtype=0&keyword=Song%20-%20Artist",
                    "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=HASH1",
                    "https://lyrics.kugou.com/download?fmt=lrc&charset=utf8&client=pc&ver=1&id=77&accesskey=KEY77",
                ),
                seen.map { it.url.toString() },
            )
            assertFalse(seen.any { it.headers[HttpHeaders.UserAgent] != null })
        }

    @Test
    fun keywordSearchIsTheFallbackWhenNoSongMatchesTheDuration() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                routed(
                    seen,
                    "api/v3/search/song" to songMiss,
                    "duration=200000" to keywordHit,
                    "id=88" to download(rawLrc),
                )

            val result = provider.getLyrics("id", "Song", "Artist", null, 200)

            assertEquals(normalizedLrc, result.getOrThrow())
            assertEquals(
                "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&duration=200000&keyword=Song%20-%20Artist",
                seen[1].url.toString(),
            )
            assertEquals(3, seen.size)
        }

    @Test
    fun noCandidateAnywhereIsAFailure() =
        runTest {
            val provider =
                routed(
                    mutableListOf(),
                    "api/v3/search/song" to songHit,
                    "hash=HASH1" to noCandidates,
                    "duration=200000" to noCandidates,
                )

            val result = provider.getLyrics("id", "Song", "Artist", null, 200)

            assertEquals("No lyrics candidate", result.exceptionOrNull()?.message)
        }

    @Test
    fun unknownDurationOmitsTheDurationParameterAndAcceptsAnySong() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val provider =
                routed(
                    seen,
                    "api/v3/search/song" to songMiss,
                    "hash=HASH2" to hashHit,
                    "id=77" to download(rawLrc),
                )

            val result = provider.getLyrics("id", "Song", "Artist", null, -1)

            assertEquals(normalizedLrc, result.getOrThrow())
            assertFalse(seen.any { it.url.toString().contains("duration=") })
        }

    @Test
    fun serverErrorOnSongSearchIsAFailureCarryingTheStatus() =
        runTest {
            val provider = KuGouLyricsProvider(HttpClient(MockEngine { jsonReply("{}", HttpStatusCode.InternalServerError) }))

            val result = provider.getLyrics("id", "Song", "Artist", null, 200)

            assertEquals(500, assertIs<LyricsHttpStatusException>(result.exceptionOrNull()).status)
        }

    @Test
    fun malformedBase64DownloadIsAFailure() =
        runTest {
            val provider =
                routed(
                    mutableListOf(),
                    "api/v3/search/song" to songHit,
                    "hash=HASH1" to hashHit,
                    "id=77" to """{"content":"%%% not base64 %%%"}""",
                )

            assertTrue(provider.getLyrics("id", "Song", "Artist", null, 200).isFailure)
        }

    @Test
    fun getAllLyricsEmitsHashCandidatesBeforeKeywordCandidates() =
        runTest {
            val provider =
                routed(
                    mutableListOf(),
                    "api/v3/search/song" to songHit,
                    "hash=HASH1" to hashHit,
                    "duration=200000" to keywordHit,
                    "id=77" to download("[00:01.00]Hash line"),
                    "id=88" to download("[00:02.00]Keyword line"),
                )
            val emitted = mutableListOf<String>()

            provider.getAllLyrics("id", "Song", "Artist", null, 200) { emitted += it }

            assertEquals(listOf("[00:01.00]Hash line", "[00:02.00]Keyword line"), emitted)
        }

    @Test
    fun generateKeywordStripsBracketedTextAndJoinsArtistsWithIdeographicComma() {
        val keyword = KuGouLyricsProvider.generateKeyword("Song (Live)", "A & B")

        assertEquals("Song ", keyword.title)
        assertEquals("A、B", keyword.artist)
    }
}

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
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsProviderSimpMusicTest {
    private val singleTrack =
        """{"type":"success","data":[{"id":"1","videoId":"abc","songTitle":"Song","artistName":"Artist",""" +
            """"albumName":"Album","durationSeconds":200,"syncedLyrics":"[00:01.00]synced",""" +
            """"plainLyric":"plain","richSyncLyrics":null,"vote":3}]}"""

    private fun providerAnswering(
        body: String,
        seen: MutableList<HttpRequestData>,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): SimpMusicLyricsProvider =
        SimpMusicLyricsProvider(
            HttpClient(
                MockEngine { request ->
                    seen += request
                    jsonReply(body, status)
                },
            ),
        )

    private fun track(
        duration: Int,
        synced: String?,
        plain: String?,
    ): String {
        fun quoted(value: String?) = value?.let { "\"$it\"" } ?: "null"
        return """{"videoId":"abc","durationSeconds":$duration,"syncedLyrics":${quoted(synced)},"plainLyric":${quoted(plain)}}"""
    }

    @Test
    fun syncedLyricsAreReturnedForTheVideoIdWithTheSimpMusicHeaders() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val result = providerAnswering(singleTrack, seen).getLyrics("abc", "Song", "Artist", null, 200)

            assertEquals("[00:01.00]synced", result.getOrThrow())
            val request = seen.single()
            assertEquals("https://api-lyrics.simpmusic.org/v1/abc", request.url.toString())
            assertEquals("application/json", request.headers[HttpHeaders.Accept])
            assertEquals("SimpMusicLyrics/1.0", request.headers[HttpHeaders.UserAgent])
            assertEquals("application/json", request.headers[HttpHeaders.ContentType])
        }

    @Test
    fun plainLyricsAreUsedWhenNoSyncedLyricsExist() =
        runTest {
            val body = """{"type":"success","data":[${track(200, null, "plain only")}]}"""

            val result = providerAnswering(body, mutableListOf()).getLyrics("abc", "Song", "Artist", null, 200)

            assertEquals("plain only", result.getOrThrow())
        }

    @Test
    fun closestDurationWinsWhenSeveralTracksExistAndZeroTakesTheFirst() =
        runTest {
            val body =
                """{"type":"success","data":[${track(100, "[00:01.00]wrong", null)},${track(198, "[00:01.00]right", null)}]}"""

            val near = providerAnswering(body, mutableListOf()).getLyrics("abc", "Song", "Artist", null, 200)
            val first = providerAnswering(body, mutableListOf()).getLyrics("abc", "Song", "Artist", null, 0)

            assertEquals("[00:01.00]right", near.getOrThrow())
            assertEquals("[00:01.00]wrong", first.getOrThrow())
        }

    @Test
    fun nonSuccessTypeIsAFailure() =
        runTest {
            val body = """{"type":"error","data":[${track(200, "[00:01.00]x", null)}]}"""

            val result = providerAnswering(body, mutableListOf()).getLyrics("abc", "Song", "Artist", null, 200)

            assertEquals("Lyrics unavailable", result.exceptionOrNull()?.message)
        }

    @Test
    fun httpErrorIsAFailure() =
        runTest {
            val result =
                providerAnswering(singleTrack, mutableListOf(), HttpStatusCode.InternalServerError)
                    .getLyrics("abc", "Song", "Artist", null, 200)

            assertTrue(result.isFailure)
        }

    @Test
    fun emptyDataIsAFailure() =
        runTest {
            val result =
                providerAnswering("""{"type":"success","data":[]}""", mutableListOf())
                    .getLyrics("abc", "Song", "Artist", null, 200)

            assertTrue(result.isFailure)
        }

    @Test
    fun emptySyncedStringIsReturnedAsSuccessRatherThanFallingBackToPlain() =
        runTest {
            val body = """{"type":"success","data":[${track(200, "", "plain")}]}"""

            val result = providerAnswering(body, mutableListOf()).getLyrics("abc", "Song", "Artist", null, 200)

            assertEquals("", result.getOrThrow())
        }

    @Test
    fun getAllLyricsEmitsSyncedAndPlainWithinFiveSecondsAndSkipsTheRest() =
        runTest {
            val body =
                """{"type":"success","data":[${track(240, "[00:01.00]s3", null)},""" +
                    """${track(203, "[00:01.00]s2", "p2")},${track(200, "[00:01.00]s1", "p1")}]}"""
            val emitted = mutableListOf<String>()

            providerAnswering(body, mutableListOf()).getAllLyrics("abc", "Song", "Artist", null, 200) { emitted += it }

            assertEquals(listOf("[00:01.00]s1", "p1", "[00:01.00]s2"), emitted)
        }

    @Test
    fun getAllLyricsIsSilentOnHttpError() =
        runTest {
            val emitted = mutableListOf<String>()

            providerAnswering(singleTrack, mutableListOf(), HttpStatusCode.InternalServerError)
                .getAllLyrics("abc", "Song", "Artist", null, 200) { emitted += it }

            assertTrue(emitted.isEmpty())
        }
}

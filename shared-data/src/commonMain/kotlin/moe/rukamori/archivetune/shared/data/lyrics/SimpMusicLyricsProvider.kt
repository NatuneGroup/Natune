/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.decodeFromString
import kotlin.math.abs

/**
 * Port of the Android SimpMusic wrapper and `SimpMusicLyrics` client.
 *
 * [id] is a YouTube video id. The Android helper dropped SimpMusic for Telegram and local media
 * ids, and that gate belongs to the orchestrator: it must skip this provider for non-YouTube ids.
 */
class SimpMusicLyricsProvider(
    private val client: HttpClient,
) : LyricsProvider {
    override val name: String = "SimpMusic"

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String?,
    ): Result<String> =
        suspendRunCatching {
            val tracks = getLyricsByVideoId(id)
            if (tracks.isEmpty()) throw IllegalStateException("Lyrics unavailable")

            val bestMatch =
                if (durationSec > 0 && tracks.size > 1) {
                    tracks.minByOrNull { track -> abs((track.duration ?: 0) - durationSec) }
                } else {
                    tracks.firstOrNull()
                }

            // An empty synced string is still returned, as on Android, rather than falling through to plain.
            bestMatch?.syncedLyrics ?: bestMatch?.plainLyrics
                ?: throw IllegalStateException("Lyrics unavailable")
        }

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String?,
        callback: (String) -> Unit,
    ) {
        val tracks = getLyricsByVideoId(id)
        var count = 0
        var plain = 0

        val sortedTracks =
            if (durationSec > 0) {
                tracks.sortedBy { abs((it.duration ?: 0) - durationSec) }
            } else {
                tracks
            }

        sortedTracks.forEach { track ->
            if (count <= 4) {
                if (track.syncedLyrics != null && abs((track.duration ?: 0) - durationSec) <= 5) {
                    count++
                    callback(track.syncedLyrics)
                }
                if (track.plainLyrics != null && abs((track.duration ?: 0) - durationSec) <= 5 && plain == 0) {
                    count++
                    plain++
                    callback(track.plainLyrics)
                }
            }
        }
    }

    /** Any transport, status or decode error reads as no entries, as the Android client did. */
    private suspend fun getLyricsByVideoId(videoId: String): List<LyricsData> =
        suspendRunCatching {
            val response = LyricsHttp.getWithStatus(client, BASE_URL + videoId, headers = REQUEST_HEADERS)
            val body = response.body
            if (response.status != HttpStatusCode.OK.value || body == null) {
                emptyList()
            } else {
                val apiResponse = LyricsJson.decodeFromString<SimpMusicApiResponse>(body)
                if (apiResponse.success) apiResponse.data else emptyList()
            }
        }.getOrDefault(emptyList())

    private companion object {
        const val BASE_URL = "https://api-lyrics.simpmusic.org/v1/"

        // Sent by the Android default request; the User-Agent is this service's own identity string.
        val REQUEST_HEADERS =
            mapOf(
                HttpHeaders.Accept to "application/json",
                HttpHeaders.UserAgent to "SimpMusicLyrics/1.0",
                HttpHeaders.ContentType to "application/json",
            )
    }
}

package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.decodeFromString

/**
 * Port of the Android YouLyPlus wrapper and `YouLyPlus` client.
 *
 * Each path (TTML first, then the line/word lyrics) walks the mirror list in order. A mirror that
 * fails, answers non-2xx or returns an undecodable body is skipped; cancellation still propagates.
 * The download is returned as text, and parsing belongs to the shared domain `LyricsParser`.
 */
class YouLyPlusLyricsProvider(
    private val client: HttpClient,
) : LyricsProvider {
    override val name: String = "YouLyPlus"

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String?,
    ): Result<String> {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        val cleanAlbum = album?.trim().orEmpty()

        if (cleanTitle.isBlank() || cleanArtist.isBlank()) {
            return Result.failure(IllegalArgumentException("Song title and artist are required"))
        }

        return suspendRunCatching {
            fetchTtml(cleanTitle, cleanArtist, cleanAlbum, durationSec)
                ?: fetchLyricsAsLrc(cleanTitle, cleanArtist, cleanAlbum, durationSec)
                ?: throw IllegalStateException("Lyrics unavailable")
        }
    }

    private suspend fun fetchTtml(
        title: String,
        artist: String,
        album: String,
        durationSec: Int,
    ): String? =
        fetchFromMirrors(TTML_PATH, title, artist, album, durationSec) { body ->
            val trimmed = body.trim()
            val ttml =
                if (trimmed.startsWith("<")) {
                    trimmed
                } else {
                    LyricsJson.decodeFromString<YouLyPlusTtmlResponse>(body).ttml?.trim()
                }
            // Only an XML document counts; a JSON envelope carrying plain text is not TTML.
            ttml?.takeIf { it.isNotBlank() && it.startsWith("<") }
        }

    private suspend fun fetchLyricsAsLrc(
        title: String,
        artist: String,
        album: String,
        durationSec: Int,
    ): String? =
        fetchFromMirrors(LYRICS_PATH, title, artist, album, durationSec) { body ->
            LyricsJson.decodeFromString<YouLyPlusLyricsResponse>(body).toLyricsText()
        }

    private suspend fun fetchFromMirrors(
        path: String,
        title: String,
        artist: String,
        album: String,
        durationSec: Int,
        decode: (String) -> String?,
    ): String? {
        for (baseUrl in MIRROR_BASE_URLS) {
            currentCoroutineContext().ensureActive()
            val lyrics =
                suspendRunCatching {
                    LyricsHttp
                        .getWithStatus(client, baseUrl + path, headers = REQUEST_HEADERS) {
                            parameter("title", title)
                            parameter("artist", artist)
                            if (album.isNotBlank()) parameter("album", album)
                            if (durationSec > 0) parameter("duration", durationSec)
                        }.body
                        ?.let(decode)
                }.getOrNull()
            if (!lyrics.isNullOrBlank()) return lyrics
        }
        return null
    }

    /**
     * Timed lines become LRC: word-typed lines with syllable timestamps, otherwise the line text.
     * Untimed rows are joined as plain text. The timed branch returns as soon as timed rows exist.
     */
    private fun YouLyPlusLyricsResponse.toLyricsText(): String? {
        if (lyrics.isEmpty()) return null

        val timedLines = lyrics.filter { it.time != null }
        if (timedLines.isNotEmpty()) {
            return timedLines
                .joinToString("\n") { line ->
                    buildString {
                        append(formatLrcTimestamp(line.time ?: 0L, bracketed = true))
                        val syllables = line.syllabus.orEmpty().filter { !it.text.isNullOrBlank() && it.time != null }
                        if (type.equals("Word", ignoreCase = true) && syllables.isNotEmpty()) {
                            syllables.forEach { syllable ->
                                append(formatLrcTimestamp(syllable.time ?: 0L, bracketed = false))
                                append(syllable.text.orEmpty())
                            }
                        } else {
                            append(line.text.orEmpty())
                        }
                    }
                }.takeIf { it.isNotBlank() }
        }

        return lyrics
            .mapNotNull { it.text }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    }

    /**
     * Formats `mm:ss.mmm` by hand: `String.format` with a Locale is JVM-only, and the output must
     * match the Android `%02d:%02d.%03d` exactly for non-negative times.
     */
    private fun formatLrcTimestamp(
        timeMs: Long,
        bracketed: Boolean,
    ): String {
        val safeTime = timeMs.coerceAtLeast(0L)
        val minutes = (safeTime / 60000L).toString().padStart(2, '0')
        val seconds = ((safeTime % 60000L) / 1000L).toString().padStart(2, '0')
        val millis = (safeTime % 1000L).toString().padStart(3, '0')
        val timestamp = "$minutes:$seconds.$millis"
        return if (bracketed) "[$timestamp]" else "<$timestamp>"
    }

    private companion object {
        const val TTML_PATH = "v1/ttml/get"
        const val LYRICS_PATH = "v2/lyrics/get"

        // Ordered by observed reliability. Hosts that failed DNS or certificate checks were removed
        // on Android and are intentionally absent here.
        val MIRROR_BASE_URLS =
            listOf(
                "https://lyricsplus.prjktla.my.id/",
                "https://lyricsplus.prjktla.workers.dev/",
                "https://lyricsplus-seven.vercel.app/",
            )

        // Sent by the Android default request, so the mirrors see the same identity.
        val REQUEST_HEADERS =
            mapOf(
                HttpHeaders.Accept to "application/json",
                HttpHeaders.UserAgent to "ArchiveTune",
            )
    }
}

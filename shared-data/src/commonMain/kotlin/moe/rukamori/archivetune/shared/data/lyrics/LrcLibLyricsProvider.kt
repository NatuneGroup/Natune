package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.decodeFromString
import kotlin.math.abs

/**
 * Port of the Android LRCLIB wrapper and `LrcLib` client.
 *
 * [album] is accepted for the shared contract but never sent: the Android wrapper did not forward
 * it, so the same input must keep returning the same lyrics.
 */
class LrcLibLyricsProvider(
    private val client: HttpClient,
) : LyricsProvider {
    override val name: String = "LrcLib"

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String?,
    ): Result<String> =
        suspendRunCatching {
            firstSelection(title, artist) { tracks ->
                tracks.bestMatchingFor(durationSec, title, artist)?.preferredLyrics()
            } ?: throw IllegalStateException("Lyrics unavailable")
        }

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String?,
        onFailure: (Throwable) -> Unit,
        callback: (String) -> Unit,
    ) {
        try {
            // A decorated title that answers nothing usable is retried once with the cleaned pair, like getLyrics.
            val tracks = firstSelection(title, artist) { candidates ->
                candidates.takeIf { page -> page.any { track -> track.matchesDuration(durationSec) } }
            } ?: return
            var count = 0
            var emittedPlainLyrics = false

            val sortedTracks =
                if (durationSec == -1) {
                    tracks.sortedByDescending { track ->
                        var score = 0.0
                        if (track.syncedLyrics.isUsableLyrics()) score += 1.0
                        if (track.plainLyrics.isUsableLyrics()) score += 0.25

                        val titleSimilarity = calculateStringSimilarity(title, track.trackName)
                        val artistSimilarity = calculateStringSimilarity(artist, track.artistName)
                        score + (titleSimilarity + artistSimilarity) / 2.0
                    }
                } else {
                    tracks.sortedBy { track -> abs(track.duration.toInt() - durationSec) }
                }

            // Plain-text lyrics are emitted at most once, after the synced candidates that fit the budget.
            for (track in sortedTracks) {
                currentCoroutineContext().ensureActive()
                if (count >= MAX_SEARCH_RESULTS) return
                if (!track.matchesDuration(durationSec)) continue

                track.syncedLyrics?.takeIf { it.isNotBlank() }?.let { lyrics ->
                    callback(lyrics)
                    count++
                }

                if (!emittedPlainLyrics && count < MAX_SEARCH_RESULTS) {
                    track.plainLyrics?.takeIf { it.isNotBlank() }?.let { lyrics ->
                        callback(lyrics)
                        count++
                        emittedPlainLyrics = true
                    }
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error.isTransientLyricsFailure()) onFailure(error)
        }
    }

    /**
     * The exact query's answer first. Only when it holds nothing the caller can use does the cleaned pair run, so a
     * title that already resolves never costs a second request and never resolves differently.
     */
    private suspend fun <T : Any> firstSelection(
        title: String,
        artist: String,
        select: (List<LrcLibTrack>) -> T?,
    ): T? {
        for (query in lrcLibQueries(title, artist)) {
            select(queryLyrics(query.title, query.artist))?.let { return it }
        }
        return null
    }

    private suspend fun queryLyrics(
        title: String,
        artist: String,
    ): List<LrcLibTrack> {
        // The Android client sent no User-Agent of its own, so none is added here.
        val body =
            LyricsHttp
                .getWithStatus(client, SEARCH_URL, defaultUserAgent = false) {
                    parameter("track_name", title)
                    parameter("artist_name", artist)
                }.requireBody()
        return LyricsJson
            .decodeFromString<List<LrcLibTrack>>(body)
            .filter { track -> track.syncedLyrics.isUsableLyrics() || track.plainLyrics.isUsableLyrics() }
    }

    private fun LrcLibTrack.matchesDuration(duration: Int): Boolean =
        duration == -1 || abs(this.duration.toInt() - duration) <= LRCLIB_DURATION_TOLERANCE_SECONDS

    private fun LrcLibTrack.preferredLyrics(): String? =
        syncedLyrics.takeIf { it.isUsableLyrics() } ?: plainLyrics.takeIf { it.isUsableLyrics() }

    private fun String?.isUsableLyrics(): Boolean = !isNullOrBlank()

    private companion object {
        const val SEARCH_URL = "https://lrclib.net/api/search"
        const val MAX_SEARCH_RESULTS = 5
    }
}

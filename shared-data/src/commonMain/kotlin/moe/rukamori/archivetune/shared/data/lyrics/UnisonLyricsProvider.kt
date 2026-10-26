package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.decodeFromString

/**
 * Port of the Android Unison wrapper and `Unison` client.
 *
 * [id] is the YouTube video id that the Android wrapper passed as `videoId`. The lookup order
 * matches Android: a metadata search first, then the video-id lookup. Diagnostic logging is not
 * ported; the host owns it.
 */
class UnisonLyricsProvider(
    private val client: HttpClient,
) : LyricsProvider {
    override val name: String = "Unison"

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String?,
    ): Result<String> =
        suspendRunCatching {
            require(title.isNotBlank() && artist.isNotBlank()) { "Song title and artist are required" }
            val entry =
                fetchEntry(id, title, artist, album, durationSec)
                    ?: throw IllegalStateException("Lyrics unavailable")
            entry.lyrics
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
        var results = searchEntries(title, artist, album, durationSec)
        if (results.isEmpty()) {
            val cleanVideoId = id.trim().takeIf { it.isNotEmpty() }
            if (cleanVideoId != null) {
                results = fetchVariants(cleanVideoId)
                if (results.isEmpty()) {
                    results = listOfNotNull(fetchByVideoId(cleanVideoId))
                }
            }
        }
        results.take(MAX_SEARCH_RESULTS).forEach { entry ->
            currentCoroutineContext().ensureActive()
            callback(entry.lyrics)
        }
    }

    private suspend fun fetchEntry(
        videoId: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): UnisonEntry? {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return null

        val byMetadata = searchEntries(cleanTitle, cleanArtist, album?.trim(), durationSec).firstOrNull()
        if (byMetadata != null) return byMetadata

        val cleanVideoId = videoId.trim().takeIf { it.isNotEmpty() } ?: return null
        return fetchByVideoId(cleanVideoId)
    }

    private suspend fun fetchByVideoId(videoId: String): UnisonEntry? =
        suspendRunCatching {
            requestBody(LYRICS_URL) { parameter("v", videoId) }?.let { decodeEntry(it) }
        }.getOrNull()

    private suspend fun fetchById(id: Long): UnisonEntry? =
        suspendRunCatching {
            requestBody("${API_BASE_URL}lyrics/$id")?.let { decodeEntry(it) }
        }.getOrNull()

    private suspend fun fetchVariants(videoId: String): List<UnisonEntry> =
        suspendRunCatching {
            val response =
                requestBody("${API_BASE_URL}lyrics/variants/$videoId")
                    ?.let { LyricsJson.decodeFromString<UnisonSearchResponse>(it) }
            response
                ?.takeIf { it.success }
                ?.data
                .orEmpty()
                .mapNotNull { it.toEntry() }
                .take(MAX_SEARCH_RESULTS)
        }.getOrDefault(emptyList())

    private suspend fun searchEntries(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<UnisonEntry> {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return emptyList()

        return suspendRunCatching {
            val body =
                requestBody(SEARCH_URL) {
                    parameter("song", cleanTitle)
                    parameter("artist", cleanArtist)
                    if (!album.isNullOrBlank()) parameter("album", album.trim())
                    if (durationSec > 0) parameter("duration", durationSec)
                    parameter("limit", MAX_SEARCH_RESULTS)
                }
            val summaries =
                body
                    ?.let { LyricsJson.decodeFromString<UnisonSearchResponse>(it) }
                    ?.takeIf { it.success }
                    ?.data
                    .orEmpty()
            val entries = mutableListOf<UnisonEntry>()
            for (summary in summaries) {
                currentCoroutineContext().ensureActive()
                if (entries.size >= MAX_SEARCH_RESULTS) break
                val entry =
                    summary.toEntry()
                        ?: summary.videoId?.takeIf { it.isNotBlank() }?.let { fetchByVideoId(it) }
                        ?: fetchById(summary.id)
                if (entry != null) entries += entry
            }
            entries
        }.getOrDefault(emptyList())
    }

    /** Unison's Android client set no User-Agent of its own, so none is sent here. */
    private suspend fun requestBody(
        url: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): String? = LyricsHttp.getWithStatus(client, url, defaultUserAgent = false, configure = configure).body

    private fun decodeEntry(body: String): UnisonEntry? =
        LyricsJson
            .decodeFromString<UnisonResponse>(body)
            .takeIf { it.success }
            ?.data
            ?.takeIf { it.lyrics.isNotBlank() }

    private companion object {
        const val API_BASE_URL = "https://unison.boidu.dev/"
        const val LYRICS_URL = "https://unison.boidu.dev/lyrics"
        const val SEARCH_URL = "https://unison.boidu.dev/lyrics/search"
        const val MAX_SEARCH_RESULTS = 5
    }
}

package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonObject
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsPayload

/** Port of the Android BiniLyrics wrapper: ISRC exact lookup, otherwise a track/artist/album search. */
class BiniLyricsProvider(
    private val client: HttpClient,
) : LyricsProvider {
    override val name: String = "BiniLyrics"

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String?,
    ): Result<String> =
        suspendRunCatching {
            fetchLyrics(title, artist, album, durationSec, isrc)
                ?: throw IllegalStateException("No BiniLyrics lyrics for $title — $artist")
        }

    private suspend fun fetchLyrics(
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        isrc: String?,
    ): String? {
        if (title.isBlank() && artist.isBlank() && isrc.isNullOrBlank()) return null

        val url = buildString {
            append(BASE_URL)
            append('?')
            if (!isrc.isNullOrBlank()) {
                append("isrc=")
                append(isrc.trim().uppercase().encodeURLParameter())
            } else {
                append("track=")
                append(title.forLyricsSearch().encodeURLParameter())
                append("&artist=")
                append(artist.artistForLyricsSearch().encodeURLParameter())
                if (!album.isNullOrBlank()) {
                    append("&album=")
                    append(album.trim().encodeURLParameter())
                }
                if (duration > 0) {
                    append("&duration=")
                    append(duration)
                }
            }
        }

        val body = LyricsHttp.getWithStatus(client, url).body ?: return null
        val root = LyricsPayload.parseJson(body) as? JsonObject ?: return null
        val results = root["results"] as? kotlinx.serialization.json.JsonArray ?: return null
        val hit = results.firstOrNull() as? JsonObject ?: return null

        val document =
            (hit["lyricsUrl"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content
                ?.takeIf { it.startsWith("https://") } ?: return null
        return LyricsHttp.getWithStatus(client, document).body?.let(LyricsPayload::toLyrics)
    }

    private companion object {
        const val BASE_URL = "https://lyrics-api.binimum.org/"
    }
}

package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.data.provider.spotify.SpotifyEchoClient
import moe.rukamori.archivetune.shared.data.provider.spotify.SpotifyHttp
import moe.rukamori.archivetune.shared.domain.source.TrackMatching

/** Direct color-lyrics lookup using the catalog account only; streaming credentials never enter this provider. */
class SpotifyAccountLyricsProvider(client: HttpClient, private val token: suspend () -> String) : LyricsProvider {
    override val name = "Spotify"
    private val http = SpotifyHttp(client)
    override suspend fun getLyrics(id: String, title: String, artist: String, album: String?, durationSec: Int, isrc: String?): Result<String> = try {
        val bearer = token()
        val headers = mapOf("Authorization" to "Bearer $bearer", "App-Platform" to "WebPlayer")
        val search = http.exchange(SpotifyHttp.url("https://api.spotify.com/v1/search", mapOf(
            "q" to "$artist $title", "type" to "track", "limit" to "20")), headers = headers)
        require(search.status in 200..299) { "Spotify lyrics catalog unavailable" }
        val rows = (search.json()["tracks"] as? JsonObject)?.get("items") as? JsonArray
        val tracks = rows.orEmpty().mapNotNull { (it as? JsonObject)?.let { row -> SpotifyEchoClient.parseRest(row) } }
        val target = TrackMatching.Target(title, listOf(artist), album, durationSec.takeIf { it > 0 }?.times(1000L))
        val match = TrackMatching.best(target, tracks.map { it.candidate() }) ?: error("Lyrics unavailable")
        val reply = http.exchange("https://spclient.wg.spotify.com/color-lyrics/v2/track/${match.id}?format=json&market=from_token",
            headers = headers)
        require(reply.status in 200..299) { "Lyrics unavailable" }
        val lyrics = reply.json()["lyrics"] as? JsonObject ?: error("Lyrics unavailable")
        val rowsText = (lyrics["lines"] as? JsonArray).orEmpty()
        val timed = lyrics["syncType"]?.jsonPrimitive?.content != "UNSYNCED"
        val text = rowsText.joinToString("\n") { element ->
            val line = element.jsonObject
            val words = line["words"]?.jsonPrimitive?.content.orEmpty()
            val ms = line["startTimeMs"]?.jsonPrimitive?.content?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            if (timed) "[${(ms / 60000).toString().padStart(2, '0')}:${((ms / 1000) % 60).toString().padStart(2, '0')}.${((ms % 1000) / 10).toString().padStart(2, '0')}]$words" else words
        }
        require(text.isNotBlank()) { "Lyrics unavailable" }
        Result.success(text)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { Result.failure(IllegalStateException("Spotify lyrics unavailable")) }
}

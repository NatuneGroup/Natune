/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs

/**
 * Port of the Android Paxsenix Apple Music variant. It finds the Apple Music song id through the
 * AMP catalog search, then reads the lyrics from Paxsenix.
 *
 * The bearer comes from [appleBearer], which the Android wrapper installed before every lookup. With
 * no bearer the lookup fails before any request, so the chain moves on to the next provider.
 *
 * AMP requests carry their own Apple headers and no Paxsenix credential, as the Android requests did:
 * the Paxsenix default headers were overridden there, and Apple never saw the Paxsenix bearer.
 */
class PaxsenixAppleMusicLyricsProvider(
    private val client: HttpClient,
    private val appleBearer: PaxsenixAppleMusicToken = PaxsenixAppleMusicToken(client),
    settings: PaxsenixSettings = PaxsenixSettings(),
) : LyricsProvider {
    override val name: String = "Paxsenix: Apple Music"
    private val endpoint = PaxsenixEndpoint(client, settings)

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String?,
    ): Result<String> =
        suspendRunCatching {
            val bearer =
                appleBearer.ensureFresh()
                    ?: throw IllegalStateException("No Apple Music web player token available")
            val durationMs = resolveDurationMs(durationSec)
            val songId =
                searchSongId(title, artist, durationMs, bearer)
                    ?: throw IllegalStateException("Apple Music lyrics unavailable")
            fetchLyrics(songId) ?: throw IllegalStateException("Apple Music lyrics unavailable")
        }

    /**
     * Searches the AMP catalog. A 401 forces one refresh and one retry; a refresh that yields nothing
     * ends the lookup, since a second attempt with the same rejected bearer could only fail the same way.
     */
    private suspend fun searchSongId(
        title: String,
        artist: String,
        durationMs: Long,
        bearer: String,
    ): String? {
        val query = "$title $artist"
        val first = searchAmp(query, bearer)
        if (first.status == UNAUTHORIZED) {
            val fresh = appleBearer.refreshAfterRejection(bearer) ?: return null
            val retry = searchAmp(query, fresh)
            if (retry.status != OK_STATUS) return null
            return parseSearchResult(retry.requireBody(), title, artist, durationMs)
        }
        if (first.status != OK_STATUS) return null
        return parseSearchResult(first.requireBody(), title, artist, durationMs)
    }

    private suspend fun searchAmp(
        query: String,
        bearer: String,
    ): LyricsHttpResponse =
        paxsenixGet(client, "$AMP_BASE_URL/v1/catalog/${endpoint.storefront}/search", ampHeaders(bearer)) {
            parameter("term", query)
            parameter("types", "songs")
            parameter("limit", "10")
        }

    private fun ampHeaders(bearer: String): Map<String, String> =
        mapOf(
            HttpHeaders.Accept to "application/json, text/plain, */*",
            HttpHeaders.AcceptLanguage to "en-US,en;q=0.9",
            HttpHeaders.Authorization to "Bearer $bearer",
            "Origin" to "https://music.apple.com",
            "Referer" to "https://music.apple.com/",
            HttpHeaders.UserAgent to AMP_USER_AGENT,
        )

    /**
     * Picks the best song from an AMP search. Scoring uses title, artist and duration, and a best
     * score under [MIN_MATCH_SCORE] is rejected rather than risk the wrong song's lyrics.
     */
    private fun parseSearchResult(
        body: String,
        title: String,
        artist: String,
        durationMs: Long,
    ): String? {
        val root = PaxsenixJson.decodeFromString<JsonObject>(body)
        val songs =
            root["results"]
                ?.jsonObject
                ?.get("songs")
                ?.jsonObject
                ?.get("data")
                ?.jsonArray
                ?: return null
        if (songs.isEmpty()) return null

        val best =
            songs
                .mapNotNull { item -> scoreSong(item.jsonObject, title, artist, durationMs) }
                .sortedByDescending { it.score }
                .firstOrNull()
                ?: return null
        return best.id.takeIf { best.score >= MIN_MATCH_SCORE }
    }

    private data class ScoredSong(
        val id: String,
        val score: Int,
    )

    /** Null for a result without an id or attributes; such an entry cannot be fetched, so it is not scored. */
    private fun scoreSong(
        song: JsonObject,
        title: String,
        artist: String,
        durationMs: Long,
    ): ScoredSong? {
        val attributes = song["attributes"]?.jsonObject ?: return null
        val songId = song["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = attributes["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val artistName = attributes["artistName"]?.jsonPrimitive?.contentOrNull ?: ""
        val durationOnAmp = attributes["durationInMillis"]?.jsonPrimitive?.longOrNull ?: 0L

        var score = 0
        score +=
            when {
                name.equals(title, ignoreCase = true) -> 20
                name.contains(title, ignoreCase = true) || title.contains(name, ignoreCase = true) -> 10
                else -> 0
            }
        score +=
            when {
                artistName.equals(artist, ignoreCase = true) -> 15
                artistName.contains(artist, ignoreCase = true) || artist.contains(artistName, ignoreCase = true) -> 5
                else -> 0
            }
        if (durationMs > 0 && durationOnAmp > 0) {
            val diff = abs(durationOnAmp - durationMs)
            if (diff < 3000) score += 10 else if (diff < 10000) score += 5
        }
        return ScoredSong(songId, score)
    }

    /**
     * The TTML document first, then the LRC conversion of Apple's timed-line JSON. A TTML body that
     * fails to parse is skipped, so the JSON request still gets its turn.
     */
    private suspend fun fetchLyrics(songId: String): String? {
        val ttml =
            endpoint.get("apple-music/lyrics") {
                parameter("id", songId)
                parameter("ttml", "true")
            }
        if (ttml.status == OK_STATUS) ttmlFromBody(ttml.requireBody())?.let { return it }

        val timed = endpoint.get("apple-music/lyrics") { parameter("id", songId) }
        if (timed.status != OK_STATUS) return null
        val lyricsData = PaxsenixJson.decodeFromString<AppleMusicLyricsResponse>(timed.requireBody())
        return if (lyricsData.content.isEmpty()) null else convertAppleMusicToLrc(lyricsData)
    }

    /** A TTML document as-is, or the TTML inside a JSON `content` field. Anything else is null. */
    private fun ttmlFromBody(body: String): String? {
        val raw = body.trim()
        if (raw.startsWith("<tt") || raw.startsWith("<?xml")) return raw
        return try {
            Json.decodeFromString<JsonObject>(raw)["content"]
                ?.jsonPrimitive
                ?.content
                ?.takeIf { it.contains("<tt") || it.contains("<?xml") }
        } catch (error: Exception) {
            null
        }
    }

    private companion object {
        const val AMP_BASE_URL = "https://amp-api.music.apple.com"
        const val AMP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
        const val UNAUTHORIZED = 401
        const val MIN_MATCH_SCORE = 12
    }
}

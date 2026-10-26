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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Port of the Android Paxsenix Musixmatch variant. The word-timed request is tried first; the
 * plain request is the fallback, as in the Android client. Each request reads the body only on 200.
 */
class PaxsenixMusixmatchLyricsProvider(
    client: HttpClient,
    settings: PaxsenixSettings = PaxsenixSettings(),
) : LyricsProvider {
    override val name: String = "Paxsenix: Musixmatch"
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
            fetchLyrics(title, artist, durationSec) ?: throw IllegalStateException("Musixmatch lyrics unavailable")
        }

    private suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSec: Int,
    ): String? {
        val query = "$title $artist"
        for (wordTimed in listOf(true, false)) {
            val response =
                endpoint.get("musixmatch/lyrics") {
                    parameter("q", query)
                    parameter("t", title)
                    parameter("a", artist)
                    parameter("d", durationSec.toString())
                    if (wordTimed) parameter("type", "word")
                }
            if (response.status == OK_STATUS) cleanJsonLyrics(response.requireBody())?.let { return it }
        }
        return null
    }
}

/**
 * Port of the Android Paxsenix NetEase variant. A karaoke (`klyric`) lyric is preferred to the plain
 * LRC, and a candidate is accepted only within ten seconds of the requested duration.
 */
class PaxsenixNeteaseLyricsProvider(
    client: HttpClient,
    settings: PaxsenixSettings = PaxsenixSettings(),
) : LyricsProvider {
    override val name: String = "Paxsenix: NetEase"
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
            fetchLyrics(title, artist, durationSec) ?: throw IllegalStateException("NetEase lyrics unavailable")
        }

    private suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSec: Int,
    ): String? {
        val durationMs = resolveDurationMs(durationSec)
        val search = endpoint.get("netease/search") { parameter("q", "$title $artist") }
        if (search.status != OK_STATUS) return null
        val songs = PaxsenixJson.decodeFromString<NeteaseSearchResponse>(search.requireBody()).result?.songs.orEmpty()
        val best = songs.closestTo(durationMs) { it.duration.toLong() } ?: return null
        if (!withinDurationTolerance(durationMs, best.duration.toLong())) return null

        val lyrics =
            endpoint.get("netease/lyrics") {
                parameter("id", best.id)
                parameter("word", "true")
            }
        if (lyrics.status != OK_STATUS) return null
        val data = PaxsenixJson.decodeFromString<JsonObject>(lyrics.requireBody())
        val karaoke = data["klyric"]?.jsonObject?.get("lyric")?.jsonPrimitive?.content
        if (!karaoke.isNullOrBlank()) return karaoke
        val lrc = data["lrc"]?.jsonObject?.get("lyric")?.jsonPrimitive?.content
        return lrc?.takeIf { it.isNotBlank() }
    }
}

/** Port of the Android Paxsenix Spotify variant: search, then the closest match's lyrics. */
class PaxsenixSpotifyLyricsProvider(
    client: HttpClient,
    settings: PaxsenixSettings = PaxsenixSettings(),
) : LyricsProvider {
    override val name: String = "Paxsenix: Spotify"
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
            fetchMatchedLyrics(endpoint, "spotify/search", "spotify/lyrics", title, artist, durationSec)
                ?: throw IllegalStateException("Spotify lyrics unavailable")
        }
}

/** Port of the Android Paxsenix YouTube variant. Its search and lyric paths mirror Spotify's. */
class PaxsenixYouTubeLyricsProvider(
    client: HttpClient,
    settings: PaxsenixSettings = PaxsenixSettings(),
) : LyricsProvider {
    override val name: String = "Paxsenix: YouTube"
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
            fetchMatchedLyrics(endpoint, "youtube/search", "youtube/lyrics", title, artist, durationSec)
                ?: throw IllegalStateException("YouTube lyrics unavailable")
        }
}

/**
 * Searches [searchPath] for `title artist`, takes the candidate closest to [durationSec], and reads
 * [lyricsPath] for its id. A non-200 search, an empty result or an out-of-tolerance match is a miss.
 */
private suspend fun fetchMatchedLyrics(
    endpoint: PaxsenixEndpoint,
    searchPath: String,
    lyricsPath: String,
    title: String,
    artist: String,
    durationSec: Int,
): String? {
    val durationMs = resolveDurationMs(durationSec)
    val search = endpoint.get(searchPath) { parameter("q", "$title $artist") }
    if (search.status != OK_STATUS) return null
    val items = PaxsenixJson.decodeFromString<List<PaxsenixSearchItem>>(search.requireBody())
    val best = items.closestTo(durationMs) { it.durationMs } ?: return null
    if (!withinDurationTolerance(durationMs, best.durationMs)) return null
    val lyrics = endpoint.get(lyricsPath) { parameter("id", best.realId) }
    return if (lyrics.status == OK_STATUS) cleanJsonLyrics(lyrics.requireBody()) else null
}

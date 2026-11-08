package moe.rukamori.archivetune.spotify.librespot

import java.util.concurrent.TimeUnit
import moe.rukamori.archivetune.audiosource.DirectStream
import moe.rukamori.archivetune.audiosource.TitleMatch
import moe.rukamori.archivetune.audiosource.executeCancellable
import moe.rukamori.archivetune.audiosource.withAudioSourceAttemptDeadline
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.playback.SourceQuery
import moe.rukamori.archivetune.shared.domain.source.SpotifyLibrespotAudioPolicy
import moe.rukamori.archivetune.shared.domain.source.SpotifyLibrespotFailure
import moe.rukamori.archivetune.spotify.SpotifyRequestCooldown
import moe.rukamori.archivetune.spotify.isSpotifyQuotaExceededResponse
import moe.rukamori.archivetune.utils.withAppProxy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Audio identification uses this grant's own bearer, never Spotify.accessToken or catalog search. */
internal object SpotifyLibrespotTrackSearch {
    private val cooldown = SpotifyRequestCooldown()
    private val client = OkHttpClient.Builder().withAppProxy().retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    suspend fun find(token: String, query: SourceQuery): String? {
        if (query.spotifyTrackId != null) return SpotifyLibrespotAudioPolicy.trackId(query.spotifyTrackId)
        if (query.mediaId.startsWith("sp:") || query.mediaId.startsWith("spotify:track:") ||
            query.mediaId.startsWith("https://open.spotify.com/")) {
            SpotifyLibrespotAudioPolicy.trackId(query.mediaId)?.let { return it }
        }
        if (cooldown.remainingSeconds() > 0) throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.RATE_LIMITED)
        val isrc = SpotifyLibrespotAudioPolicy.isrc(query.isrc)
        if (!query.isrc.isNullOrBlank() && isrc == null) return null
        val term = isrc?.let { "isrc:$it" } ?: (query.title + " " + query.artists.joinToString(" ")).trim()
        if (term.isBlank()) return null
        val url = "https://api.spotify.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", term).addQueryParameter("type", "track").addQueryParameter("limit", "10").build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Accept", "application/json").build()
        return client.newCall(request).withAudioSourceAttemptDeadline().executeCancellable().use { response ->
            if (response.code == 429) {
                val body = response.body?.string().orEmpty()
                cooldown.recordRateLimit(response.header("Retry-After"), isSpotifyQuotaExceededResponse(body))
                throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.RATE_LIMITED)
            }
            if (response.code == 401) throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.AUTHENTICATION)
            if (response.code == 403) throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.TRACK_UNAVAILABLE)
            if (!response.isSuccessful) throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.NETWORK)
            val tracks = JSONObject(response.body?.string().orEmpty()).optJSONObject("tracks")?.optJSONArray("items")
                ?: return@use null
            var best: Pair<String, Double>? = null
            for (index in 0 until minOf(tracks.length(), 10)) {
                val track = tracks.optJSONObject(index) ?: continue
                val id = SpotifyLibrespotAudioPolicy.trackId(track.optString("id")) ?: continue
                if (track.has("is_playable") && !track.optBoolean("is_playable")) continue
                if (isrc != null) {
                    if (SpotifyLibrespotAudioPolicy.isrc(track.optJSONObject("external_ids")?.optString("isrc")) == isrc) {
                        return@use id
                    }
                    continue
                }
                val artists = track.optJSONArray("artists")
                val artist = (0 until (artists?.length() ?: 0)).mapNotNull {
                    artists?.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank)
                }.joinToString(", ")
                val match = TitleMatch.evaluate(query.title, query.artists, query.album, query.durationMs,
                    DirectStream("", "audio/ogg", "vorbis", null, "", AudioSourceType.SPOTIFY,
                        matchedTitle = track.optString("name"), matchedArtist = artist,
                        matchedAlbum = track.optJSONObject("album")?.optString("name"),
                        matchedDurationMs = track.optLong("duration_ms").takeIf { it > 0 }))
                if (match.accepted && match.score > (best?.second ?: -1.0)) best = id to match.score
            }
            best?.first
        }
    }
}

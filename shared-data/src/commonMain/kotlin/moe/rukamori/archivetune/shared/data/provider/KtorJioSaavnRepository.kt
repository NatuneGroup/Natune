package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.http.Url
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsPayload
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.playback.PlaybackHeaders
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

fun interface JioSaavnUrlDecoder { fun decode(encryptedUrl: String): String }

/** Retained decoder protocol by default; the explicit web-player mode needs no decoder key. */
class KtorJioSaavnRepository(client: HttpClient, private val decoder: JioSaavnUrlDecoder? = null,
    base: String = "https://www.jiosaavn.com/api.php",
    private val useWebPlayer: () -> Boolean = { decoder == null }) : PagedAudioProvider("jiosaavn:$base") {
    override val provider = ContentProvider.JIOSAAVN
    override val source = AudioSourceType.JIOSAAVN
    private val base = endpoint(base)
    private val http = ProviderHttp(client)
    private suspend fun call(method: String, params: Map<String, String>): JsonObject =
        http.json(base, params + mapOf("__call" to method, "_format" to "json", "_marker" to "0",
            "api_version" to "4", "ctx" to if (useWebPlayer()) "web6dot0" else "android"),
            if (useWebPlayer()) mapOf("Referer" to REFERER) else mapOf("Cookie" to "explicit_content=1",
                "Accept-Language" to "en-IN,en;q=0.9", "X-Forwarded-For" to "49.36.0.1")).jsonObject
    override suspend fun tracks(request: ContentRequest, offset: Int, limit: Int): ProviderTrackPage {
        if (request.surface != ContentSurface.SEARCH) throw UnsupportedContentOperation()
        require(offset % limit == 0) { "Invalid Saavn page boundary" }
        val result = call("search.getResults", mapOf("q" to requireNotNull(request.text),
            "p" to "${offset / limit + 1}", "n" to "$limit"))
        val tracks = (result.array("results") ?: throw ProviderProtocolFailure()).map { parse(it.jsonObject) }
        val total = result.long("total")
        if (total != null && offset + tracks.size < total && tracks.size != limit) throw ProviderProtocolFailure()
        return ProviderTrackPage(tracks, total)
    }
    override suspend fun track(id: String): ProviderTrack {
        val root = call("song.getDetails", mapOf("pids" to pathId(id)))
        if (!useWebPlayer()) {
            val songs = root.array("songs") ?: throw ProviderProtocolFailure()
            return songs.map { parse(it.jsonObject) }.firstOrNull { it.item.ref.id == id } ?: throw ProviderProtocolFailure()
        }
        val song = root.array("songs")?.firstOrNull { it.jsonObject.text("id") == id }?.jsonObject
            ?: if (useWebPlayer()) root.obj(id)?.takeIf { it.text("id") == id } else null
        return parse(song ?: throw ProviderProtocolFailure())
    }
    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        // Keyless with the web player off is a client that cannot resolve at all, not an empty
        // search: say unavailable so the resolver reports it instead of NO_MATCH.
        if (!useWebPlayer() && decoder == null)
            throw AudioProviderUnavailable(AudioProviderUnavailableReason.CLIENT_CONFIGURATION_REQUIRED)
        val track = match(request) ?: return null
        val info = Json.parseToJsonElement(track.rawJson).jsonObject.obj("more_info") ?: return null
        val rights = info.obj("rights")
        if (rights?.text("code") == "1" || rights?.text("reason")?.contains("Pro Only", true) == true) return null
        val encrypted = info.text("encrypted_media_url") ?: return null
        require(encrypted.length <= 64 * 1024)
        if (useWebPlayer()) return resolveWebPlayer(request, track, info, encrypted)
        val rawUrl = requireNotNull(decoder).decode(encrypted).trim()
        // createDownloadLinks + selectBestUrl, exactly: derive the quality variants, prefer the
        // requested one, then 320kbps, then the first available.
        val suffixRegex = Regex("_(48|96|160|320)\\.(mp4|aac|mp3)$")
        val variants = listOf("_96" to "96kbps", "_160" to "160kbps", "_320" to "320kbps").map { (suffix, label) ->
            val variant = if (rawUrl.contains(suffixRegex)) {
                rawUrl.replace(suffixRegex) { "$suffix.${it.groupValues[2]}" }
            } else {
                rawUrl.replace("_96", suffix)
            }
            label to variant
        }
        val wanted = (request.qualities[source]?.removeSuffix("kbps") ?: "320") + "kbps"
        require(wanted in setOf("96kbps", "160kbps", "320kbps"))
        val url = variants.firstOrNull { it.first == wanted }?.second
            ?: variants.firstOrNull { it.first == "320kbps" }?.second
            ?: variants.first().second
        val mp3 = url.substringBefore('?').endsWith(".mp3")
        return PreparedAudio.Progressive(direct(request, track, url, if (mp3) "audio/mpeg" else "audio/mp4",
            if (mp3) "mp3" else "mp4a.40.2"))
    }
    private suspend fun resolveWebPlayer(request: AudioResolveRequest, track: ProviderTrack,
        info: JsonObject, encrypted: String): PreparedAudio {
        val requested = request.qualities[source]?.removeSuffix("kbps") ?: "320"
        require(requested in setOf("96", "160", "320"))
        // The public web player offers 96 and 320; 160 is served at 96 rather than over its data budget.
        val bitrate = if (requested == "320" && info.text("320kbps") == "true") 320 else 96
        val token = call("song.generateAuthToken", mapOf("url" to encrypted, "bitrate" to "$bitrate"))
        val url = token.text("auth_url") ?: throw ProviderProtocolFailure()
        if (token.text("status") != "success" ||
            Regex("_(\\d+)\\.mp4$").find(Url(url).encodedPath)?.groupValues?.get(1)?.toIntOrNull() != bitrate) {
            throw ProviderProtocolFailure()
        }
        return PreparedAudio.Progressive(direct(request, track, url, "audio/mp4", "mp4a.40.2")
            .copy(headers = PlaybackHeaders.of(mapOf("Referer" to REFERER)),
                expiresAtEpochMs = Url(url).parameters["Expires"]?.toLongOrNull()
                    ?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)))
    }

    private fun parse(obj: JsonObject): ProviderTrack {
        val info = obj.obj("more_info")
        val artistMap = info?.obj("artistMap")
        val nativeArtists = (artistMap?.array("primary_artists") ?: artistMap?.array("artists"))
            ?.mapNotNull { it.jsonObject.text("name") }.orEmpty()
        val artists = if (useWebPlayer()) {
            val credits = (artistMap?.array("primary_artists").orEmpty() + artistMap?.array("featured_artists").orEmpty())
                .mapNotNull { it.jsonObject.text("name") }
                .ifEmpty { artistMap?.array("artists").orEmpty().filter { it.jsonObject.text("role") == "singer" }
                    .mapNotNull { it.jsonObject.text("name") } }
                .ifEmpty { obj.text("subtitle").orEmpty().substringBefore(" - ").split(',') }
            credits.map(::webText).filter { it.isNotBlank() }.distinct()
        } else nativeArtists
        val title = obj.text("title") ?: throw ProviderProtocolFailure()
        return ProviderTrack(ContentItem(ContentRef(provider.id, ContentKind.SONG,
            obj.text("id") ?: throw ProviderProtocolFailure()), if (useWebPlayer()) webText(title) else title,
            artists.joinToString(", "), obj.text("image")?.replace("150x150", "500x500"),
            info?.long("duration")?.times(1000), obj.text("explicit_content") == "1"), artists,
            info?.text("album")?.let { if (useWebPlayer()) webText(it) else it }, null, obj.toString())
    }
    private fun webText(value: String): String = LyricsPayload.decodeEntities(value).replace(Regex("\\s+"), " ").trim()

    private companion object { const val REFERER = "https://www.jiosaavn.com/" }
}

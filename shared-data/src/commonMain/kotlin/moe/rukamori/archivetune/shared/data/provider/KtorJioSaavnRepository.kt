package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

fun interface JioSaavnUrlDecoder { fun decode(encryptedUrl: String): String }

/** Direct retained service protocol, exactly as `jiosaavn/SaavnService.kt` issues it. */
class KtorJioSaavnRepository(client: HttpClient, private val decoder: JioSaavnUrlDecoder,
    base: String = "https://www.jiosaavn.com/api.php") : PagedAudioProvider("jiosaavn:$base") {
    override val provider = ContentProvider.JIOSAAVN
    override val source = AudioSourceType.JIOSAAVN
    private val base = endpoint(base)
    private val http = ProviderHttp(client)
    private suspend fun call(method: String, params: Map<String, String>): JsonObject =
        http.json(base, params + mapOf("__call" to method, "_format" to "json", "_marker" to "0",
            "api_version" to "4", "ctx" to "android"), mapOf("Cookie" to "explicit_content=1",
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
        val songs = call("song.getDetails", mapOf("pids" to pathId(id))).array("songs") ?: throw ProviderProtocolFailure()
        return songs.map { parse(it.jsonObject) }.firstOrNull { it.item.ref.id == id } ?: throw ProviderProtocolFailure()
    }
    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val track = match(request) ?: return null
        val info = Json.parseToJsonElement(track.rawJson).jsonObject.obj("more_info") ?: return null
        val rights = info.obj("rights")
        if (rights?.text("code") == "1" || rights?.text("reason")?.contains("Pro Only", true) == true) return null
        val encrypted = info.text("encrypted_media_url") ?: return null
        require(encrypted.length <= 64 * 1024)
        val rawUrl = decoder.decode(encrypted).trim()
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
    private fun parse(obj: JsonObject): ProviderTrack {
        val info = obj.obj("more_info")
        val artistMap = info?.obj("artistMap")
        val artists = (artistMap?.array("primary_artists") ?: artistMap?.array("artists"))
            ?.mapNotNull { it.jsonObject.text("name") }.orEmpty()
        return ProviderTrack(ContentItem(ContentRef(provider.id, ContentKind.SONG,
            obj.text("id") ?: throw ProviderProtocolFailure()), obj.text("title") ?: throw ProviderProtocolFailure(),
            artists.joinToString(", "), obj.text("image")?.replace("150x150", "500x500"),
            info?.long("duration")?.times(1000), obj.text("explicit_content") == "1"), artists,
            info?.text("album"), null, obj.toString())
    }
}

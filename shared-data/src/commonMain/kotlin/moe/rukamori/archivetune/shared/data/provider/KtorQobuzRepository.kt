package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

class QobuzAccess(val scope: String, val appId: String, val userToken: String, val appSecret: String,
    val credentialScope: CredentialScope = CredentialScope.PERSONAL,
    val isActive: () -> Boolean = { credentialScope != CredentialScope.PLAYBACK_POOL }) {
    init {
        require(listOf(scope, appId, userToken, appSecret).all { it.isNotBlank() })
        require(credentialScope in setOf(CredentialScope.PERSONAL, CredentialScope.PLAYBACK_POOL))
    }
    override fun toString() = "QobuzAccess(REDACTED)"
}
fun interface QobuzRequestDigest { fun md5Hex(value: String): String }

/** Account API or an explicitly supplied instance. No bundled app secret or account discovery. */
class KtorQobuzRepository(
    client: HttpClient,
    private val nowMs: () -> Long,
    private val access: QobuzAccess? = null,
    private val digest: QobuzRequestDigest? = null,
    instanceBase: String? = null,
    apiBase: String = "https://www.qobuz.com/api.json/0.2",
) : PagedAudioProvider("qobuz:${access?.scope ?: "public"}:$apiBase:$instanceBase") {
    override val provider = ContentProvider.QOBUZ
    override val source = AudioSourceType.QOBUZ
    private val http = ProviderHttp(client)
    private val api = endpoint(apiBase)
    private val instance = instanceBase?.let(::endpoint)
    init {
        require(access != null || instance != null)
        require(access == null || digest != null) { "Account playback requires its request signer" }
    }
    private fun headers() = access?.let { mapOf("X-App-Id" to it.appId, "X-User-Auth-Token" to it.userToken) }.orEmpty()
    private suspend fun api(path: String, params: Map<String, String>): JsonObject {
        val account = access ?: throw UnsupportedContentOperation()
        if (!account.isActive()) throw UnsupportedContentOperation()
        val result = http.json("$api/$path", params + ("app_id" to account.appId), headers()).jsonObject
        if (!account.isActive()) throw UnsupportedContentOperation()
        return result
    }
    override suspend fun tracks(request: ContentRequest, offset: Int, limit: Int): ProviderTrackPage {
        val paging = mapOf("offset" to "$offset", "limit" to "$limit")
        val root = when (request.surface) {
            ContentSurface.SEARCH -> {
                val query = requireNotNull(request.text).also { require(it.isNotBlank()) }
                if (access != null) api("track/search", paging + ("query" to query))
                else http.json("$instance/api/get-music", paging + ("q" to query)).jsonObject
            }
            ContentSurface.ALBUM -> api("album/get", paging + mapOf("album_id" to pathId(requireNotNull(request.parent).id), "extra" to "tracks"))
            ContentSurface.PLAYLIST -> api("playlist/get", paging + mapOf("playlist_id" to pathId(requireNotNull(request.parent).id), "extra" to "tracks"))
            else -> throw UnsupportedContentOperation()
        }
        val data = root.obj("data") ?: root
        val tracks = data.obj("tracks") ?: data
        val items = tracks.array("items") ?: data.array("tracks") ?: throw ProviderProtocolFailure()
        return ProviderTrackPage(items.map { parse(it.jsonObject) }, tracks.long("total"))
    }
    override suspend fun track(id: String): ProviderTrack = parse(api("track/get", mapOf("track_id" to pathId(id))))

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        if (access != null && !access.isActive()) throw UnsupportedContentOperation()
        val track = match(request) ?: return null
        val format = request.qualities[source]?.toInt() ?: 6
        require(format in setOf(5, 6, 7, 27))
        val root = if (access != null) {
            val ts = nowMs() / 1000
            val sig = requireNotNull(digest).md5Hex("trackgetFileUrlformat_id${format}intentstreamtrack_id${track.item.ref.id}$ts${access.appSecret}")
            api("track/getFileUrl", mapOf("request_ts" to "$ts", "request_sig" to sig, "track_id" to track.item.ref.id,
                "format_id" to "$format", "intent" to "stream"))
        } else http.json("$instance/api/download-music", mapOf("track_id" to track.item.ref.id, "quality" to "$format")).jsonObject
        if (root.preview()) return null
        val data = root.obj("data") ?: root
        val url = listOf("url", "downloadUrl", "download_url", "stream_url", "streamUrl", "link")
            .firstNotNullOfOrNull { data.text(it) } ?: throw ProviderProtocolFailure()
        val mime = data.text("mime_type") ?: when {
            ".flac" in url -> "audio/flac"
            ".mp3" in url -> "audio/mpeg"
            else -> throw ProviderProtocolFailure()
        }
        val codecs = when (mime) { "audio/flac" -> "flac"; "audio/mpeg" -> "mp3"; else -> "mp4a.40.2" }
        val rate = (data["sampling_rate"] as? JsonPrimitive)?.doubleOrNull?.times(1000)?.toInt()
        return PreparedAudio.Progressive(direct(request, track, url, mime, codecs,
            data.long("content_length"), rate, data.long("bit_depth")?.toInt(),
            accountScope = access?.let { streamAccount(it.scope) }))
    }
    private fun parse(obj: JsonObject): ProviderTrack {
        val id = obj.text("id") ?: obj.text("track_id") ?: throw ProviderProtocolFailure()
        val title = obj.text("title") ?: throw ProviderProtocolFailure()
        val album = obj.obj("album")
        val artist = obj.obj("performer")?.text("name") ?: obj.obj("artist")?.text("name") ?: album?.obj("artist")?.text("name")
        val image = album?.obj("image")?.let { it.text("large") ?: it.text("small") }
            ?: (album?.get("image") as? JsonPrimitive)?.contentOrNull
            ?: album?.array("image")?.firstOrNull()?.jsonObject?.text("url")
        return ProviderTrack(ContentItem(ContentRef(provider.id, ContentKind.SONG, id), title, artist.orEmpty(), image,
            obj.long("duration")?.times(1000), obj.flag("parental_warning")), listOfNotNull(artist),
            album?.text("title"), obj.text("isrc"), obj.toString())
    }
}

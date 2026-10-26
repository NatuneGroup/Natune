package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

/** Immutable account lease; refresh/isolation/pool enablement remains with the credential owner. */
class TidalAccess(val scope: String, val bearer: String? = null, val catalogToken: String? = null,
    val credentialScope: CredentialScope = CredentialScope.PERSONAL,
    val isActive: () -> Boolean = { credentialScope != CredentialScope.PLAYBACK_POOL }) {
    init { require(scope.isNotBlank() && credentialScope in setOf(CredentialScope.PERSONAL, CredentialScope.PLAYBACK_POOL)) }
    override fun toString() = "TidalAccess(REDACTED)"
}

class KtorTidalRepository(
    client: HttpClient,
    private val access: TidalAccess,
    instanceBase: String? = null,
    apiBase: String = "https://tidal.com/v1",
    private val countryCode: String = "US",
) : PagedAudioProvider("tidal:${access.scope}:$apiBase:$instanceBase:$countryCode") {
    override val provider = ContentProvider.TIDAL
    override val source = AudioSourceType.TIDAL
    private val http = ProviderHttp(client)
    private val api = endpoint(apiBase)
    private val instance = instanceBase?.let(::endpoint)
    private fun headers(): Map<String, String> {
        if (!access.isActive()) throw UnsupportedContentOperation()
        return when {
        !access.bearer.isNullOrBlank() -> mapOf("Authorization" to "Bearer ${access.bearer}")
        !access.catalogToken.isNullOrBlank() -> mapOf("x-tidal-token" to access.catalogToken)
        else -> throw UnsupportedContentOperation()
        }
    }
    private val locale get() = mapOf("countryCode" to countryCode, "locale" to "en_US", "deviceType" to "BROWSER")

    override suspend fun tracks(request: ContentRequest, offset: Int, limit: Int): ProviderTrackPage {
        val params = mapOf("offset" to "$offset", "limit" to "$limit")
        val root = when (request.surface) {
            ContentSurface.SEARCH -> {
                val query = requireNotNull(request.text).also { require(it.isNotBlank()) }
                if (instance != null) http.json("$instance/search/", params + ("s" to query)).jsonObject
                else http.json("$api/search/tracks", locale + params + ("query" to query), headers()).jsonObject
            }
            ContentSurface.ALBUM -> http.json("$api/albums/${pathId(requireNotNull(request.parent).id)}/tracks",
                locale + params, headers()).jsonObject
            ContentSurface.PLAYLIST -> http.json("$api/playlists/${pathId(requireNotNull(request.parent).id)}/items",
                locale + params, headers()).jsonObject
            else -> throw UnsupportedContentOperation()
        }
        val data = root.obj("data") ?: root
        val items = data.array("items") ?: throw ProviderProtocolFailure()
        return ProviderTrackPage(items.map {
            val obj = it.jsonObject
            parse(obj.obj("item") ?: obj)
        }, data.long("totalNumberOfItems") ?: data.long("total"))
    }
    override suspend fun track(id: String): ProviderTrack =
        parse(http.json("$api/tracks/${pathId(id)}", locale, headers()).jsonObject)

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        if (!access.isActive()) throw UnsupportedContentOperation()
        val track = match(request) ?: return null
        val quality = request.qualities[source] ?: "LOSSLESS"
        require(quality in setOf("HIGH", "LOW", "LOSSLESS", "HI_RES_LOSSLESS", "HI_RES", "DOLBY_ATMOS"))
        val root: JsonObject
        if (!access.bearer.isNullOrBlank()) {
            root = http.json("$api/tracks/${pathId(track.item.ref.id)}/playbackinfopostpaywall",
                mapOf("audioquality" to quality, "playbackmode" to "STREAM", "assetpresentation" to "FULL"),
                mapOf("Authorization" to "Bearer ${access.bearer}")).jsonObject
        } else {
            val base = instance ?: throw UnsupportedContentOperation()
            val format = when (quality) {
                "HI_RES", "HI_RES_LOSSLESS" -> "FLAC_HIRES"
                "LOSSLESS" -> "FLAC"
                "DOLBY_ATMOS" -> "EAC3_JOC"
                else -> null
            }
            val response = if (format == null) oldManifest(base, track.item.ref.id, quality) else try {
                http.json("$base/trackManifests/", mapOf("id" to track.item.ref.id, "formats" to format,
                    "adaptive" to "false", "manifestType" to "MPEG_DASH", "uriScheme" to "HTTPS",
                    "usage" to "PLAYBACK")).jsonObject
            } catch (failure: ProviderHttpFailure) {
                if (failure.status != 404) throw failure
                oldManifest(base, track.item.ref.id, quality)
            }
            root = response.obj("data") ?: response
            val attributes = root.obj("data")?.obj("attributes")
            if (attributes != null) {
                if (attributes.preview()) return null
                val returned = attributes.array("formats")?.map { it.jsonPrimitive.content }.orEmpty()
                if (format !in returned) return null
                val uri = attributes.text("uri") ?: throw ProviderProtocolFailure()
                return manifest(request, track, quality, http.text(streamUrl(uri)))
            }
        }
        if (root.preview()) return null
        val data = root.obj("data") ?: root
        if (data.preview()) return null
        val encoded = data.text("manifest") ?: throw ProviderProtocolFailure()
        val raw = try { Base64.decode(encoded).decodeToString(throwOnInvalidSequence = true) }
            catch (failure: Exception) { throw ProviderProtocolFailure() }
        return manifest(request, track, quality, raw)
    }
    private suspend fun oldManifest(base: String, id: String, quality: String) =
        http.json("$base/track/", mapOf("id" to id, "quality" to quality)).jsonObject

    private fun manifest(request: AudioResolveRequest, track: ProviderTrack, quality: String, text: String): PreparedAudio? {
        if (!access.isActive()) throw UnsupportedContentOperation()
        if (text.trimStart().startsWith("<")) {
            require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true))
            require(text.contains("<MPD") || text.contains(":MPD"))
            // The manifest keeps the scope it was resolved under, so a personal one is admitted before it is read.
            return PreparedAudio.TidalManifest(text, track, quality, request.directIds[source] == track.item.ref.id,
                accountScope = access.scope)
        }
        val json = Json.parseToJsonElement(text).jsonObject
        if (json.preview() || json.text("encryptionType")?.let { it != "NONE" } == true) return null
        val urls = json.array("urls") ?: throw ProviderProtocolFailure()
        // BTS URLs are alternate full-file mirrors, not DASH segments (retained first-URL policy).
        if (urls.isEmpty()) throw ProviderProtocolFailure()
        val mime = json.text("mimeType") ?: throw ProviderProtocolFailure()
        if ("dash" in mime || "mpegurl" in mime) throw UnsupportedContentOperation()
        val codecs = json.text("codecs") ?: if ("flac" in mime) "flac" else "mp4a.40.2"
        return PreparedAudio.Progressive(direct(request, track, urls.first().jsonPrimitive.content, mime, codecs,
            sampleRate = json.long("sampleRate")?.toInt(), bitDepth = json.long("bitDepth")?.toInt(),
            accountScope = streamAccount(access.scope)))
    }
    private fun parse(obj: JsonObject): ProviderTrack {
        val id = obj.text("id") ?: throw ProviderProtocolFailure()
        val title = obj.text("title") ?: throw ProviderProtocolFailure()
        val artists = obj.array("artists")?.mapNotNull { it.jsonObject.text("name") }?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(obj.obj("artist")?.text("name"))
        val album = obj.obj("album")
        val cover = album?.text("cover")?.replace('-', '/')?.let { "https://resources.tidal.com/images/$it/640x640.jpg" }
        return ProviderTrack(ContentItem(ContentRef(provider.id, ContentKind.SONG, id), title,
            artists.joinToString(", "), cover, obj.long("duration")?.times(1000), obj.flag("explicit")),
            artists, album?.text("title"), obj.text("isrc"), obj.toString())
    }
}

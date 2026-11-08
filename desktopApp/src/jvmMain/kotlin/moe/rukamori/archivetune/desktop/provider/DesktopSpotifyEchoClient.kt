package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderUnavailable
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderUnavailableReason
import moe.rukamori.archivetune.shared.domain.repository.AudioResolveRequest
import moe.rukamori.archivetune.shared.domain.repository.AudioSecret
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.NativeAudioRecipe
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.repository.ProviderTrack
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.DirectStream
import moe.rukamori.archivetune.shared.domain.source.StreamUrlExpiry
import moe.rukamori.archivetune.shared.domain.source.TrackMatching
import moe.rukamori.archivetune.spotify.SpotifyHashProvider
import moe.rukamori.archivetune.spotify.echo.SpotifyEchoProto
import moe.rukamori.archivetune.spotify.echo.SpotifyPlayPlayLicenseRequest
import moe.rukamori.archivetune.spotify.echo.SpotifyPlayPlayRecordDecoder

internal interface DesktopSpotifyStreamingTransport {
    suspend fun userId(spDc: String, active: () -> Boolean): String?
    suspend fun resolve(spDc: String, request: AudioResolveRequest, scope: String, active: () -> Boolean): PreparedAudio?
    suspend fun close()
}

/** Retained desktop metadata → storage → PlayPlay pipeline. No catalog token, cookie or singleton is accepted. */
internal class DesktopSpotifyEchoClient(
    private val client: HttpClient,
    private val configuration: DesktopSpotifyEchoConfiguration,
) : DesktopSpotifyStreamingTransport {
    private val http = DesktopSpotifyHttp(client)
    private val auth = DesktopSpotifyEchoAuth(client, configuration)

    override suspend fun userId(spDc: String, active: () -> Boolean): String? {
        val session = auth.session(spDc, active)
        val id = desktopSpotifyProfileId(client, session.bearer)
        ensureActive(active)
        return id
    }

    override suspend fun resolve(
        spDc: String,
        request: AudioResolveRequest,
        scope: String,
        active: () -> Boolean,
    ): PreparedAudio? {
        val session = auth.session(spDc, active)
        val track = match(session, request, active) ?: return null
        val id = track.item.ref.id
        val metadata = call(session, METADATA_URL, active, SpotifyEchoProto.buildExtendedMetadataRequest(
            "spotify:track:$id", listOf(SpotifyEchoProto.EXTENSION_KIND_TRACK_V4, SpotifyEchoProto.EXTENSION_KIND_AUDIO_FILES)),
            "application/x-protobuf").bytes
        val payloads = SpotifyEchoProto.parseBatchedExtensionResponse(metadata)
        val data = payloads.firstOrNull { it.kind == SpotifyEchoProto.EXTENSION_KIND_TRACK_V4 }
            ?.let { SpotifyEchoProto.parseTrack(it.data) } ?: return null
        val extended = payloads.firstOrNull { it.kind == SpotifyEchoProto.EXTENSION_KIND_AUDIO_FILES }
            ?.let { SpotifyEchoProto.parseAudioFilesExtension(it.data) }.orEmpty()
        val files = when {
            (data.restrictionCount > 0 || data.files.isEmpty()) && data.alternativeFiles.isNotEmpty() -> data.alternativeFiles
            extended.isNotEmpty() -> extended
            data.files.isNotEmpty() -> data.files
            else -> data.alternativeFiles
        }.filter { it.fileIdHex.length == 40 && it.fileIdHex.all { c -> c.digitToIntOrNull(16) != null } }
        val chosen = FORMAT_ORDER.firstNotNullOfOrNull { format -> files.firstOrNull { it.format == format } } ?: return null
        val storage = call(session, "$STORAGE_URL/${chosen.format}/${chosen.fileIdHex}" +
            "?version=10000000&product=9&platform=39&alt=json", active).json()
        val urls = (storage["cdnurl"] as? JsonArray).orEmpty().mapNotNull { value ->
            (value as? JsonPrimitive)?.content?.takeIf(::safeCdnUrl)
        }.distinct()
        val url = urls.firstOrNull() ?: return null
        val license = call(session, "$KEY_URL/${chosen.fileIdHex}", active,
            SpotifyPlayPlayLicenseRequest.encode(), "application/x-protobuf").bytes
        val key = SpotifyPlayPlayRecordDecoder.decodeObfuscatedKey(
            SpotifyPlayPlayRecordDecoder.parseObfuscatedKey(license))
        val keyHex = key.joinToString("") { "%02x".format(it.toInt() and 255) }
        key.fill(0)
        ensureActive(active)
        val flac = chosen.format in setOf(SpotifyEchoProto.FORMAT_FLAC_FLAC, SpotifyEchoProto.FORMAT_FLAC_FLAC_24BIT)
        val stream = DirectStream(url, if (flac) "audio/flac" else "audio/ogg", if (flac) "flac" else "vorbis",
            null, AudioSourceType.SPOTIFY.name, AudioSourceType.SPOTIFY, track.item.title,
            track.artists.joinToString(", "), track.album, track.item.durationMs,
            trustedDirectId = request.directIds[AudioSourceType.SPOTIFY] == id, accountScope = scope,
            expiresAtEpochMs = StreamUrlExpiry.fromUrl(url))
        return PreparedAudio.Native(stream,
            NativeAudioRecipe.SpotifyPlayPlay(url, AudioSecret(keyHex), AudioSecret(configuration.audioIv), flac, scope))
    }

    private suspend fun match(session: DesktopSpotifyDesktopSession, request: AudioResolveRequest,
        active: () -> Boolean): ProviderTrack? {
        request.directIds[AudioSourceType.SPOTIFY]?.let { id ->
            if (!TRACK_ID.matches(id)) return null
            return parseRest(call(session, "https://api.spotify.com/v1/tracks/$id", active).json())
                ?.takeIf { it.item.ref.id == id }
        }
        val query = (listOf(request.target.title) + request.target.artists.take(1)).joinToString(" ")
        val tracks = searchGraphQl(session, query, active).ifEmpty {
            val root = call(session, DesktopSpotifyHttp.url("https://api.spotify.com/v1/search",
                mapOf("q" to query, "type" to "track", "limit" to "20")), active).json()
            (root.spotifyObject("tracks")?.get("items") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.let(::parseRest) }
        }
        val best = TrackMatching.best(request.target, tracks.map { it.candidate() }) ?: return null
        return tracks.firstOrNull { it.item.ref.id == best.id }
    }

    private suspend fun searchGraphQl(session: DesktopSpotifyDesktopSession, query: String,
        active: () -> Boolean): List<ProviderTrack> = try {
        val hash = SpotifyHashProvider.getHash("searchDesktop")
        val body = buildJsonObject {
            put("operationName", "searchDesktop")
            put("variables", buildJsonObject {
                put("searchTerm", query); put("offset", 0); put("limit", 20); put("numberOfTopResults", 5)
                put("includeAudiobooks", false); put("includeArtistHasConcertsField", false)
                put("includePreReleases", false); put("includeLocalConcertsField", false); put("includeAuthors", false)
            })
            put("extensions", buildJsonObject {
                put("persistedQuery", buildJsonObject { put("version", 1); put("sha256Hash", hash) })
            })
        }
        val root = call(session, "https://api-partner.spotify.com/pathfinder/v2/query", active,
            body.toString().encodeToByteArray(), "application/json").json()
        val rows = root.spotifyObject("data")?.spotifyObject("searchV2")?.spotifyObject("tracksV2")
            ?.get("items") as? JsonArray
        rows.orEmpty().mapNotNull { value ->
            val raw = value as? JsonObject ?: return@mapNotNull null
            val wrapper = raw.spotifyObject("item") ?: raw
            val data = wrapper.spotifyObject("data") ?: wrapper
            parseGraphQl(data, wrapper.spotifyText("_uri") ?: wrapper.spotifyText("uri"))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unavailable: AudioProviderUnavailable) {
        throw unavailable
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun call(session: DesktopSpotifyDesktopSession, url: String, active: () -> Boolean,
        body: ByteArray? = null, contentType: String? = null): DesktopSpotifyReply {
        ensureActive(active)
        val result = http.exchange(url, if (body == null) HttpMethod.Get else HttpMethod.Post, buildMap {
            put("Authorization", "Bearer ${session.bearer}")
            session.clientToken?.let { put("client-token", it) }
            put("Accept", contentType ?: "application/json")
            put("User-Agent", DesktopSpotifyEchoAuth.USER_AGENT)
            put("App-Platform", "Win32_x86_64")
            put("spotify-app-version", DesktopSpotifyEchoAuth.APP_VERSION)
            put("Accept-Language", "en")
            contentType?.let { put("Content-Type", it) }
        }, body)
        ensureActive(active)
        if (result.status !in 200..299) throw DesktopSpotifyProtocolFailure(result.status)
        return result
    }

    override suspend fun close() {
        auth.close()
        http.close()
    }

    private fun ensureActive(active: () -> Boolean) {
        if (!active()) throw AudioProviderUnavailable(AudioProviderUnavailableReason.ACCOUNT_CHANGED)
    }

    companion object {
        private const val METADATA_URL = "https://spclient.wg.spotify.com/extended-metadata/v0/extended-metadata"
        private const val STORAGE_URL = "https://spclient.wg.spotify.com/storage-resolve/v2/files/audio/interactive"
        private const val KEY_URL = "https://spclient.wg.spotify.com/playplay/v1/key"
        private val FORMAT_ORDER = listOf(SpotifyEchoProto.FORMAT_OGG_VORBIS_320, SpotifyEchoProto.FORMAT_FLAC_FLAC,
            SpotifyEchoProto.FORMAT_OGG_VORBIS_160, SpotifyEchoProto.FORMAT_FLAC_FLAC_24BIT, SpotifyEchoProto.FORMAT_OGG_VORBIS_96)
        private val TRACK_ID = Regex("^[A-Za-z0-9]{22}$")

        private fun safeCdnUrl(value: String): Boolean = runCatching {
            val url = Url(value)
            url.protocol.name == "https" && url.host.isNotBlank() && url.user.isNullOrEmpty() && url.password.isNullOrEmpty()
        }.getOrDefault(false)

        internal fun parseRest(row: JsonObject): ProviderTrack? {
            val id = row.spotifyText("id")?.takeIf(TRACK_ID::matches) ?: return null
            val title = row.spotifyText("name") ?: return null
            val artists = (row["artists"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.spotifyText("name") }
            val album = row.spotifyObject("album")
            return track(id, title, artists, album?.spotifyText("name"),
                (row["duration_ms"] as? JsonPrimitive)?.longOrNull, row)
        }

        internal fun parseGraphQl(row: JsonObject, wrapperUri: String?): ProviderTrack? {
            val uri = (row.spotifyText("uri") ?: wrapperUri)?.takeIf { it.startsWith("spotify:track:") } ?: return null
            val id = uri.substringAfterLast(':').takeIf(TRACK_ID::matches) ?: return null
            val title = row.spotifyText("name") ?: return null
            val artists = (row.spotifyObject("artists")?.get("items") as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonObject)?.spotifyObject("profile")?.spotifyText("name")
            }
            return track(id, title, artists, row.spotifyObject("albumOfTrack")?.spotifyText("name"),
                (row.spotifyObject("duration")?.get("totalMilliseconds") as? JsonPrimitive)?.longOrNull, row)
        }

        private fun track(id: String, title: String, artists: List<String>, album: String?, duration: Long?,
            raw: JsonObject): ProviderTrack? {
            if (artists.isEmpty()) return null
            return ProviderTrack(ContentItem(ContentRef(ContentProvider.SPOTIFY_STREAMING.id, ContentKind.SONG, id),
                title, artists.joinToString(", "), durationMs = duration, album = album,
                artists = artists.map { ContentCredit(it) }), artists, album, raw.spotifyObject("external_ids")?.spotifyText("isrc"), raw.toString())
        }
    }
}

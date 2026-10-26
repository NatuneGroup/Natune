/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

class AppleMusicAccess(val scope: String, val developerToken: String, val mediaUserToken: String,
    val storefront: String, val credentialScope: CredentialScope, val isActive: () -> Boolean) {
    init {
        require(listOf(scope, developerToken, mediaUserToken, storefront).all { it.isNotBlank() })
        require(credentialScope in setOf(CredentialScope.PERSONAL, CredentialScope.PLAYBACK_POOL))
        require(storefront.all { it.isLetterOrDigit() || it == '-' })
    }
    override fun toString() = "AppleMusicAccess(REDACTED)"
}

/** AMP catalog and actual webPlayback ctrp assets; the native owner performs the license exchange. */
class KtorAppleMusicRepository(client: HttpClient, private val access: AppleMusicAccess,
    apiBase: String = "https://amp-api.music.apple.com",
    playbackUrl: String = "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback") :
    PagedAudioProvider("apple:${access.scope}:${access.storefront}:$apiBase") {
    override val provider = ContentProvider.APPLE
    override val source = AudioSourceType.APPLE
    private val http = ProviderHttp(client)
    private val api = endpoint(apiBase)
    private val playback = streamUrl(playbackUrl)
    private fun headers() = mapOf("Authorization" to "Bearer ${access.developerToken}",
        "Media-User-Token" to access.mediaUserToken, "Origin" to "https://music.apple.com", "Referer" to "https://music.apple.com/")
    private suspend fun call(path: String, params: Map<String, String> = emptyMap()): JsonObject {
        check(access.isActive()) { "Apple account is no longer active" }
        val result = http.json("$api/v1/catalog/${access.storefront}/$path", params, headers()).jsonObject
        check(access.isActive()) { "Apple account is no longer active" }
        return result
    }
    override suspend fun tracks(request: ContentRequest, offset: Int, limit: Int): ProviderTrackPage {
        val paging = mapOf("offset" to "$offset", "limit" to "$limit")
        val root = when (request.surface) {
            ContentSurface.SEARCH -> call("search", paging + mapOf("term" to requireNotNull(request.text), "types" to "songs"))
                .obj("results")?.obj("songs") ?: throw ProviderProtocolFailure()
            ContentSurface.ALBUM -> call("albums/${pathId(requireNotNull(request.parent).id)}/tracks", paging)
            ContentSurface.PLAYLIST -> call("playlists/${pathId(requireNotNull(request.parent).id)}/tracks", paging)
            else -> throw UnsupportedContentOperation()
        }
        val rows = root.array("data") ?: throw ProviderProtocolFailure()
        return ProviderTrackPage(rows.map { parse(it.jsonObject) }, root.obj("meta")?.long("total"),
            hasMore = root.text("next") != null)
    }
    override suspend fun track(id: String): ProviderTrack =
        parse(call("songs/${pathId(id)}").array("data")?.singleOrNull()?.jsonObject ?: throw ProviderProtocolFailure())

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        check(access.isActive()) { "Apple account is no longer active" }
        val track = match(request) ?: return null
        val id = track.item.ref.id.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val root = http.json(playback, headers = headers(), body = buildJsonObject {
            put("salableAdamId", id); put("language", "en-us")
        }).jsonObject
        if (root.preview()) return null
        val song = root.array("songList")?.firstOrNull()?.jsonObject ?: return null
        val assets = song.array("assets")?.mapNotNull { it as? JsonObject }?.filter {
            it.text("flavor")?.contains("ctrp") == true && it.text("URL") != null
        }?.sortedByDescending { bitrate(it) }.orEmpty()
        val asset = when (request.qualities[source] ?: "LOSSLESS") {
            "AAC" -> assets.firstOrNull { bitrate(it) <= 320 } ?: assets.lastOrNull()
            "LOSSLESS" -> assets.firstOrNull { bitrate(it) in 321..1411 } ?: assets.firstOrNull()
            "HI_RES_LOSSLESS" -> assets.firstOrNull()
            else -> throw IllegalArgumentException("Unknown Apple quality")
        } ?: return null
        val url = streamUrl(requireNotNull(asset.text("URL")))
        val playlist = http.text(url, headers = mapOf("Accept" to "application/vnd.apple.mpegurl"))
        if (!playlist.trimStart().startsWith("#EXTM3U")) throw ProviderProtocolFailure()
        // Keep the unmodified playlist. Native preparation validates the KID/byteranges and refuses
        // unsupported FairPlay, rather than treating its encrypted fMP4 as ordinary audio.
        check(access.isActive()) { "Apple account is no longer active" }
        val stream = direct(request, track, url, "application/vnd.apple.mpegurl", "",
            accountScope = streamAccount(access.scope))
        return PreparedAudio.Native(stream, NativeAudioRecipe.AppleHls(url, playlist,
            streamUrl(song.text("hls-key-server-url") ?: playback.replace("webPlayback", "acquireWebPlaybackLicense")),
            requireNotNull(asset.text("flavor")), access.scope))
    }
    private fun bitrate(asset: JsonObject) = asset.text("flavor")?.takeLastWhile { it.isDigit() }?.toIntOrNull() ?: 0
    private fun parse(row: JsonObject): ProviderTrack {
        val a = row.obj("attributes") ?: throw ProviderProtocolFailure()
        val id = row.text("id") ?: throw ProviderProtocolFailure()
        val title = a.text("name") ?: throw ProviderProtocolFailure()
        val artist = a.text("artistName")
        val image = a.obj("artwork")?.text("url")?.replace("{w}", "600")?.replace("{h}", "600")
        return ProviderTrack(ContentItem(ContentRef(provider.id, ContentKind.SONG, id), title, artist.orEmpty(),
            image, a.long("durationInMillis"), a.text("contentRating") == "explicit"),
            listOfNotNull(artist), a.text("albumName"), a.text("isrc"), row.toString())
    }
}

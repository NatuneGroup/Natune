package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

class AppleMusicAccess(val scope: String, val developerToken: String, val mediaUserToken: String,
    val storefront: String? = null, val credentialScope: CredentialScope, val isActive: () -> Boolean) {
    init {
        require(listOf(scope, developerToken, mediaUserToken).all { it.isNotBlank() })
        require(credentialScope in setOf(CredentialScope.PERSONAL, CredentialScope.PLAYBACK_POOL))
        require(storefront == null || storefront.all { it.isLetterOrDigit() || it == '-' })
    }
    override fun toString() = "AppleMusicAccess(REDACTED)"
}

/**
 * AMP catalog and actual webPlayback ctrp assets; the native owner performs the license exchange.
 * Retained behaviour: the storefront is resolved per token via `/v1/me/storefront` (24 h cache,
 * "us" on failure) instead of trusting a stored value, and the account ring — personal token
 * first, then pooled entries — rotates past 401/403 rejections.
 */
class KtorAppleMusicRepository(client: HttpClient, private val access: AppleMusicAccess,
    private val ring: () -> List<AppleMusicAccess> = { emptyList() },
    private val nowMs: () -> Long = { 0L },
    apiBase: String = "https://amp-api.music.apple.com",
    playbackUrl: String = "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback") :
    PagedAudioProvider("apple:${access.scope}:$apiBase") {
    override val provider = ContentProvider.APPLE
    override val source = AudioSourceType.APPLE
    private val http = ProviderHttp(client)
    private val api = endpoint(apiBase)
    private val playback = streamUrl(playbackUrl)
    private val storefrontMutex = Mutex()
    private val storefrontCache = mutableMapOf<String, Pair<String, Long>>()

    /** The account ring: the personal media-user-token first, then the shared pool's entries. */
    private fun ringAccounts(): List<AppleMusicAccess> =
        (listOf(access) + ring()).distinctBy { it.scope + it.mediaUserToken }.filter { it.isActive() }

    /** Storefront resolved from the Media-User-Token (`/v1/me/storefront`), cached 24 h. */
    private suspend fun storefront(access: AppleMusicAccess): String =
        storefrontMutex.withLock {
            access.storefront?.takeIf { it.isNotBlank() }?.let { return@withLock it }
            val now = nowMs()
            storefrontCache[access.mediaUserToken]?.let { (value, at) ->
                if (now - at < STOREFRONT_CACHE_MS) return@withLock value
            }
            val resolved = try {
                http.json("$api/v1/me/storefront",
                    headers = mapOf("Authorization" to "Bearer ${access.developerToken}",
                        "Media-User-Token" to access.mediaUserToken)).jsonObject
                    .array("data")?.firstOrNull()?.jsonObject?.obj("attributes")?.text("name")
                    ?: "us"
            } catch (e: ProviderHttpFailure) {
                if (e.status == 401 || e.status == 403) throw AppleAuthException()
                "us"
            } catch (e: Exception) { "us" }
            storefrontCache[access.mediaUserToken] = resolved to now
            resolved
        }

    private fun headers(access: AppleMusicAccess) = mapOf("Authorization" to "Bearer ${access.developerToken}",
        "Media-User-Token" to access.mediaUserToken, "Origin" to "https://music.apple.com", "Referer" to "https://music.apple.com/")
    private suspend fun call(path: String, params: Map<String, String> = emptyMap()): JsonObject {
        check(access.isActive()) { "Apple account is no longer active" }
        val storefront = storefront(access)
        val result = try {
            http.json("$api/v1/catalog/$storefront/$path", params, headers(access)).jsonObject
        } catch (e: ProviderHttpFailure) {
            if (e.status == 401 || e.status == 403) throw AppleAuthException()
            throw e
        }
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
        // Retained ring order: personal first, then pool entries; rotate past each auth rejection.
        for (account in ringAccounts()) {
            try {
                return resolveViaAccount(request, account) ?: continue
            } catch (e: AppleAuthException) {
            }
        }
        return null
    }

    /** One resolve pass with a specific media-user-token. Throws [AppleAuthException] on 401/403. */
    private suspend fun resolveViaAccount(request: AudioResolveRequest, account: AppleMusicAccess): PreparedAudio? {
        check(account.isActive()) { "Apple account is no longer active" }
        val track = match(request) ?: return null
        val id = track.item.ref.id.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val root = try {
            http.json(playback, headers = headers(account), body = buildJsonObject {
                put("salableAdamId", id); put("language", "en-us")
            }).jsonObject
        } catch (e: ProviderHttpFailure) {
            if (e.status == 401 || e.status == 403) throw AppleAuthException()
            throw e
        }
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
        check(account.isActive()) { "Apple account is no longer active" }
        val stream = direct(request, track, url, "application/vnd.apple.mpegurl", "",
            accountScope = streamAccount(account.scope))
        return PreparedAudio.Native(stream, NativeAudioRecipe.AppleHls(url, playlist,
            streamUrl(song.text("hls-key-server-url") ?: playback.replace("webPlayback", "acquireWebPlaybackLicense")),
            requireNotNull(asset.text("flavor")), account.scope))
    }

    /** Thrown by storefront/catalog/webPlayback on 401/403 — the media-user-token is dead. */
    private class AppleAuthException : Exception("apple media-user-token rejected (401/403)")
    private companion object { const val STOREFRONT_CACHE_MS = 24L * 60L * 60L * 1000L }
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

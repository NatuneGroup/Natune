/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

class DeezerAccess(val scope: String, val arl: String,
    val credentialScope: CredentialScope = CredentialScope.PERSONAL,
    val isActive: () -> Boolean = { credentialScope != CredentialScope.PLAYBACK_POOL }) {
    init {
        require(scope.isNotBlank() && arl.isNotBlank() && arl.none { it in "\r\n;" })
        require(credentialScope in setOf(CredentialScope.PERSONAL, CredentialScope.PLAYBACK_POOL))
    }
    override fun toString() = "DeezerAccess(REDACTED)"
}
/** The account behind a verified ARL. [userId] is the gateway USER_ID and identifies the account; [name] is display only. */
class DeezerVerifiedAccount(val userId: Long, val name: String, val lossless: Boolean)

/**
 * Asks the Deezer gateway who [arl] belongs to, as the Android sign-in does. Deezer hands an arl cookie to anonymous
 * visitors too, and those answer with USER_ID 0, so only a non-zero user is an account. The name prefers BLOG_NAME, then
 * FIRSTNAME, then EMAIL. Returns null for a rejected, anonymous or malformed ARL and for a failed request.
 */
suspend fun verifyDeezerArl(
    client: HttpClient,
    arl: String,
    gateway: String = "https://www.deezer.com/ajax/gw-light.php",
): DeezerVerifiedAccount? {
    val value = arl.trim()
    if (value.isEmpty() || value.any { it in "\r\n;" }) return null
    return try {
        val root = ProviderHttp(client).json(endpoint(gateway),
            mapOf("method" to "deezer.getUserData", "input" to "3", "api_version" to "1.0", "api_token" to ""),
            mapOf("Cookie" to "arl=$value"), buildJsonObject {}).jsonObject
        val user = root.obj("results")?.obj("USER") ?: return null
        val userId = user.long("USER_ID")?.takeIf { it > 0L } ?: return null
        val options = user.obj("OPTIONS")
        DeezerVerifiedAccount(
            userId = userId,
            name = user.text("BLOG_NAME") ?: user.text("FIRSTNAME") ?: user.text("EMAIL") ?: "Deezer",
            lossless = options?.flag("web_lossless") == true || options?.flag("mobile_lossless") == true,
        )
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }
}

class KtorDeezerRepository(
    client: HttpClient,
    private val nowMs: () -> Long,
    private val access: DeezerAccess? = null,
    apiBase: String = "https://api.deezer.com",
    gateway: String = "https://www.deezer.com/ajax/gw-light.php",
    mediaEndpoint: String = "https://media.deezer.com/v1/get_url",
) : PagedAudioProvider("deezer:${access?.scope ?: "public"}:$apiBase") {
    override val provider = ContentProvider.DEEZER
    override val source = AudioSourceType.DEEZER
    private val http = ProviderHttp(client)
    private val api = endpoint(apiBase)
    private val gateway = endpoint(gateway)
    private val mediaEndpoint = endpoint(mediaEndpoint)
    private class Session(val apiToken: String, val license: String, val lossless: Boolean, val expiresMs: Long)
    private val sessionMutex = Mutex()
    private var cachedSession: Session? = null
    private fun cookie() = mapOf("Cookie" to "arl=${requireNotNull(access).arl}")
    private suspend fun gateway(method: String, apiToken: String, payload: JsonObject = buildJsonObject {}): JsonObject {
        val root = http.json(gateway, mapOf("method" to method, "input" to "3", "api_version" to "1.0",
            "api_token" to apiToken), cookie(), payload).jsonObject
        val error = root["error"]
        if (error != null && error !is JsonNull && error.toString() !in listOf("{}", "[]", "\"\"")) throw ProviderProtocolFailure()
        return root.obj("results") ?: throw ProviderProtocolFailure()
    }
    private suspend fun session(): Session = sessionMutex.withLock {
        if (access?.isActive() != true) { cachedSession = null; throw UnsupportedContentOperation() }
        cachedSession?.takeIf { nowMs() < it.expiresMs }?.let { return@withLock it }
        val result = gateway("deezer.getUserData", "")
        val user = result.obj("USER") ?: throw ProviderProtocolFailure()
        if ((user.long("USER_ID") ?: 0L) <= 0) throw ProviderHttpFailure(401)
        val options = user.obj("OPTIONS") ?: throw ProviderProtocolFailure()
        Session(result.text("checkForm") ?: throw ProviderProtocolFailure(),
            options.text("license_token") ?: throw ProviderProtocolFailure(),
            options.flag("web_lossless") || options.flag("mobile_lossless"), nowMs() + 45 * 60_000L)
            .also { cachedSession = it }
    }
    override suspend fun tracks(request: ContentRequest, offset: Int, limit: Int): ProviderTrackPage {
        val path = when (request.surface) {
            ContentSurface.SEARCH -> "search"
            ContentSurface.ALBUM -> "album/${pathId(requireNotNull(request.parent).id)}/tracks"
            ContentSurface.PLAYLIST -> "playlist/${pathId(requireNotNull(request.parent).id)}/tracks"
            else -> throw UnsupportedContentOperation()
        }
        val params = mapOf("index" to "$offset", "limit" to "$limit") +
            if (request.surface == ContentSurface.SEARCH) mapOf("q" to requireNotNull(request.text)) else emptyMap()
        val root = http.json("$api/$path", params).jsonObject
        if (root.obj("error") != null) throw ProviderProtocolFailure()
        return ProviderTrackPage((root.array("data") ?: throw ProviderProtocolFailure()).map { parse(it.jsonObject) },
            root.long("total"))
    }
    override suspend fun track(id: String): ProviderTrack {
        val result = http.json("$api/track/${pathId(id)}").jsonObject
        if (result.obj("error") != null) throw ProviderProtocolFailure()
        return parse(result)
    }
    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        if (access == null) return null // public preview URLs never become full streams
        val session = session()
        val isrc = request.isrc?.takeIf { it.isNotBlank() }
        val track = if (request.directIds[source] == null && isrc != null) {
            val exact = try {
                val root = http.json("$api/track/isrc:${pathId(isrc)}").jsonObject
                if (root.obj("error") == null) parse(root) else null
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { null }
            exact ?: match(request)
        } else match(request)
        if (track == null) return null
        val details = gateway("song.getData", session.apiToken, buildJsonObject { put("sng_id", track.item.ref.id) })
        val token = details.text("TRACK_TOKEN") ?: return null
        val requested = request.qualities[source] ?: "FLAC"
        require(requested in TIERS)
        val effective = if (requested == "FLAC" && !session.lossless) "MP3_320" else requested
        val formats = TIERS.dropWhile { it != effective }
        val root = http.json(mediaEndpoint, headers = cookie(), body = buildJsonObject {
            put("license_token", session.license)
            putJsonArray("media") { addJsonObject {
                put("type", "FULL")
                putJsonArray("formats") { formats.forEach { format -> addJsonObject {
                    put("cipher", "BF_CBC_STRIPE"); put("format", format)
                } } }
            } }
            putJsonArray("track_tokens") { add(token) }
        }).jsonObject
        if (!access.isActive()) { sessionMutex.withLock { cachedSession = null }; throw UnsupportedContentOperation() }
        val data = root.array("data")?.firstOrNull()?.jsonObject ?: return null
        val media = data.array("media")?.firstOrNull()?.jsonObject ?: return null
        val format = media.text("format")?.takeIf { it in formats } ?: return null
        if (media.preview() || media.text("type")?.let { it != "FULL" } == true) return null
        if (media.text("cipher")?.let { it != "BF_CBC_STRIPE" } == true) throw UnsupportedContentOperation()
        val url = media.array("sources")?.firstOrNull()?.jsonObject?.text("url") ?: return null
        val stream = direct(request, track, url, if (format == "FLAC") "audio/flac" else "audio/mpeg",
            if (format == "FLAC") "flac" else "mp3", details.long("FILESIZE_$format") ?: details.long("FILESIZE"),
            accountScope = streamAccount(access.scope))
        return PreparedAudio.DeezerEncrypted(stream.copy(trustedDirectId = stream.trustedDirectId ||
            isrc != null && isrc.equals(track.isrc, true)), track.item.ref.id, access.scope)
    }
    private fun parse(obj: JsonObject): ProviderTrack {
        val id = obj.text("id") ?: throw ProviderProtocolFailure()
        val artists = obj.array("contributors")?.mapNotNull { it.jsonObject.text("name") }?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(obj.obj("artist")?.text("name"))
        val album = obj.obj("album")
        return ProviderTrack(ContentItem(ContentRef(provider.id, ContentKind.SONG, id),
            obj.text("title") ?: throw ProviderProtocolFailure(), artists.joinToString(", "), album?.text("cover_big"),
            obj.long("duration")?.times(1000), obj.flag("explicit_lyrics")), artists, album?.text("title"),
            obj.text("isrc"), obj.toString())
    }
    private companion object { val TIERS = listOf("FLAC", "MP3_320", "MP3_128") }
}

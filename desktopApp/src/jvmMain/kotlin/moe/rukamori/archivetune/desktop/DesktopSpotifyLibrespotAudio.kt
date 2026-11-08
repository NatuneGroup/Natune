package moe.rukamori.archivetune.desktop

import io.ktor.client.HttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import moe.rukamori.archivetune.desktop.provider.DesktopSourceAdmissions
import moe.rukamori.archivetune.desktop.provider.DesktopSpotifyEchoClient
import moe.rukamori.archivetune.desktop.provider.DesktopSpotifyHttp
import moe.rukamori.archivetune.desktop.provider.spotifyObject
import moe.rukamori.archivetune.desktop.provider.spotifyText
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.platform.ProviderIdentity
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.source.*
import moe.rukamori.archivetune.spotify.librespot.SpotifyLibrespotNative

/** Uses the existing OAuth owner, Rust AP/audio-key resolver and shared Spotify CTR preparation. */
internal class DesktopSpotifyLibrespotAudio(
    client: HttpClient,
    private val preferences: () -> PreferenceSnapshot?,
    private val admissions: DesktopSourceAdmissions,
    private val cacheDirectory: Path,
) {
    var accessToken: (suspend () -> String?)? = null
    private val http = DesktopSpotifyHttp(client)
    private val gate = Mutex()
    private val deviceId = UUID.randomUUID().toString().replace("-", "")
    private var connectedToken: String? = null
    private var cooldownUntil = 0L
    private var closed = false

    suspend fun resolve(request: AudioResolveRequest): PreparedAudio? = gate.withLock {
        if (closed || request.purpose == AudioPurpose.DOWNLOAD || !SpotifyLibrespotNative.available)
            unavailable(AudioProviderUnavailableReason.PLATFORM_DECRYPTION_REQUIRED)
        if (System.currentTimeMillis() < cooldownUntil) return@withLock null
        val token = accessToken?.invoke() ?: unavailable(AudioProviderUnavailableReason.ACCOUNT_REQUIRED)
        val prefs = preferences() ?: unavailable(AudioProviderUnavailableReason.ACCOUNT_REQUIRED)
        if (!ready(prefs)) unavailable(AudioProviderUnavailableReason.ACCOUNT_CHANGED)
        val user = prefs[SpotifyLibrespotUserIdKey].orEmpty()
        val grant = prefs[SpotifyLibrespotRefreshTokenKey]
        val catalog = prefs[SpotifySpDcKey]
        val binding = prefs[SpotifyLibrespotCatalogSpDcKey]
        val proxy = desktopProxyConfig(prefs)
        if (prefs[IpRotationEnabledKey, false] || (prefs[ProxyEnabledKey, false] && proxy == null) ||
            (proxy != null && (proxy.proxy.type() != Proxy.Type.HTTP || proxy.hasCredentials)))
            unavailable(AudioProviderUnavailableReason.PLATFORM_DECRYPTION_REQUIRED)
        val proxyUrl = (proxy?.proxy?.address() as? InetSocketAddress)?.let {
            io.ktor.http.URLBuilder().apply { protocol = io.ktor.http.URLProtocol.HTTP; host = it.hostString; port = it.port }.buildString()
        }.orEmpty()
        val revocation = SpotifyLibrespotNative.revocation
        fun current(): Boolean = !closed && SpotifyLibrespotNative.revocation == revocation && preferences()?.let {
            ready(it) && it[SpotifyLibrespotUserIdKey] == user && it[SpotifyLibrespotRefreshTokenKey] == grant &&
                it[SpotifySpDcKey] == catalog && it[SpotifyLibrespotCatalogSpDcKey] == binding &&
                desktopProxyConfig(it)?.toString() == proxy?.toString() && it[ProxyHostKey] == prefs[ProxyHostKey] &&
                it[ProxyPortKey] == prefs[ProxyPortKey] && it[IpRotationEnabledKey, false] == false
        } == true
        suspend fun native(operation: () -> String): JsonObject {
            if (!current()) unavailable(AudioProviderUnavailableReason.ACCOUNT_CHANGED)
            val reply = Json.parseToJsonElement(SpotifyLibrespotNative.request({ SpotifyLibrespotNative.close() }, operation)) as? JsonObject
                ?: unavailable(AudioProviderUnavailableReason.PLATFORM_DECRYPTION_REQUIRED)
            if (!current()) { SpotifyLibrespotNative.close(); unavailable(AudioProviderUnavailableReason.ACCOUNT_CHANGED) }
            reply.spotifyText("error")?.let { code ->
                connectedToken = null
                if (code == "RATE_LIMITED") cooldownUntil = System.currentTimeMillis() + 60_000L
                unavailable(AudioProviderUnavailableReason.PLATFORM_DECRYPTION_REQUIRED)
            }
            return reply
        }
        val track = match(token, request) ?: return@withLock null
        if (connectedToken != token || !SpotifyLibrespotNative.nativeSessionValid()) {
            Files.createDirectories(cacheDirectory)
            val generation = SpotifyLibrespotNative.nativeGeneration()
            val result = native { SpotifyLibrespotNative.nativeConnect(token, user, deviceId,
                cacheDirectory.toAbsolutePath().toString(), proxyUrl, 0, generation, 20_000L) }
            if ((result["ok"] as? JsonPrimitive)?.booleanOrNull != true) return@withLock null
            connectedToken = token
        }
        val formats = SpotifyLibrespotAudioPolicy.preferredFormats(request.qualities[AudioSourceType.SPOTIFY], false)
        val generation = SpotifyLibrespotNative.nativeGeneration()
        val result = native { SpotifyLibrespotNative.nativeResolve("spotify:track:${track.item.ref.id}",
            formats.toIntArray(), generation, 20_000L) }
        val format = (result["format"] as? JsonPrimitive)?.intOrNull ?: return@withLock null
        val key = result.spotifyText("keyHex")?.takeIf { Regex("[0-9a-f]{32}").matches(it) } ?: return@withLock null
        if (format !in formats) return@withLock null
        val url = (result["urls"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            .firstOrNull { runCatching { io.ktor.http.Url(it).let { u -> u.protocol.name == "https" &&
                u.host.isNotBlank() && u.user.isNullOrEmpty() && u.password.isNullOrEmpty() } }.getOrDefault(false) }
            ?: return@withLock null
        val lease = admissions.accept(ProviderIdentity("spotify", CredentialScope.SPOTIFY_STREAMING, user),
            grant.orEmpty(), ::current) ?: unavailable(AudioProviderUnavailableReason.ACCOUNT_CHANGED)
        val expiry = minOf(System.currentTimeMillis() + 20 * 60_000L, StreamUrlExpiry.fromUrl(url) ?: Long.MAX_VALUE)
        val stream = DirectStream(url, "audio/ogg", "vorbis", null,
            "Spotify Librespot", AudioSourceType.SPOTIFY, result.spotifyText("title"), result.spotifyText("artist"),
            result.spotifyText("album"), (result["durationMs"] as? JsonPrimitive)?.longOrNull,
            trustedDirectId = request.directIds[AudioSourceType.SPOTIFY] != null, accountScope = lease.scope, expiresAtEpochMs = expiry)
        PreparedAudio.Native(stream, NativeAudioRecipe.SpotifyPlayPlay(url, AudioSecret(key),
            AudioSecret("72e067fbddcbcf77ebe8bc643f630d93"), false, lease.scope))
    }

    private suspend fun match(token: String, request: AudioResolveRequest): ProviderTrack? {
        suspend fun get(url: String): JsonObject {
            val reply = http.exchange(url, headers = mapOf("Authorization" to "Bearer $token"))
            if (reply.status !in 200..299) unavailable(AudioProviderUnavailableReason.ACCOUNT_REQUIRED)
            return reply.json()
        }
        request.directIds[AudioSourceType.SPOTIFY]?.let { raw ->
            val id = SpotifyLibrespotAudioPolicy.trackId(raw) ?: return null
            return DesktopSpotifyEchoClient.parseRest(get("https://api.spotify.com/v1/tracks/$id"))
        }
        val isrc = SpotifyLibrespotAudioPolicy.isrc(request.isrc)
        val query = isrc?.let { "isrc:$it" } ?: (listOf(request.target.title) + request.target.artists.take(1)).joinToString(" ")
        val root = get(DesktopSpotifyHttp.url("https://api.spotify.com/v1/search", mapOf("q" to query, "type" to "track", "limit" to "20")))
        val tracks = (root.spotifyObject("tracks")?.get("items") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(DesktopSpotifyEchoClient::parseRest) }
        val best = TrackMatching.best(request.target, tracks.map { it.candidate() }) ?: return null
        return tracks.firstOrNull { it.item.ref.id == best.id }
    }

    suspend fun close() = gate.withLock {
        closed = true
        connectedToken = null
        SpotifyLibrespotNative.close()
        http.close()
    }

    companion object {
        fun ready(prefs: PreferenceSnapshot): Boolean = prefs.spotifyStreamEngineEffective() == SpotifyStreamEngine.LIBRESPOT &&
            prefs[SpotifyStreamEnabledKey, false] && prefs.spotifyLibrespotSignedIn() && !prefs[SpotifyLibrespotUserIdKey].isNullOrBlank() &&
            (prefs[SpotifyLibrespotCatalogSpDcKey].isNullOrBlank() || prefs[SpotifyLibrespotCatalogSpDcKey] == prefs[SpotifySpDcKey])
        private fun unavailable(reason: AudioProviderUnavailableReason): Nothing = throw AudioProviderUnavailable(reason)
    }
}

package moe.rukamori.archivetune.spotify.librespot

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptScope
import moe.rukamori.archivetune.audiosource.DirectStream
import moe.rukamori.archivetune.constants.AudioQualityKey
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.constants.SpotifySpDcKey
import moe.rukamori.archivetune.constants.SpotifyStreamEnabledKey
import moe.rukamori.archivetune.playback.SourceQuery
import moe.rukamori.archivetune.shared.domain.platform.ContentDigest
import moe.rukamori.archivetune.shared.domain.source.SpotifyLibrespotAudioPolicy
import moe.rukamori.archivetune.shared.domain.source.SpotifyLibrespotFailure
import moe.rukamori.archivetune.shared.domain.source.SpotifyProduct
import moe.rukamori.archivetune.shared.domain.source.SpotifyStreamEngine
import moe.rukamori.archivetune.shared.domain.source.StreamUrlExpiry
import moe.rukamori.archivetune.spotify.SpotifyLibrespotAccount
import moe.rukamori.archivetune.spotify.SpotifyLibrespotCatalogSpDcKey
import moe.rukamori.archivetune.spotify.SpotifyLibrespotRefreshTokenKey
import moe.rukamori.archivetune.spotify.SpotifyLibrespotUserIdKey
import moe.rukamori.archivetune.spotify.SpotifyProductKey
import moe.rukamori.archivetune.spotify.SpotifyProductCheckedAtKey
import moe.rukamori.archivetune.spotify.SpotifyRequestCooldown
import moe.rukamori.archivetune.spotify.SpotifyStreamEngineKey
import moe.rukamori.archivetune.spotify.echo.SpotifyEchoResolver
import moe.rukamori.archivetune.utils.AppProxy
import moe.rukamori.archivetune.utils.PreferenceStore
import moe.rukamori.archivetune.utils.ProxyUtils
import moe.rukamori.archivetune.utils.dataStore
import org.json.JSONObject

internal class SpotifyLibrespotAudio(private val context: Context) {
    private val gate = Mutex()
    private val lifecycle = Any()
    private val generation = AtomicLong()
    private val deviceId = UUID.randomUUID().toString()
    private val streams = object : LinkedHashMap<String, Stream>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Stream>?): Boolean = size > 64
    }
    private var identity: Identity? = null
    private var nativeToken: String? = null
    private var observer: Job? = null
    @Volatile private var cachedPreferences: Pair<Preferences, Identity>? = null
    @Volatile private var closed = false

    private class Identity(val userId: String, val catalog: String, val binding: String, val eligible: Boolean,
        val engine: String, val quality: String?, val proxy: AppProxy.Config?) {
        val admitted: Boolean get() = eligible && binding == catalog
        val fingerprint by lazy { ContentDigest.sha256("$userId\u0000$catalog\u0000$binding".encodeToByteArray()) }
        override fun equals(other: Any?): Boolean = other is Identity && userId == other.userId &&
            catalog == other.catalog && binding == other.binding && eligible == other.eligible &&
            engine == other.engine && quality == other.quality && proxy == other.proxy
        override fun hashCode(): Int = fingerprint.hashCode()
        override fun toString(): String = "LibrespotIdentity(redacted)"
    }

    internal class Stream(val ref: SpotifyEchoResolver.EchoStreamRef?, val tag: String, val expiresAtMs: Long,
        private val valid: () -> Boolean) {
        fun authorized(): Boolean = valid()
        fun active(): Boolean = valid() && System.currentTimeMillis() < expiresAtMs
        override fun toString(): String = "LibrespotStream(redacted)"
    }

    private fun identity(prefs: Preferences): Identity {
        val engine = SpotifyStreamEngine.fromPreference(prefs[SpotifyStreamEngineKey])
        val catalog = prefs[SpotifySpDcKey].orEmpty()
        val binding = prefs[SpotifyLibrespotCatalogSpDcKey].orEmpty()
        val user = prefs[SpotifyLibrespotUserIdKey].orEmpty()
        return Identity(user, catalog, binding,
            SpotifyLibrespotAudioPolicy.admitted(engine, prefs[SpotifyStreamEnabledKey] == true,
                SpotifyProduct.of(prefs[SpotifyProductKey]), !prefs[SpotifyLibrespotRefreshTokenKey].isNullOrBlank()) &&
                user.isNotBlank(), engine.name, prefs[AudioQualityKey], ProxyUtils.configFromPreferences(prefs))
    }

    private fun cachedIdentity(): Identity? {
        val prefs = PreferenceStore.snapshot() ?: return null
        cachedPreferences?.takeIf { it.first === prefs }?.let { return it.second }
        return identity(prefs).also { cachedPreferences = prefs to it }
    }

    fun enabled(): Boolean = !closed && cachedIdentity()?.admitted == true

    fun cacheAllowed(tag: String?, lowData: Boolean): Boolean = cachedIdentity()?.takeIf { !closed && it.admitted }?.let {
        SpotifyLibrespotAudioPolicy.cacheAdmitted(tag, it.fingerprint, it.quality, lowData)
    } == true

    fun observe(scope: CoroutineScope, onChanged: () -> Unit) {
        observer?.cancel()
        observer = scope.launch(Dispatchers.IO) {
            context.dataStore.data.map(::identity).distinctUntilChanged().collect { next ->
                if (synchronize(next)) onChanged()
            }
        }
    }

    private fun synchronize(next: Identity): Boolean = synchronized(lifecycle) {
        if (identity == next) return@synchronized false
        identity = next
        revoke()
        true
    }

    private fun revoke() {
        generation.incrementAndGet()
        nativeToken = null
        streams.clear()
        SpotifyLibrespotNative.close()
    }

    fun invalidate() = synchronized(lifecycle) { revoke() }

    fun close() {
        closed = true
        observer?.cancel()
        observer = null
        invalidate()
    }

    fun stream(uri: Uri): Stream? = synchronized(lifecycle) {
        if (uri.scheme != SpotifyLibrespotAudioPolicy.SCHEME || uri.host != "audio") return@synchronized null
        streams[uri.toString()]?.takeIf(Stream::active)
    }

    fun cachedUri(tag: String, lowData: Boolean): Uri? = synchronized(lifecycle) {
        val captured = cachedIdentity()?.takeIf { !closed && it.admitted } ?: return@synchronized null
        // Validate the selected entry under the same identity captured by its read guard.
        if (!SpotifyLibrespotAudioPolicy.cacheAdmitted(tag, captured.fingerprint, captured.quality, lowData)) {
            return@synchronized null
        }
        val epoch = generation.get()
        val uri = Uri.Builder().scheme(SpotifyLibrespotAudioPolicy.SCHEME).authority("audio")
            .appendPath(UUID.randomUUID().toString()).build()
        streams[uri.toString()] = Stream(null, tag, Long.MAX_VALUE) {
            !closed && generation.get() == epoch && cachedIdentity() == captured
        }
        uri
    }

    private fun remaining(): Long = (AudioSourceAttemptScope.current()?.remainingMillis() ?: 20_000L)
        .coerceAtMost(20_000L).also { if (it <= 0) throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.TIMEOUT) }

    private suspend fun native(expectedGeneration: Long, operation: (Long) -> String): JSONObject {
        if (nativeCooldown.remainingSeconds() > 0) throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.RATE_LIMITED)
        val nativeGeneration = synchronized(lifecycle) {
            if (closed || generation.get() != expectedGeneration || cachedIdentity() != identity) {
                throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.ACCOUNT_CHANGED)
            }
            SpotifyLibrespotNative.nativeGeneration()
        }
        val result = withTimeoutOrNull(remaining()) {
            SpotifyLibrespotNative.request(onCancelled = {
                synchronized(lifecycle) { if (generation.get() == expectedGeneration) revoke() }
            }, operation = { operation(nativeGeneration) })
        } ?: throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.TIMEOUT)
        currentCoroutineContext().ensureActive()
        val json = JSONObject(result)
        if (json.has("error")) {
            if (generation.get() != expectedGeneration) {
                throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.ACCOUNT_CHANGED)
            }
            val failure = SpotifyLibrespotAudioPolicy.failure(json.optString("error"))
            if (failure == SpotifyLibrespotFailure.RATE_LIMITED) {
                nativeCooldown.recordRateLimit(json.optString("retryAfter").takeUnless { it == "null" })
            }
            if (failure == SpotifyLibrespotFailure.NOT_PREMIUM) {
                val captured = synchronized(lifecycle) { identity }
                context.dataStore.edit {
                    if (generation.get() == expectedGeneration && identity(it) == captured) {
                        it[SpotifyProductKey] = "free"
                        it[SpotifyProductCheckedAtKey] = System.currentTimeMillis()
                    }
                }
            }
            if (failure.reconnectOnNextRequest || failure == SpotifyLibrespotFailure.NOT_PREMIUM ||
                failure == SpotifyLibrespotFailure.ACCOUNT_CHANGED) invalidate()
            throw SpotifyLibrespotAudioException(failure)
        }
        return json
    }

    suspend fun resolve(query: SourceQuery, trusted: Boolean, lowData: Boolean): Pair<DirectStream, String>? = gate.withLock {
        if (closed) return@withLock null
        val before = identity(context.dataStore.data.first())
        synchronize(before)
        if (!before.eligible || (before.binding.isNotBlank() && before.binding != before.catalog)) return@withLock null
        val token = SpotifyLibrespotAccount.accessToken(context) ?: run { invalidate(); return@withLock null }
        val captured = identity(context.dataStore.data.first())
        val justBound = before.binding.isBlank() && captured.binding == before.catalog && captured.userId == before.userId &&
            captured.catalog == before.catalog && captured.quality == before.quality && captured.proxy == before.proxy
        if (!captured.admitted || (captured != before && !justBound)) { synchronize(captured); return@withLock null }
        synchronize(captured)
        val expectedGeneration = generation.get()
        fun current(): Boolean = !closed && generation.get() == expectedGeneration && cachedIdentity() == captured
        val trackId = SpotifyLibrespotTrackSearch.find(token, query) ?: return@withLock null
        if (!current()) return@withLock null
        if (!SpotifyLibrespotNative.available) throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.NATIVE_UNAVAILABLE)
        val proxy = captured.proxy
        if (proxy != null && (proxy.type != Proxy.Type.HTTP || proxy.hasCredentials)) {
            // Native AP proxy support cannot honor SOCKS/authenticated proxies; do not bypass one.
            throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.NATIVE_UNAVAILABLE)
        }
        if (synchronized(lifecycle) { nativeToken != token } || !SpotifyLibrespotNative.nativeSessionValid()) {
            val timeout = remaining()
            native(expectedGeneration) { nativeGeneration -> SpotifyLibrespotNative.nativeConnect(token, captured.userId, deviceId,
                context.cacheDir.absolutePath, proxy?.let {
                    okhttp3.HttpUrl.Builder().scheme("http").host(it.host).port(it.port).build().toString()
                }.orEmpty(), android.os.Build.VERSION.SDK_INT, nativeGeneration, timeout) }
            synchronized(lifecycle) { if (current()) nativeToken = token }
        }
        val formats = SpotifyLibrespotAudioPolicy.preferredFormats(captured.quality, lowData)
        val timeout = remaining()
        val result = native(expectedGeneration) { nativeGeneration ->
            SpotifyLibrespotNative.nativeResolve("spotify:track:$trackId", formats.toIntArray(), nativeGeneration, timeout)
        }
        if (!current() || identity(context.dataStore.data.first()) != captured) return@withLock null
        val format = result.getInt("format")
        if (format !in formats) throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.UNSUPPORTED_FORMAT)
        val fileId = result.getString("fileId").takeIf { it.matches(Regex("[0-9a-f]{40}")) }
            ?: throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.INVALID_RESPONSE)
        val key = result.getString("keyHex").takeIf { it.matches(Regex("[0-9a-f]{32}")) }
            ?: throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.INVALID_RESPONSE)
        val urls = result.getJSONArray("urls").let { array -> (0 until minOf(array.length(), 4)).map { array.getString(it) } }
        if (urls.isEmpty()) throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.INVALID_RESPONSE)
        val expiry = minOf(System.currentTimeMillis() + 20 * 60_000L,
            urls.mapNotNull(StreamUrlExpiry::fromUrl).minOrNull() ?: Long.MAX_VALUE)
        val tag = "${SpotifyLibrespotAudioPolicy.cacheNamespace(captured.fingerprint, captured.quality, lowData)}:$format:$fileId"
        val uri = Uri.Builder().scheme(SpotifyLibrespotAudioPolicy.SCHEME).authority("audio")
            .appendPath(UUID.randomUUID().toString()).build()
        val stream = Stream(SpotifyEchoResolver.EchoStreamRef(urls, key, format, fileId), tag, expiry, ::current)
        synchronized(lifecycle) {
            if (!current()) return@withLock null
            streams[uri.toString()] = stream
        }
        DirectStream(uri.toString(), "audio/ogg", "vorbis", null,
            "Spotify Librespot Vorbis ${SpotifyLibrespotAudioPolicy.bitrate(format)?.div(1000)}", AudioSourceType.SPOTIFY,
            matchedTitle = result.optString("title"), matchedArtist = result.optString("artist"),
            matchedAlbum = result.optString("album").takeUnless { it == "null" },
            matchedDurationMs = result.optLong("durationMs").takeIf { it > 0 },
            trustedDirectId = trusted || query.spotifyTrackId != null || !query.isrc.isNullOrBlank(),
            expiresAtEpochMs = expiry) to tag
    }

    private companion object {
        val nativeCooldown = SpotifyRequestCooldown()
    }
}

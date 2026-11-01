package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.desktop.DesktopCredentials
import moe.rukamori.archivetune.shared.data.provider.ArchivePoolEndpoints
import moe.rukamori.archivetune.shared.data.provider.PoolDeadReportPolicy
import moe.rukamori.archivetune.shared.data.provider.PoolDeadReporter
import moe.rukamori.archivetune.shared.domain.platform.CredentialKey
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.platform.SecureCredentialStore
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.UsePoolAccountsKey

/** One shared TIDAL subscriber session from the pool feed. [id] is the feed id a report names; manual entries have none. */
internal class TidalPoolAccount(val id: Long?, val token: String, val countryCode: String?, val premium: Boolean) {
    override fun toString() = "TidalPoolAccount(id=$id, REDACTED)"
}

/** One shared Deezer ARL from the pool feed. [premium] false means the account can serve MP3 but not FLAC. */
internal class DeezerPoolAccount(val id: Long?, val arl: String, val premium: Boolean) {
    override fun toString() = "DeezerPoolAccount(id=$id, REDACTED)"
}

/** One shared Qobuz subscriber credential from the pool feed. */
internal class QobuzPoolAccount(val id: Long?, val token: String, val appId: String, val appSecret: String, val premium: Boolean) {
    override fun toString() = "QobuzPoolAccount(id=$id, REDACTED)"
}

/**
 * The desktop client of the Playback Pool, over the same ArchivePool contract as Android's PoolAccountManager: the
 * account feed at `/api/accounts` (the combined `/api/sources` when a deployment predates the split), v2 field
 * encryption keyed by the read key, `/api/discovery/tidal` for community HiFi instances and `/api/report` for accounts
 * that fail for real playback. The Playback Pool switch gates every request and every getter, so with it off the
 * sources fall through to the user's own accounts.
 *
 * The decrypted accounts are cached in the platform vault under their own key, never in the settings file and never
 * over a personal sign-in. The time of the last attempt is cached with them, so an empty or failed feed stays throttled
 * across restarts. TIDAL, Deezer and Qobuz are kept; the other pooled services have no source on this host.
 */
internal class DesktopPlaybackPool(
    private val http: HttpClient,
    private val vault: SecureCredentialStore,
    private val preferences: () -> PreferenceSnapshot?,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val baseUrl: String? = DesktopCredentials.poolBaseUrl,
    private val readKey: String = DesktopCredentials.poolReadKey,
    legacyClientKey: String = DesktopCredentials.poolLegacyClientKey,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** True when this build names a pool deployment, so the Playback Pool rows can be used. */
    val configured: Boolean get() = baseUrl != null

    private val derivedKey: SecretKeySpec? = deriveClientKey(readKey)
    private val legacyKey: SecretKeySpec? = legacyClientKey.trim().takeIf { it.isNotEmpty() }?.let { raw ->
        runCatching { Base64.getMimeDecoder().decode(raw) }.getOrNull()?.takeIf { it.size == 32 }?.let { SecretKeySpec(it, "AES") }
    }

    private val mutex = Mutex()
    @Volatile private var loaded = false
    @Volatile private var tidal: List<TidalPoolAccount> = emptyList()
    @Volatile private var deezer: List<DeezerPoolAccount> = emptyList()
    @Volatile private var qobuz: List<QobuzPoolAccount> = emptyList()
    @Volatile private var lastAttemptAtMs = 0L
    @Volatile private var discovered: List<String> = emptyList()
    @Volatile private var discoveredAtMs = 0L

    /** The shared dead-report policy: recover-then-retry once, dedupe per account per window. */
    internal val deadReports = PoolDeadReporter(nowMs)

    /** "service:id" to the time the account is worth trying first again. */
    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val reportedAt = ConcurrentHashMap<String, Long>()

    /** The Playback Pool switch, on by default as on Android, and only while a deployment is configured. */
    fun enabled(): Boolean = configured && (preferences()?.get(UsePoolAccountsKey) ?: true)

    /** Loads the cached accounts and runs one throttled refresh in the background, as Android does at startup. */
    fun start() {
        scope.launch { refresh(force = false) }
    }

    /**
     * The pooled TIDAL accounts, recently working ones first and premium before free within each group. Pooled access
     * tokens live about an hour while the cache lives far longer, so an expired token triggers one forced re-lease
     * before the list is returned, instead of a sure 401 that would report a healthy account dead.
     */
    suspend fun tidalAccounts(): List<TidalPoolAccount> {
        if (!enabled()) return emptyList()
        ensureLoaded()
        if (tidal.any { accessTokenExpired(it.token, nowMs()) }) refresh(force = true)
        if (!enabled()) return emptyList()
        return tidal.sortedWith(compareBy({ coolingDown(TIDAL, it.id) }, { !it.premium }))
    }

    suspend fun deezerAccounts(): List<DeezerPoolAccount> {
        if (!enabled()) return emptyList()
        ensureLoaded()
        return deezer.sortedWith(compareBy({ coolingDown(DEEZER, it.id) }, { !it.premium }))
    }

    suspend fun qobuzAccounts(): List<QobuzPoolAccount> {
        if (!enabled()) return emptyList()
        ensureLoaded()
        return qobuz.sortedWith(compareBy({ coolingDown(QOBUZ, it.id) }, { !it.premium }))
    }

    /** The credential the feed last served, with no load or refresh — the marker a recovery compares. */
    fun tidalSnapshot(): List<TidalPoolAccount> = tidal

    fun deezerSnapshot(): List<DeezerPoolAccount> = deezer

    fun qobuzSnapshot(): List<QobuzPoolAccount> = qobuz

    /** A replaced feed credential must revoke an already-prepared source, not only the next pool lookup. */
    fun admitsQobuz(account: QobuzPoolAccount): Boolean = enabled() && qobuz.any {
        it.id == account.id && it.token == account.token && it.appId == account.appId && it.appSecret == account.appSecret
    }

    /**
     * Community HiFi instances from the pool's discovery feed, normalized and without dead `.squid.wtf` hosts. A fetch is
     * reused for [DISCOVERY_INTERVAL_MS]; a failed one answers the previous list.
     */
    suspend fun tidalInstances(): List<String> {
        if (!enabled()) return emptyList()
        if (baseUrl == null) return emptyList()
        if (discoveredAtMs > 0L && nowMs() - discoveredAtMs < DISCOVERY_INTERVAL_MS) return discovered
        discoveredAtMs = nowMs()
        val body = fetch("/api/discovery/tidal")
        if (body == null || body.first != 200 || !enabled()) return discovered
        discovered = parseDiscoveredInstances(body.second).mapNotNull(::normalizeInstanceUrl).distinct()
        return discovered
    }

    /**
     * The deployment base and read key that the Deezer and Qobuz instance resolvers discover through. Both are empty
     * while the switch is off, so their discovery stops with it.
     */
    fun discoveryBaseUrl(): String = if (enabled()) baseUrl.orEmpty() else ""

    fun discoveryReadKey(): String = if (enabled()) readKey else ""

    fun noteFailure(service: String, id: Long?) {
        if (id == null || id <= 0L) return
        cooldownUntil["$service:$id"] = nowMs() + ACCOUNT_COOLDOWN_MS
    }

    fun noteSuccess(service: String, id: Long?) {
        if (id == null || id <= 0L) return
        cooldownUntil.remove("$service:$id")
    }

    /**
     * Reports an account that failed for real playback (`dead` or `not_premium`), at most once per account and type in
     * [REPORT_DEDUPE_MS]. A report never blocks a resolver. When the pool answers with a replacement account, the dead
     * entry is replaced in the cache; an empty replacement drops it.
     */
    fun report(service: String, id: Long?, type: String) {
        if (id == null || id <= 0L || !enabled()) return
        if (baseUrl == null) return
        val key = "$service:$id:$type"
        val now = nowMs()
        val last = reportedAt[key]
        if (last != null && now - last < REPORT_DEDUPE_MS) return
        reportedAt[key] = now
        reportedAt.entries.removeIf { now - it.value > REPORT_DEDUPE_MS }
        scope.launch {
            try {
                val body = buildJsonObject {
                    put("service", service)
                    put("kind", "account")
                    put("id", id)
                    put("report", type)
                }.toString()
                val response = ArchivePoolEndpoints.acrossEndpoints(baseUrl, report = true) { base ->
                    try {
                        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                            val reply =
                                http.post("$base/api/report") {
                                    poolHeaders()
                                    contentType(ContentType.Application.Json)
                                    setBody(body)
                                }
                            reply.status.value to if (reply.status.value in 200..299) reply.bodyAsText() else ""
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                } ?: return@launch
                if (response.first !in 200..299) return@launch
                val root = parseObject(response.second) ?: return@launch
                if ((root["ok"] as? JsonPrimitive)?.booleanOrNull == true && enabled()) mergeReplacement(root, service, id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log("Playback Pool report failed: ${failure.javaClass.simpleName}")
            }
        }
    }

    /** Mirrors a change of the Playback Pool switch. Turning it off clears the cached accounts but keeps the throttle. */
    suspend fun onEnabledChanged(enabled: Boolean) {
        if (enabled) {
            refresh(force = false)
            return
        }
        mutex.withLock {
            tidal = emptyList()
            deezer = emptyList()
            qobuz = emptyList()
            discovered = emptyList()
            discoveredAtMs = 0L
            loaded = true
            persist()
        }
    }

    /**
     * Fetches the account feed unless the last attempt is recent, keeping the previous cache when the feed answers with
     * every list empty. Answers true when accounts are cached afterwards. Never throws.
     */
    suspend fun refresh(force: Boolean): Boolean {
        if (!enabled()) return false
        if (baseUrl == null) return false
        ensureLoaded()
        return mutex.withLock {
            if (!enabled()) return@withLock false
            if (!shouldRefresh(nowMs(), lastAttemptAtMs, refreshIntervalMs(), force)) return@withLock hasAccounts()
            lastAttemptAtMs = nowMs()
            var result = fetch("/api/accounts")
            if (result?.first == 404) result = fetch("/api/sources")
            val root = result?.takeIf { it.first == 200 }?.second?.let(::parseObject)
            when {
                root != null && enabled() -> {
                    val decrypt = decryptorFor(root)
                    val nextTidal = parseTidal(accountsArray(root, TIDAL), decrypt)
                    val nextDeezer = parseDeezer(accountsArray(root, DEEZER), decrypt)
                    val nextQobuz = parseQobuz(accountsArray(root, QOBUZ), decrypt)
                    if (nextTidal.isEmpty() && nextDeezer.isEmpty() && nextQobuz.isEmpty() && hasAccounts()) {
                        log("Playback Pool answered empty lists; the cached accounts are kept")
                    } else {
                        tidal = nextTidal
                        deezer = nextDeezer
                        qobuz = nextQobuz
                    }
                }
                result == null -> log("Playback Pool could not be reached")
                result.first == 401 -> log("Playback Pool rejected the read key (HTTP 401)")
                result.first == 404 -> log("Playback Pool: no pool API at the configured deployment (HTTP 404)")
                else -> log("Playback Pool feed returned HTTP ${result.first}")
            }
            persist()
            hasAccounts()
        }
    }

    private fun hasAccounts() = tidal.isNotEmpty() || deezer.isNotEmpty() || qobuz.isNotEmpty()

    /** Android re-reads a pool that serves every service daily; one that is missing a service is retried sooner. */
    private fun refreshIntervalMs(): Long =
        if (tidal.isNotEmpty() && deezer.isNotEmpty() && qobuz.isNotEmpty()) FULL_INTERVAL_MS else PARTIAL_INTERVAL_MS

    private fun coolingDown(service: String, id: Long?): Boolean {
        val key = "$service:${id ?: 0L}"
        val until = cooldownUntil[key] ?: return false
        if (until > nowMs()) return true
        cooldownUntil.remove(key, until)
        return false
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return@withLock
            try {
                vault.read(CACHE_KEY)?.let(::parseObject)?.let { cached ->
                    val passthrough: (String) -> String? = { it }
                    lastAttemptAtMs = (cached["attemptAt"] as? JsonPrimitive)?.longOrNull ?: 0L
                    if (enabled()) {
                        tidal = parseTidal(cached[TIDAL] as? JsonArray, passthrough)
                        deezer = parseDeezer(cached[DEEZER] as? JsonArray, passthrough)
                        qobuz = parseQobuz(cached[QOBUZ] as? JsonArray, passthrough)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log("Playback Pool cache was not read: ${failure.javaClass.simpleName}")
            }
            loaded = true
        }
    }

    /** Called with [mutex] held. A vault failure leaves the in-memory cache in place for this session. */
    private suspend fun persist() {
        val document = buildJsonObject {
            put("attemptAt", lastAttemptAtMs)
            put(TIDAL, buildJsonArray {
                tidal.forEach { account ->
                    add(buildJsonObject {
                        account.id?.let { put("id", it) }
                        put("token", account.token)
                        account.countryCode?.let { put("countryCode", it) }
                        put("premium", account.premium)
                    })
                }
            })
            put(DEEZER, buildJsonArray {
                deezer.forEach { account ->
                    add(buildJsonObject {
                        account.id?.let { put("id", it) }
                        put("arl", account.arl)
                        put("premium", account.premium)
                    })
                }
            })
            put(QOBUZ, buildJsonArray {
                qobuz.forEach { account ->
                    add(buildJsonObject {
                        account.id?.let { put("id", it) }
                        put("token", account.token)
                        put("appId", account.appId)
                        put("appSecret", account.appSecret)
                        put("premium", account.premium)
                    })
                }
            })
        }
        try {
            vault.write(CACHE_KEY, document.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log("Playback Pool cache was not saved: ${failure.javaClass.simpleName}")
        }
    }

    private suspend fun mergeReplacement(root: JsonObject, service: String, deadId: Long) {
        val replacement = root["replacement"] as? JsonObject ?: return
        val decrypt = decryptorFor(replacement)
        val entries = (replacement[service] as? JsonObject)?.get("accounts") as? JsonArray ?: JsonArray(emptyList())
        mutex.withLock {
            if (!enabled()) return@withLock
            when (service) {
                TIDAL -> tidal = merged(tidal, deadId, { it.id }, parseTidal(entries, decrypt).firstOrNull()) ?: return@withLock
                DEEZER -> deezer = merged(deezer, deadId, { it.id }, parseDeezer(entries, decrypt).firstOrNull()) ?: return@withLock
                QOBUZ -> qobuz = merged(qobuz, deadId, { it.id }, parseQobuz(entries, decrypt).firstOrNull()) ?: return@withLock
                else -> return@withLock
            }
            persist()
        }
    }

    private suspend fun fetch(path: String): Pair<Int, String>? =
        ArchivePoolEndpoints.acrossEndpoints(baseUrl) { base ->
            try {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    val response = http.get("$base$path") { poolHeaders() }
                    response.status.value to if (response.status.value == 200) response.bodyAsText() else ""
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    private fun io.ktor.client.request.HttpRequestBuilder.poolHeaders() {
        header("User-Agent", "ArchiveTune-Desktop")
        // v2 of the feed protocol: sensitive fields are encrypted with a key derived from the read key.
        header("X-Pool-Client", "v2")
        if (readKey.isNotBlank()) header("Authorization", "Bearer $readKey")
    }

    private fun decryptorFor(root: JsonObject): (String) -> String? {
        val readKeyScheme = (root["encryption"] as? JsonPrimitive)?.contentOrNull == "read-key"
        return { raw ->
            if (!raw.startsWith(ENCRYPTED_PREFIX)) {
                raw
            } else if (readKeyScheme) {
                decrypt(raw, derivedKey) ?: decrypt(raw, legacyKey)
            } else {
                decrypt(raw, legacyKey) ?: decrypt(raw, derivedKey)
            }
        }
    }

    private fun parseTidal(array: JsonArray?, decrypt: (String) -> String?): List<TidalPoolAccount> =
        array.orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val token = field(entry, "token", decrypt) ?: return@mapNotNull null
            TidalPoolAccount(entryId(entry), token, field(entry, "countryCode", decrypt), flag(entry, "premium", false))
        }

    private fun parseDeezer(array: JsonArray?, decrypt: (String) -> String?): List<DeezerPoolAccount> =
        array.orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val arl = field(entry, "arl", decrypt)?.takeIf { value -> value.none { it in "\r\n;" } } ?: return@mapNotNull null
            DeezerPoolAccount(entryId(entry), arl, flag(entry, "premium", false))
        }

    private fun parseQobuz(array: JsonArray?, decrypt: (String) -> String?): List<QobuzPoolAccount> =
        array.orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val token = field(entry, "token", decrypt) ?: return@mapNotNull null
            val appId = field(entry, "appId", decrypt) ?: return@mapNotNull null
            val appSecret = field(entry, "appSecret", decrypt) ?: return@mapNotNull null
            QobuzPoolAccount(entryId(entry), token, appId, appSecret, flag(entry, "premium", false))
        }

    private fun field(entry: JsonObject, name: String, decrypt: (String) -> String?): String? {
        val raw = (entry[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        return decrypt(raw)?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val TIDAL = "tidal"
        const val DEEZER = "deezer"
        const val QOBUZ = "qobuz"
        private val CACHE_KEY = CredentialKey("archivepool", CredentialScope.PLAYBACK_POOL, "feed-cache-v1")
        private const val ENCRYPTED_PREFIX = "enc:1:"
        private const val CLIENT_KEY_DOMAIN = "archivepool-client:"
        private const val FULL_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private const val PARTIAL_INTERVAL_MS = 5L * 60L * 60L * 1000L
        private const val DISCOVERY_INTERVAL_MS = 30L * 60L * 1000L
        private const val ACCOUNT_COOLDOWN_MS = 10L * 60L * 1000L
        private const val REPORT_DEDUPE_MS = PoolDeadReportPolicy.REPORT_DEDUPE_MS
        private const val REQUEST_TIMEOUT_MS = 10_000L
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        internal fun shouldRefresh(nowMs: Long, lastAttemptAtMs: Long, intervalMs: Long, force: Boolean): Boolean {
            if (force || lastAttemptAtMs <= 0L) return true
            return nowMs >= lastAttemptAtMs && nowMs - lastAttemptAtMs >= intervalMs
        }

        /** SHA-256 over the domain-separated read key, byte for byte what the pool server derives. */
        internal fun deriveClientKey(readKey: String): SecretKeySpec? {
            val trimmed = readKey.trim().takeIf { it.isNotEmpty() } ?: return null
            val bytes = MessageDigest.getInstance("SHA-256").digest((CLIENT_KEY_DOMAIN + trimmed).toByteArray(Charsets.UTF_8))
            return SecretKeySpec(bytes, "AES")
        }

        /** Decrypts one `enc:1:<iv>:<ciphertext+tag>` blob with AES-256-GCM, or null when the key or the blob is wrong. */
        internal fun decrypt(blob: String, key: SecretKeySpec?): String? {
            if (key == null || !blob.startsWith(ENCRYPTED_PREFIX)) return null
            return runCatching {
                val parts = blob.removePrefix(ENCRYPTED_PREFIX).split(":")
                if (parts.size != 2) return null
                val decoder = Base64.getMimeDecoder()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decoder.decode(parts[0])))
                String(cipher.doFinal(decoder.decode(parts[1])), Charsets.UTF_8)
            }.getOrNull()
        }

        /** True when [token] is a JWT whose `exp` has passed. A token that is not a JWT is left to the server to judge. */
        internal fun accessTokenExpired(token: String, nowMs: Long): Boolean {
            val payload = token.split('.').getOrNull(1) ?: return false
            val exp = runCatching {
                val text = String(Base64.getUrlDecoder().decode(payload.trimEnd('=')), Charsets.UTF_8)
                ((json.parseToJsonElement(text) as? JsonObject)?.get("exp") as? JsonPrimitive)?.longOrNull
            }.getOrNull() ?: return false
            return exp * 1000L <= nowMs
        }

        /**
         * A discovery payload: an object of URL arrays (`streaming`, `instances`, then `api`; `qobuz` is another backend),
         * a plain array of URLs or objects with `url`/`host`, or whitespace-separated URLs.
         */
        internal fun parseDiscoveredInstances(body: String): List<String> {
            val trimmed = body.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
            val element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
            when (element) {
                is JsonObject -> {
                    val urls = listOf("streaming", "instances", "api").flatMap { key -> urlsOf(element[key] as? JsonArray) }
                        .filterNot { it.contains(".squid.wtf", ignoreCase = true) }
                    if (urls.isNotEmpty()) return urls
                }
                is JsonArray -> urlsOf(element).takeIf { it.isNotEmpty() }?.let { return it }
                else -> Unit
            }
            return trimmed.split('\n', '\r', ' ', ',').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        }

        private fun urlsOf(array: JsonArray?): List<String> = array.orEmpty().mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }
                is JsonObject -> listOf("url", "host").firstNotNullOfOrNull { key ->
                    (item[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                }
                else -> null
            }
        }

        /** `scheme://host[:port]` for an https or http URL with a dotted host, or null. A bare host takes https. */
        internal fun normalizeInstanceUrl(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/').takeIf { it.isNotEmpty() } ?: return null
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
            val uri = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
            val host = uri.host?.takeIf { it.contains('.') } ?: return null
            return "${uri.scheme}://$host${if (uri.port > 0) ":${uri.port}" else ""}"
        }

        private fun parseObject(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

        private fun accountsArray(root: JsonObject, service: String): JsonArray? =
            (root[service] as? JsonObject)?.get("accounts") as? JsonArray ?: root[service] as? JsonArray

        private fun entryId(entry: JsonObject): Long? = (entry["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }

        private fun flag(entry: JsonObject, name: String, default: Boolean): Boolean =
            (entry[name] as? JsonPrimitive)?.booleanOrNull ?: default

        private fun <T> merged(list: List<T>, deadId: Long, idOf: (T) -> Long?, replacement: T?): List<T>? {
            val index = list.indexOfFirst { idOf(it) == deadId }.takeIf { it >= 0 } ?: return null
            return when {
                replacement == null || list.any { idOf(it) == idOf(replacement) } -> list.filterIndexed { i, _ -> i != index }
                else -> list.mapIndexed { i, entry -> if (i == index) replacement else entry }
            }
        }

        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    }
}

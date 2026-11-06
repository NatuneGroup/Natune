@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
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
import moe.rukamori.archivetune.shared.data.provider.ArchivePoolEndpoints
import moe.rukamori.archivetune.shared.data.provider.PoolResponse
import moe.rukamori.archivetune.shared.data.stream.aes.Aes256Gcm
import moe.rukamori.archivetune.shared.domain.lyrics.ai.Sha256
import moe.rukamori.archivetune.shared.domain.platform.CredentialKey
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.platform.SecureCredentialStore
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.UsePoolAccountsKey
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.create

/** One shared TIDAL subscriber session from the pool feed. [id] is the feed id a report names; manual entries have none. */
internal class TidalPoolAccount(val id: Long?, val token: String, val countryCode: String?, val premium: Boolean) {
    override fun toString() = "TidalPoolAccount(id=$id, REDACTED)"
}

/** One shared Deezer ARL from the pool feed. [premium] false means the account can serve MP3 but not FLAC. */
internal class DeezerPoolAccount(val id: Long?, val arl: String, val premium: Boolean) {
    override fun toString() = "DeezerPoolAccount(id=$id, REDACTED)"
}

/**
 * The iOS client of the Playback Pool, over the same ArchivePool contract as Android's PoolAccountManager: the account
 * feed at `/api/accounts` (the combined `/api/sources` when a deployment predates the split), v2 field encryption keyed
 * by the read key, `/api/discovery/tidal` for community HiFi instances and `/api/report` for accounts that fail for real
 * playback. The Playback Pool switch gates every request and every getter, so with it off the sources fall through to
 * the user's own accounts.
 *
 * The decrypted accounts are cached in the Keychain vault under their own key, never in the settings file and never
 * over a personal sign-in. The time of the last attempt is cached with them, so an empty or failed feed stays throttled
 * across restarts. Only the TIDAL and Deezer lists are kept: the other pooled services have no source on this host.
 */
internal class IosPlaybackPool(
    private val http: HttpClient,
    private val vault: SecureCredentialStore,
    private val preferences: () -> PreferenceSnapshot?,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val baseUrl: String? = iosPoolBaseUrl(),
    private val readKey: String = iosPoolValue("ArchiveTuneSourceProviderKey"),
    legacyClientKey: String = iosPoolValue("ArchiveTunePoolClientKey"),
    private val nowMs: () -> Long,
) {
    /** True when this bundle names a pool deployment, so the Playback Pool rows can be used. */
    val configured: Boolean get() = baseUrl != null

    private val derivedKey: ByteArray? = deriveClientKey(readKey)
    private val legacyKey: ByteArray? = iosBase64Decode(legacyClientKey.trim())?.takeIf { it.size == 32 }

    private val mutex = Mutex()
    private var loaded = false
    private var tidal: List<TidalPoolAccount> = emptyList()
    private var deezer: List<DeezerPoolAccount> = emptyList()
    private var lastAttemptAtMs = 0L
    private var discovered: List<String> = emptyList()
    private var discoveredAtMs = 0L

    /** "service:id" to the time the account is worth trying first again, guarded by its own mutex. */
    private val cooldownMutex = Mutex()
    private val cooldownUntil = HashMap<String, Long>()
    private val reportedAt = HashMap<String, Long>()

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
        if (mutex.withLock { tidal.any { accessTokenExpired(it.token, nowMs()) } }) refresh(force = true)
        if (!enabled()) return emptyList()
        val accounts = mutex.withLock { tidal }
        val cold = HashMap<Long?, Boolean>()
        accounts.forEach { cold[it.id] = coolingDown(TIDAL, it.id) }
        return accounts.sortedWith(compareBy({ cold[it.id] == true }, { !it.premium }))
    }

    suspend fun deezerAccounts(): List<DeezerPoolAccount> {
        if (!enabled()) return emptyList()
        ensureLoaded()
        val accounts = mutex.withLock { deezer }
        val cold = HashMap<Long?, Boolean>()
        accounts.forEach { cold[it.id] = coolingDown(DEEZER, it.id) }
        return accounts.sortedWith(compareBy({ cold[it.id] == true }, { !it.premium }))
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
        if (body == null || body.status != 200 || !enabled()) return discovered
        discovered = parseDiscoveredInstances(body.body).mapNotNull(::normalizeInstanceUrl).distinct()
        return discovered
    }

    suspend fun noteFailure(service: String, id: Long?) {
        if (id == null || id <= 0L) return
        cooldownMutex.withLock { cooldownUntil["$service:$id"] = nowMs() + ACCOUNT_COOLDOWN_MS }
    }

    suspend fun noteSuccess(service: String, id: Long?) {
        if (id == null || id <= 0L) return
        cooldownMutex.withLock { cooldownUntil.remove("$service:$id") }
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
        scope.launch {
            val now = nowMs()
            val accepted = cooldownMutex.withLock {
                val last = reportedAt[key]
                if (last != null && now - last < REPORT_DEDUPE_MS) return@withLock false
                reportedAt[key] = now
                reportedAt.entries.retainAll { now - it.value <= REPORT_DEDUPE_MS }
                true
            }
            if (!accepted) return@launch
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
                            PoolResponse(
                                status = reply.status.value,
                                body = if (reply.status.value in 200..299) reply.bodyAsText() else "",
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                } ?: return@launch
                if (response.status !in 200..299) return@launch
                val root = parseObject(response.body) ?: return@launch
                if ((root["ok"] as? JsonPrimitive)?.booleanOrNull == true && enabled()) mergeReplacement(root, service, id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log("Playback Pool report failed: ${failure::class.simpleName}")
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
            if (result?.status == 404) result = fetch("/api/sources")
            val root = result?.takeIf { it.status == 200 }?.body?.let(::parseObject)
            when {
                root != null && enabled() -> {
                    val decrypt = decryptorFor(root)
                    val nextTidal = parseTidal(accountsArray(root, TIDAL), decrypt)
                    val nextDeezer = parseDeezer(accountsArray(root, DEEZER), decrypt)
                    if (nextTidal.isEmpty() && nextDeezer.isEmpty() && hasAccounts()) {
                        log("Playback Pool answered empty lists; the cached accounts are kept")
                    } else {
                        tidal = nextTidal
                        deezer = nextDeezer
                    }
                }
                result == null -> log("Playback Pool could not be reached")
                result.status == 304 -> Unit
                result.status == 401 || result.status == 403 -> log("Playback Pool rejected the read key (HTTP ${result.status})")
                result.status == 429 || result.status == 503 -> log("Playback Pool refused the feed (HTTP ${result.status})")
                result.status == 404 -> log("Playback Pool: no pool API at the configured deployment (HTTP 404)")
                else -> log("Playback Pool feed returned HTTP ${result.status}")
            }
            persist()
            hasAccounts()
        }
    }

    private fun hasAccounts() = tidal.isNotEmpty() || deezer.isNotEmpty()

    /** Android re-reads a pool that serves every service daily; one that is missing a service is retried sooner. */
    private fun refreshIntervalMs(): Long = if (tidal.isNotEmpty() && deezer.isNotEmpty()) FULL_INTERVAL_MS else PARTIAL_INTERVAL_MS

    private suspend fun coolingDown(service: String, id: Long?): Boolean {
        val key = "$service:${id ?: 0L}"
        return cooldownMutex.withLock {
            val until = cooldownUntil[key] ?: return@withLock false
            if (until > nowMs()) return@withLock true
            cooldownUntil.remove(key)
            false
        }
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
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log("Playback Pool cache was not read: ${failure::class.simpleName}")
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
        }
        try {
            vault.write(CACHE_KEY, document.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log("Playback Pool cache was not saved: ${failure::class.simpleName}")
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
                else -> return@withLock
            }
            persist()
        }
    }

    private suspend fun fetch(path: String): PoolResponse? =
        ArchivePoolEndpoints.acrossEndpoints(baseUrl) { base ->
            try {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    val response = http.get("$base$path") { poolHeaders() }
                    PoolResponse(
                        status = response.status.value,
                        body = if (response.status.value == 200) response.bodyAsText() else "",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    private fun io.ktor.client.request.HttpRequestBuilder.poolHeaders() {
        header("User-Agent", "ArchiveTune-iOS")
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

    private fun field(entry: JsonObject, name: String, decrypt: (String) -> String?): String? {
        val raw = (entry[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        return decrypt(raw)?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val TIDAL = "tidal"
        const val DEEZER = "deezer"
        private val CACHE_KEY = CredentialKey("archivepool", CredentialScope.PLAYBACK_POOL, "feed-cache-v1")
        private const val ENCRYPTED_PREFIX = "enc:1:"
        private const val CLIENT_KEY_DOMAIN = "archivepool-client:"
        private const val FULL_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private const val PARTIAL_INTERVAL_MS = 5L * 60L * 60L * 1000L
        private const val DISCOVERY_INTERVAL_MS = 30L * 60L * 1000L
        private const val ACCOUNT_COOLDOWN_MS = 10L * 60L * 1000L
        private const val REPORT_DEDUPE_MS = 10L * 60L * 1000L
        private const val REQUEST_TIMEOUT_MS = 10_000L
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        internal fun shouldRefresh(nowMs: Long, lastAttemptAtMs: Long, intervalMs: Long, force: Boolean): Boolean {
            if (force || lastAttemptAtMs <= 0L) return true
            return nowMs >= lastAttemptAtMs && nowMs - lastAttemptAtMs >= intervalMs
        }

        /** SHA-256 over the domain-separated read key, byte for byte what the pool server derives. */
        internal fun deriveClientKey(readKey: String): ByteArray? {
            val trimmed = readKey.trim().takeIf { it.isNotEmpty() } ?: return null
            return Sha256.digest((CLIENT_KEY_DOMAIN + trimmed).encodeToByteArray())
        }

        /** Decrypts one `enc:1:<iv>:<ciphertext+tag>` blob with AES-256-GCM, or null when the key or the blob is wrong. */
        internal fun decrypt(blob: String, key: ByteArray?): String? {
            if (key == null || !blob.startsWith(ENCRYPTED_PREFIX)) return null
            val parts = blob.removePrefix(ENCRYPTED_PREFIX).split(":")
            if (parts.size != 2) return null
            val iv = iosBase64Decode(parts[0]) ?: return null
            val ciphertext = iosBase64Decode(parts[1]) ?: return null
            return Aes256Gcm.decrypt(key, iv, ciphertext)?.decodeToString()
        }

        /** True when [token] is a JWT whose `exp` has passed. A token that is not a JWT is left to the server to judge. */
        internal fun accessTokenExpired(token: String, nowMs: Long): Boolean {
            val payload = token.split('.').getOrNull(1) ?: return false
            val decoded = iosBase64Decode(payload) ?: return false
            val expiry = ((json.parseToJsonElement(decoded.decodeToString()) as? JsonObject)
                ?.get("exp") as? JsonPrimitive)?.longOrNull ?: return false
            return expiry * 1000L <= nowMs
        }

        internal fun parseObject(text: String): JsonObject? =
            try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }

        private fun accountsArray(root: JsonObject, service: String): JsonArray? =
            ((root[service] as? JsonObject)?.get("accounts") as? JsonArray)
                ?: (root["accounts"] as? JsonObject)?.let { (it[service] as? JsonObject)?.get("accounts") as? JsonArray }

        private fun entryId(entry: JsonObject): Long? =
            (entry["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }

        private fun flag(entry: JsonObject, name: String, fallback: Boolean): Boolean =
            (entry[name] as? JsonPrimitive)?.booleanOrNull ?: fallback

        /** The instances feed answers a bare list or `{ "instances": [...] }`. */
        internal fun parseDiscoveredInstances(text: String): List<String> {
            val root = try { json.parseToJsonElement(text) } catch (_: Exception) { return emptyList() }
            val array = (root as? JsonArray) ?: ((root as? JsonObject)?.get("instances") as? JsonArray) ?: return emptyList()
            return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }

        /** A dead `.squid.wtf` host is dropped; every other base is normalized to its HTTPS origin without a slash. */
        internal fun normalizeInstanceUrl(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty() || trimmed.contains(".squid.wtf")) return null
            val withScheme = if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
            return withScheme.takeIf { it.startsWith("https://") }
        }

        private fun <T> merged(current: List<T>, deadId: Long, id: (T) -> Long?, replacement: T?): List<T>? {
            val filtered = current.filter { id(it) != deadId }
            return when {
                replacement != null -> filtered + replacement
                filtered.size != current.size -> filtered
                else -> null
            }
        }
    }
}

/** The ArchivePool deployment of the Playback Pool, without a trailing slash, or null when the feed is turned off. */
private fun iosPoolBaseUrl(): String? {
    val configured =
        iosPoolValue("ArchiveTuneArchivePoolBaseUrl").ifBlank { iosPoolValue("ArchiveTuneSourceProviderUrl") }
    if (configured.equals("off", ignoreCase = true)) return null
    return configured.ifBlank { DEFAULT_POOL_BASE_URL }.trimEnd('/').takeIf { it.startsWith("https://") }
}

private const val DEFAULT_POOL_BASE_URL = ArchivePoolEndpoints.FALLBACK_BASE_URL

private fun iosPoolValue(infoPlistKey: String): String =
    (NSBundle.mainBundle.objectForInfoDictionaryKey(infoPlistKey) as? String)?.trim().orEmpty()

/** Base64 decode via NSData; answers null on input the strict decoder refuses. */
private fun iosBase64Decode(encoded: String): ByteArray? {
    val data = NSData.create(
        base64EncodedString = encoded
            .replace("\n", "").replace("\r", "")
            .let { value -> value + "=".repeat((4 - value.length % 4) % 4) },
        options = 0u,
    ) ?: return null
    val length = data.length.toInt()
    val out = ByteArray(length)
    data.bytes?.let { pointer ->
        out.usePinned { pinned -> platform.posix.memcpy(pinned.addressOf(0), pointer, length.toULong()) }
    }
    return out
}

package moe.rukamori.archivetune.shared.data.provider

import kotlin.concurrent.Volatile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.shared.domain.platform.CredentialKey
import moe.rukamori.archivetune.shared.domain.platform.SecureCredentialStore

/**
 * Whether the host is in front of the user. Discovery polling stops while it is not: a pool feed
 * is not worth a request from a backgrounded app, and the persisted instance list keeps serving
 * playback until the next foreground session. Hosts set this from their lifecycle; the default is
 * foreground so a host without one keeps working.
 */
object PoolPolling {
    @Volatile
    var foreground: Boolean = true
}

/**
 * The discovery feed state one host keeps between launches: the instance URLs it last accepted,
 * when it last asked the pool, the feed's `ETag` so the next read can revalidate instead of
 * re-downloading, and the quiet period a refusal earned. Persisting this is what removes the
 * per-launch fan-out: a relaunch inside the TTL answers from disk without a request.
 */
class PoolDiscoveryState(
    val instances: List<String> = emptyList(),
    val fetchedAtMs: Long = 0L,
    val etag: String? = null,
    val blockedUntilMs: Long = 0L,
    val refusalStatus: Int = 0,
    val refusalWindowMs: Long = 0L,
)

/**
 * The cadence and backoff every discovery feed shares. The TTL is six hours with ±20% jitter —
 * twelve times fewer polls than the old thirty minutes, and installs do not line up. A `401`/`403`
 * buys six hours (a bad key is configuration, not a blip) and doubles toward a day; `429`/`503`
 * honour the server's `Retry-After` verbatim; a transport failure buys a short flat pause so an
 * offline device does not re-ask on every resolve.
 */
object PoolDiscoveryPolicy {
    /** Base re-ask interval for one discovery feed. */
    const val TTL_MS = 6L * 60L * 60L * 1000L

    /** The first quiet period a refused read key earns. */
    const val UNAUTHORIZED_WINDOW_MS = 6L * 60L * 60L * 1000L

    /** The quiet period a throttled/unavailable feed earns when it sent no `Retry-After`. */
    const val TRANSIENT_WINDOW_MS = 5L * 60L * 1000L

    /** A transport failure's pause: long enough not to re-ask per resolve, short enough to recover. */
    const val TRANSPORT_WINDOW_MS = 2L * 60L * 1000L

    /** No refusal buys more than a day of silence. */
    const val MAX_WINDOW_MS = 24L * 60L * 60L * 1000L

    private const val MIN_WINDOW_MS = 30L * 1000L
    private const val JITTER_PERCENT = 20L

    /** The re-ask interval for a state last fetched at [seed], jittered ±20%. */
    fun ttlMs(seed: Long = 0L): Long = jittered(TTL_MS, seed)

    /** True when this feed should be asked again now and the host is allowed to ask at all. */
    fun shouldFetch(state: PoolDiscoveryState, nowMs: Long, foreground: Boolean, seed: Long = state.fetchedAtMs): Boolean {
        if (!foreground) return false
        if (state.blockedUntilMs > nowMs) return false
        if (state.fetchedAtMs <= 0L) return true
        // A clock that moved backwards cannot prove the TTL elapsed; asking once is the safe read.
        if (nowMs < state.fetchedAtMs) return true
        return nowMs - state.fetchedAtMs >= ttlMs(seed)
    }

    /** A 2xx answer: the parsed instances (or the previous ones when the payload was empty) and its ETag. */
    fun nextAfterSuccess(
        state: PoolDiscoveryState,
        nowMs: Long,
        instances: List<String>,
        etag: String?,
    ): PoolDiscoveryState =
        PoolDiscoveryState(
            instances = instances,
            fetchedAtMs = nowMs,
            etag = etag ?: state.etag,
            blockedUntilMs = 0L,
            refusalStatus = 0,
            refusalWindowMs = 0L,
        )

    /** A 304 answer: the stored instances and ETag are current, so only the fetch time moves. */
    fun nextAfterNotModified(state: PoolDiscoveryState, nowMs: Long): PoolDiscoveryState =
        PoolDiscoveryState(state.instances, nowMs, state.etag)

    /** A `401`/`403`/`429`/`503` answer: keep serving the stored list and stay quiet for the window. */
    fun nextAfterRefusal(
        state: PoolDiscoveryState,
        nowMs: Long,
        status: Int,
        retryAfterMs: Long?,
    ): PoolDiscoveryState {
        val unauthorized = status == 401 || status == 403
        val repeated = state.refusalStatus == status && state.refusalWindowMs > 0L
        val window =
            when {
                unauthorized && repeated -> state.refusalWindowMs * 2
                unauthorized -> UNAUTHORIZED_WINDOW_MS
                retryAfterMs != null -> retryAfterMs
                repeated -> state.refusalWindowMs * 2
                else -> TRANSIENT_WINDOW_MS
            }.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
        // The server's own hint is honoured exactly; only windows this client chooses are jittered,
        // and no refusal may buy more than MAX_WINDOW_MS of silence.
        val pause =
            (if (!unauthorized && retryAfterMs != null) window else jittered(window, nowMs))
                .coerceAtMost(MAX_WINDOW_MS)
        return PoolDiscoveryState(
            instances = state.instances,
            fetchedAtMs = state.fetchedAtMs,
            etag = state.etag,
            blockedUntilMs = nowMs + pause,
            refusalStatus = status,
            refusalWindowMs = window,
        )
    }

    /** A transport failure (or every deployment failing): a short pause, not a counted refusal. */
    fun nextAfterTransportFailure(state: PoolDiscoveryState, nowMs: Long): PoolDiscoveryState =
        PoolDiscoveryState(state.instances, state.fetchedAtMs, state.etag, nowMs + jittered(TRANSPORT_WINDOW_MS, nowMs))

    /** [baseMs] moved by up to ±20%, deterministically from [seed]. */
    fun jittered(baseMs: Long, seed: Long): Long {
        val spread = baseMs / 100 * JITTER_PERCENT
        if (spread <= 0L) return baseMs
        val offset = ((seed xor (seed ushr 13)) and 0xFFFF) % (2 * spread + 1) - spread
        return (baseMs + offset).coerceAtLeast(1L)
    }
}

/** JSON codec for the host's persisted discovery state. */
object PoolDiscoveryStateCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encode(state: PoolDiscoveryState): String =
        buildJsonObject {
            put("fetchedAt", state.fetchedAtMs)
            state.etag?.let { put("etag", it) }
            put("blockedUntil", state.blockedUntilMs)
            put("refusalStatus", state.refusalStatus)
            put("refusalWindowMs", state.refusalWindowMs)
            put("instances", buildJsonArray { state.instances.forEach { add(JsonPrimitive(it)) } })
        }.toString()

    fun decode(raw: String?): PoolDiscoveryState? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return PoolDiscoveryState(
            instances = (root["instances"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            fetchedAtMs = (root["fetchedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
            etag = (root["etag"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            blockedUntilMs = (root["blockedUntil"] as? JsonPrimitive)?.longOrNull ?: 0L,
            refusalStatus = (root["refusalStatus"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
            refusalWindowMs = (root["refusalWindowMs"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    }
}

/** Where a host keeps its discovery state between launches. */
interface PoolDiscoveryStore {
    suspend fun load(): PoolDiscoveryState?

    suspend fun save(state: PoolDiscoveryState)
}

/** In-memory store for hosts with no durable home for the state (and for tests). */
class MemoryPoolDiscoveryStore(initial: PoolDiscoveryState? = null) : PoolDiscoveryStore {
    @Volatile
    private var state: PoolDiscoveryState? = initial

    override suspend fun load(): PoolDiscoveryState? = state

    override suspend fun save(state: PoolDiscoveryState) {
        this.state = state
    }
}

/** A store that keeps nothing: the feed still coalesces and revalidates, but re-asks per launch. */
object NoPoolDiscoveryStore : PoolDiscoveryStore {
    override suspend fun load(): PoolDiscoveryState? = null

    override suspend fun save(state: PoolDiscoveryState) = Unit
}

/** The platform vault, under its own key; a vault failure leaves discovery in memory for the session. */
class VaultPoolDiscoveryStore(
    private val vault: SecureCredentialStore,
    private val key: CredentialKey,
) : PoolDiscoveryStore {
    override suspend fun load(): PoolDiscoveryState? =
        PoolDiscoveryStateCodec.decode(runCatching { vault.read(key) }.getOrNull())

    override suspend fun save(state: PoolDiscoveryState) {
        runCatching { vault.write(key, PoolDiscoveryStateCodec.encode(state)) }
    }
}

/**
 * The `{ streaming, instances, api }` discovery payload every service serves, tolerant of the
 * three shapes that have shipped: object-of-arrays, a bare array of strings/objects, and a plain
 * whitespace-separated list. [url] keys win, then `baseUrl`, then `host`. [dropSquidWtf] is the
 * Tidal-only rule (a working Qobuz proxy, a dead Tidal host).
 */
object PoolDiscoveryPayload {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun urls(body: String, dropSquidWtf: Boolean = false): List<String> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        val element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
        val jsonBody =
            when (element) {
                is JsonObject -> {
                    for (key in listOf("streaming", "instances", "api")) {
                        (element[key] as? JsonArray)?.let { collect(it, out) }
                    }
                    true
                }
                is JsonArray -> {
                    collect(element, out)
                    true
                }
                else -> false
            }
        // A parsed JSON body that yielded nothing is an empty feed, not a whitespace-separated list.
        if (jsonBody) return if (dropSquidWtf) out.filterNot { it.contains(".squid.wtf", ignoreCase = true) } else out.toList()
        return trimmed
            .split('\n', '\r', ' ', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun collect(array: JsonArray, into: MutableCollection<String>) {
        for (item in array) {
            val value =
                when (item) {
                    is JsonPrimitive -> item.contentOrNull
                    is JsonObject ->
                        listOf("url", "baseUrl", "host").firstNotNullOfOrNull { key ->
                            (item[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
                        }
                    else -> null
                }
            value?.trim()?.takeIf { it.isNotEmpty() }?.let(into::add)
        }
    }
}

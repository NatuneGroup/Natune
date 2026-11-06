package moe.rukamori.archivetune.shared.data.provider

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Optional host sink for one-line discovery diagnostics; silent until a host installs one. */
object PoolDiagnostics {
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun log(message: String) {
        sink?.invoke(message)
    }
}

/**
 * One service's pool discovery feed, over whichever deployment [candidateFeeds] orders and
 * whichever host transport [request] is. Everything the three client hosts used to implement
 * separately lives here: the six-hour jittered TTL, the persisted state and `ETag` revalidation
 * (a `304` answers the stored body, so a re-launch costs no download), the single-flight guard
 * (concurrent startup callers coalesce into one request instead of the observed burst), the
 * no-polling-while-backgrounded rule, and the refusal backoff [`401`/`403` for hours, `429`/`503`
 * for the server's `Retry-After`].
 *
 * A failed fetch keeps serving the previous instance list — a transient pool error must not
 * empty the playback source.
 */
class PoolDiscoveryFeed(
    private val service: String,
    private val nowMs: () -> Long,
    private val candidateFeeds: () -> List<String>,
    private val request: suspend (url: String, headers: Map<String, String>) -> PoolResponse?,
    private val parse: (String) -> List<String> = { PoolDiscoveryPayload.urls(it) },
    private val enabled: () -> Boolean = { true },
    private val readKey: () -> String = { "" },
    private val store: PoolDiscoveryStore = NoPoolDiscoveryStore,
    private val foreground: () -> Boolean = { PoolPolling.foreground },
    private val log: (String) -> Unit = PoolDiagnostics::log,
) {
    private val mutex = Mutex()

    @Volatile
    private var loaded: PoolDiscoveryState? = null

    /**
     * The instance list to use now. Answers the persisted list while the TTL has not elapsed, the
     * host is backgrounded, or a refusal's quiet period is still running; otherwise at most one
     * request is made across every concurrent caller.
     */
    suspend fun instances(): List<String> {
        if (!enabled()) return emptyList()
        val state = state()
        if (!PoolDiscoveryPolicy.shouldFetch(state, nowMs(), foreground())) return state.instances
        return mutex.withLock {
            // A caller that waited here sees the state the winner just persisted, so it does not
            // pay for a second request.
            val current = state()
            if (!PoolDiscoveryPolicy.shouldFetch(current, nowMs(), foreground())) return@withLock current.instances
            refresh(current)
        }
    }

    /** The persisted list without any request, for hosts that seed their instance set at startup. */
    suspend fun cachedInstances(): List<String> = state().instances

    /** Drops the discovered list (the Pool switch went off) — also clears the persisted copy. */
    suspend fun forget() {
        mutex.withLock {
            val next = PoolDiscoveryState()
            loaded = next
            store.save(next)
        }
    }

    private suspend fun state(): PoolDiscoveryState {
        loaded?.let { return it }
        val value = store.load() ?: PoolDiscoveryState()
        loaded = value
        return value
    }

    private suspend fun refresh(current: PoolDiscoveryState): List<String> {
        var answer: PoolResponse? = null
        for (url in candidateFeeds()) {
            if (!enabled()) return current.instances
            val reply =
                try {
                    request(url, headers(current))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            if (reply == null) continue
            if (ArchivePoolEndpoints.isFailoverStatus(reply.status)) {
                answer = reply
                continue
            }
            answer = reply
            if (!reply.refused) ArchivePoolEndpoints.remember(url.substringBefore("/api/"))
            break
        }
        val now = nowMs()
        val next =
            when {
                answer == null -> {
                    log("$service discovery: no deployment answered; paused ${PoolDiscoveryPolicy.TRANSPORT_WINDOW_MS} ms")
                    PoolDiscoveryPolicy.nextAfterTransportFailure(current, now)
                }
                answer.notModified -> PoolDiscoveryPolicy.nextAfterNotModified(current, now)
                answer.status in 200..299 -> {
                    val parsed = runCatching { parse(answer.body) }.getOrDefault(emptyList())
                    val instances = parsed.ifEmpty { current.instances }
                    PoolDiscoveryPolicy.nextAfterSuccess(current, now, instances, if (parsed.isEmpty()) current.etag else answer.etag)
                }
                else -> {
                    val refused = PoolDiscoveryPolicy.nextAfterRefusal(current, now, answer.status, answer.retryAfterMs)
                    log("$service discovery: refused (HTTP ${answer.status}); quiet for ${refused.blockedUntilMs - now} ms")
                    refused
                }
            }
        loaded = next
        store.save(next)
        return next.instances
    }

    private fun headers(state: PoolDiscoveryState): Map<String, String> =
        buildMap {
            val key = readKey()
            if (key.isNotBlank()) put("Authorization", "Bearer $key")
            state.etag?.takeIf { it.isNotBlank() }?.let { put("If-None-Match", it) }
        }
}

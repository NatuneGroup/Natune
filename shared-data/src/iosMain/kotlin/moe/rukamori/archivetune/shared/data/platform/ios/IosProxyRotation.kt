@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.rukamori.archivetune.shared.domain.playback.SystemMonotonicClock
import moe.rukamori.archivetune.shared.domain.settings.ProxyRoute
import moe.rukamori.archivetune.shared.domain.settings.ProxyRouteType
import platform.Foundation.NSLock

/**
 * The community proxy list the Android and desktop rotation pools fetch: one `host:port` per line of plain
 * HTTP proxies. The first candidates are taken, the live ones form the pool.
 */
private const val ROTATION_LIST_URL = "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt"

/** The same probe the static-route test uses: a member counts live only when YouTube answers through it. */
private const val ROTATION_PROBE_URL = "https://music.youtube.com/generate_204"
private const val MAX_CANDIDATES = 60
private const val MAX_POOL_SIZE = 20
private const val VALIDATION_PERMITS = 12
private const val FETCH_TIMEOUT_MS = 20_000L
private const val PROBE_REQUEST_MS = 6_000L
private const val PROBE_CONNECT_MS = 4_000L
private const val FAILURE_COOLDOWN_MS = 60_000L
private const val NANOS_PER_MS = 1_000_000L

private inline fun <T> NSLock.locked(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

private fun nowNanos(): Long = SystemMonotonicClock.nowNanos()

/**
 * The bounded rotating pool behind iOS IP rotation. Its lifecycle mirrors the desktop
 * `DesktopRotatingProxySelector`: the same upstream list, the same candidate bound, the same probe and the
 * same one-minute cooldown after a member broke a connection. The Darwin engine cannot re-route a session it
 * already built, so the pool never feeds the pinned engine route; it answers which proxy the next fresh
 * stream session opens with through [IosStreamProxyRouting].
 */
internal class IosProxyRotation {
    private class Entry(val route: ProxyRoute, var deadUntilNanos: Long)

    private val lock = NSLock()
    private var entries: List<Entry> = emptyList()
    private var index = 0

    /** Live members: every entry except one still inside its failure cooldown. */
    fun activeCount(): Int {
        val now = nowNanos()
        return lock.locked { entries.count { it.deadUntilNanos <= now } }
    }

    /** Whether the pool holds any member at all, cooled ones included — [next] can always answer then. */
    fun hasMembers(): Boolean = lock.locked { entries.isNotEmpty() }

    /** Replaces the pool, as a finished fetch does; callers in tests inject members the same way. */
    fun loadRoutes(routes: List<ProxyRoute>) = lock.locked {
        entries = routes.map { Entry(it, deadUntilNanos = 0L) }
        index = 0
    }

    /** Advances the start point without refetching; a member leaves early only through [markFailed]. */
    fun rotate() = lock.locked {
        if (entries.isNotEmpty()) index = (index + 1) % entries.size
    }

    /**
     * The next member to open a connection through. An all-cooling pool still answers one: rotation exists to
     * keep requests off the real address, and a null answer would leak the next request direct.
     */
    fun next(): ProxyRoute? = lock.locked {
        if (entries.isEmpty()) return@locked null
        val start = index
        index = (index + 1) % entries.size
        val now = nowNanos()
        for (offset in entries.indices) {
            val candidate = entries[(start + offset) % entries.size]
            if (candidate.deadUntilNanos <= now) return@locked candidate.route
        }
        entries[start].route
    }

    /** Cools one member after a connection through it broke, so the next pick moves past it. */
    fun markFailed(route: ProxyRoute) = lock.locked {
        entries.firstOrNull { it.route.endpointKey == route.endpointKey }?.let {
            it.deadUntilNanos = nowNanos() + FAILURE_COOLDOWN_MS * NANOS_PER_MS
        }
    }

    fun clear() = lock.locked {
        entries = emptyList()
        index = 0
    }

    /**
     * Fetches the candidate list and keeps the first [MAX_POOL_SIZE] members that carry the probe, validating
     * [VALIDATION_PERMITS] at a time like the desktop pool. The list fetch itself takes the saved route when
     * there is one — a network that only reaches the web through the static proxy cannot read the list direct.
     */
    suspend fun fetchAndLoad(savedRoute: () -> ProxyRoute? = { null }): Int {
        val candidates = fetchCandidates(savedRoute)
        val live = coroutineScope {
            val permits = Semaphore(VALIDATION_PERMITS)
            candidates.map { candidate ->
                async { permits.withPermit { if (probeThrough(candidate)) candidate else null } }
            }.awaitAll().filterNotNull().take(MAX_POOL_SIZE)
        }
        // A fetch or validation that finds nothing keeps the pool it was meant to replace: the members
        // already validated are a better answer than an empty set while the caller's toggle stays on.
        if (live.isNotEmpty()) {
            loadRoutes(live.map { (host, port) -> ProxyRoute(ProxyRouteType.HTTP, host, port) })
        }
        return live.size
    }

    private suspend fun fetchCandidates(savedRoute: () -> ProxyRoute?): List<Pair<String, Int>> {
        val client = HttpClient(Darwin) {
            // The same engine application the pinned client gets: the dictionary plus the credential
            // challenge, so a saved proxy that asks for a password can still carry the list fetch.
            savedRoute()?.let { route -> engine { applyIosProxyRoute(route) } }
            install(HttpTimeout) {
                requestTimeoutMillis = FETCH_TIMEOUT_MS
                connectTimeoutMillis = FETCH_TIMEOUT_MS
            }
        }
        return try {
            client.get(ROTATION_LIST_URL)
                .takeIf { it.status.value == 200 }
                ?.bodyAsText()
                ?.lineSequence()
                ?.mapNotNull { line ->
                    val trimmed = line.trim()
                    val colon = trimmed.lastIndexOf(':')
                    if (colon < 0) return@mapNotNull null
                    val host = trimmed.substring(0, colon)
                    val port = trimmed.substring(colon + 1).toIntOrNull() ?: return@mapNotNull null
                    if (port !in 1..65535 || host.isBlank()) null else host to port
                }
                ?.take(MAX_CANDIDATES)
                ?.toList()
                .orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        } finally {
            client.close()
        }
    }

    /** A member counts live only when the 204 endpoint answers through it, never by its presence in the list. */
    private suspend fun probeThrough(candidate: Pair<String, Int>): Boolean {
        val route = ProxyRoute(ProxyRouteType.HTTP, candidate.first, candidate.second)
        val client = HttpClient(Darwin) {
            engine { applyIosProxyRoute(route) }
            install(HttpTimeout) {
                requestTimeoutMillis = PROBE_REQUEST_MS
                connectTimeoutMillis = PROBE_CONNECT_MS
                socketTimeoutMillis = PROBE_REQUEST_MS
            }
        }
        return try {
            client.get(ROTATION_PROBE_URL).status.value in 200..399
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            client.close()
        }
    }
}

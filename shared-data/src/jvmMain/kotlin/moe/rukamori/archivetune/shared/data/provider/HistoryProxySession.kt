package moe.rukamori.archivetune.shared.data.provider

import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import moe.rukamori.archivetune.shared.domain.repository.ContentAccountChanged

/** A route owns its credentials in memory; it is never serialized or included in diagnostics. */
internal class HistoryProxyRoute(val proxy: Proxy, val username: String?, val password: String?)

/**
 * Continuations are bound to their first page's exit IP. A refresh chooses again, but a retry must not.
 * Callers serialize this state with the private history client's mutex.
 */
internal class HistoryProxySession {
    private var route: HistoryProxyRoute? = null
    private var cookie: String? = null
    private var dataSyncId: String? = null
    private var next: String? = null

    fun routeFor(
        continuation: String?,
        cookie: String?,
        dataSyncId: String?,
        select: () -> HistoryProxyRoute,
    ): HistoryProxyRoute {
        if (continuation == null) {
            reset()
            return select().also {
                route = it
                this.cookie = cookie
                this.dataSyncId = dataSyncId
            }
        }
        if (route == null || this.cookie != cookie || this.dataSyncId != dataSyncId) {
            reset()
            throw ContentAccountChanged()
        }
        if (continuation != next) throw YouTubeHistoryRoutingUnavailable()
        return requireNotNull(route)
    }

    fun acceptNext(continuation: String?) {
        check(route != null)
        next = continuation
    }

    fun reset() {
        route = null
        cookie = null
        dataSyncId = null
        next = null
    }
}

internal fun selectHistoryProxyRoute(
    selector: ProxySelector?,
    configuredProxy: Proxy?,
    username: String?,
    password: String?,
): HistoryProxyRoute {
    val proxy = selector?.select(URI("https://music.youtube.com"))?.firstOrNull() ?: Proxy.NO_PROXY
    // A public rotating proxy must never inherit the user's static-proxy password.
    val authenticated = proxy.type() != Proxy.Type.DIRECT && proxy == configuredProxy
    return HistoryProxyRoute(proxy, username.takeIf { authenticated }, password.takeIf { authenticated })
}

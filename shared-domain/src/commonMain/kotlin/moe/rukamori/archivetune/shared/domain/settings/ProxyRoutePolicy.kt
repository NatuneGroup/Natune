package moe.rukamori.archivetune.shared.domain.settings

/** The two proxy kinds every host's HTTP stack can express. */
enum class ProxyRouteType { HTTP, SOCKS }

/**
 * One saved proxy route, read once per decision so a host never re-parses the keys mid-request. The credentials stay in
 * memory only: [toString] and [endpointKey] never include them, so a diagnostic or a comparison cannot leak them.
 */
data class ProxyRoute(
    val type: ProxyRouteType,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val bypassStreams: Boolean = false,
) {
    val hasCredentials: Boolean get() = !username.isNullOrEmpty() && !password.isNullOrEmpty()

    /** Address identity of the route without credentials, for comparing a running route with a saved one. */
    val endpointKey: String get() = "${type.name.lowercase()}://$host:$port"

    override fun toString(): String = "ProxyRoute(type=$type, host=$host, port=$port, auth=$hasCredentials)"
}

/**
 * The local hosts a host's proxy stack can keep off a saved route by name. It is deliberately name-only: a host that
 * decides per connection (the desktop selector) can resolve addresses, but a proxy configuration that is fixed for a
 * whole session can only carry a name list.
 */
val PROXY_LOCAL_HOST_EXCEPTIONS: List<String> = listOf("localhost", "*.local", "127.0.0.1", "::1")

/** The saved route, or null while the switch is off or the saved host and port cannot be used. */
fun PreferenceSnapshot.proxyRoute(): ProxyRoute? {
    if (!this[ProxyEnabledKey, false]) return null
    val parsed = parseProxyHostInput(this[ProxyHostKey, ""]) ?: return null
    val port = parsed.port ?: this[ProxyPortKey, 8080]
    if (port !in 1..65535) return null
    return ProxyRoute(
        type = if (this[ProxyTypeKey, "HTTP"] == "SOCKS") ProxyRouteType.SOCKS else ProxyRouteType.HTTP,
        host = parsed.host,
        port = port,
        username = this[ProxyUsernameKey]?.trim()?.takeIf(String::isNotEmpty),
        password = this[ProxyPasswordKey]?.takeIf(String::isNotEmpty),
        bypassStreams = this[StreamBypassProxyKey, false],
    )
}

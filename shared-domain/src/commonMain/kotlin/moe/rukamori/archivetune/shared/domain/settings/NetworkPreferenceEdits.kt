package moe.rukamori.archivetune.shared.domain.settings

data class ProxyHostInput(val host: String, val port: Int?)

/** Baseline ProxyUtils input grammar, without a JVM URI/Proxy dependency. */
fun parseProxyHostInput(raw: String): ProxyHostInput? {
    var value = raw.trim()
    if (value.isEmpty()) return null
    value = value.substringAfter("://", value).substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
    val host: String
    val port: Int?
    when {
        value.startsWith("[") -> {
            val end = value.indexOf(']')
            if (end < 0) return null
            host = value.substring(1, end)
            val rest = value.substring(end + 1)
            port = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.drop(1).toIntOrNull() ?: return null
                else -> return null
            }
        }
        value.count { it == ':' } == 1 -> {
            host = value.substringBefore(':')
            port = value.substringAfter(':').toIntOrNull() ?: return null
        }
        else -> { host = value; port = null }
    }
    if (!Regex("[A-Za-z0-9._%:-]+").matches(host) || port != null && port !in 1..65535) return null
    return ProxyHostInput(host, port)
}

fun PreferenceEditor.setProxyHostInput(raw: String) {
    val parsed = requireNotNull(parseProxyHostInput(raw))
    // Userinfo in a pasted URL was never proxy authentication; never persist it in a plain host key.
    this[ProxyHostKey] = parsed.host
    parsed.port?.let { this[ProxyPortKey] = it }
}

fun PreferenceEditor.setYouTubeMusicRegion(region: String) {
    if (this[YouTubeMusicRegionKey, "SYSTEM_DEFAULT"] == region) return
    this[YouTubeMusicRegionKey] = region
    this[VisitorDataKey] = null
}

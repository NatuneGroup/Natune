package moe.rukamori.archivetune.desktop.integration

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID

/**
 * Memory-only OAuth attempts; no credentials, callback codes or state are logged or persisted. [authorityless] names
 * the schemes whose registered OAuth redirect has no host, such as discord-<id>:/authorize/callback.
 */
class DesktopDeepLinks(
    private val schemes: Set<String>,
    private val appHosts: Set<String>,
    private val authorityless: Set<String> = emptySet(),
) {
    private data class Attempt(val id: UUID, val redirect: URI, val state: String, val consume: (String) -> Unit)
    private val attempts = mutableMapOf<String, Attempt>()
    private val pending = ArrayDeque<String>()
    private var listener: ((String) -> Unit)? = null

    @Synchronized
    fun expectCallback(redirectUri: String, state: String, consume: (String) -> Unit): AutoCloseable {
        val redirect = parse(redirectUri) ?: error("Invalid callback redirect")
        require(redirect.scheme in schemes && redirect.host !in appHosts && redirect.rawQuery == null)
        require(state.isNotEmpty() && state.length <= 1024)
        val key = endpoint(redirect)
        val attempt = Attempt(UUID.randomUUID(), redirect, state, consume)
        attempts[key] = attempt
        return AutoCloseable { synchronized(this) { if (attempts[key]?.id == attempt.id) attempts.remove(key) } }
    }

    fun accept(raw: String): Boolean {
        val uri = parse(raw) ?: return false
        if (uri.scheme !in schemes) return false
        val consumer: ((String) -> Unit)?
        synchronized(this) {
            val attempt = attempts[endpoint(uri)]
            if (attempt != null) {
                val states = try {
                    uri.rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }.map {
                        val parts = it.split("=", limit = 2)
                        URLDecoder.decode(parts[0], Charsets.UTF_8) to
                            URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8)
                    }.filter { it.first == "state" }.map { it.second }
                } catch (_: IllegalArgumentException) { return false }
                if (states.size != 1 || !MessageDigest.isEqual(
                        attempt.state.toByteArray(Charsets.UTF_8), states.single().toByteArray(Charsets.UTF_8),
                    )) return false
                attempts.remove(endpoint(uri))
                consumer = attempt.consume
            } else {
                if (uri.host !in appHosts) return false
                consumer = listener
                if (consumer == null) {
                    if (pending.size >= 16) return false
                    pending += raw
                }
            }
        }
        consumer?.invoke(raw)
        return true
    }

    fun attach(consume: (String) -> Unit) {
        val buffered = synchronized(this) {
            listener = consume
            pending.toList().also { pending.clear() }
        }
        buffered.forEach(consume)
    }

    private fun parse(raw: String): URI? = try {
        if (raw.length > 8192 || raw.any { it.code < 32 }) null else URI(raw).takeIf {
            !it.isOpaque && acceptedShape(it) && it.userInfo == null && it.port == -1 && it.rawFragment == null
        }
    } catch (_: Exception) { null }

    // An authorityless scheme has exactly one slash before its absolute path; "//" would start an authority instead.
    private fun acceptedShape(uri: URI): Boolean = !uri.host.isNullOrEmpty() || (uri.scheme in authorityless &&
        uri.rawSchemeSpecificPart.let { it.startsWith("/") && !it.startsWith("//") })

    private fun endpoint(uri: URI) = "${uri.scheme}:${uri.host?.let { "//$it" }.orEmpty()}${uri.rawPath.orEmpty()}"
}

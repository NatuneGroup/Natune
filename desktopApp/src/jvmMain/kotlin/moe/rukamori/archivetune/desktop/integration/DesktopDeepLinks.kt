/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID

/** Memory-only OAuth attempts; no credentials, callback codes or state are logged or persisted. */
class DesktopDeepLinks(private val schemes: Set<String>, private val appHosts: Set<String>) {
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
            !it.isOpaque && !it.host.isNullOrEmpty() && it.userInfo == null && it.port == -1 && it.rawFragment == null
        }
    } catch (_: Exception) { null }

    private fun endpoint(uri: URI) = "${uri.scheme}://${uri.host}${uri.rawPath.orEmpty()}"
}

package moe.rukamori.archivetune.desktop.potoken

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.data.potoken.MintedPoTokens

/**
 * Mints the pair the resolver and the InnerTubeX provider need from the helper's `/get_pot` endpoint. A token is
 * cached under its content binding (the video id for the player token, the visitor/session id for the session token)
 * and expires on a fixed TTL, so repeated resolves inside one session do not re-mint while a stale binding never
 * survives a helper restart or a long pause.
 *
 * The helper mints one token per binding; [mint] therefore asks for both bindings and answers null when either is
 * missing. A null answer lets the caller send the untokened request it would have sent without the helper, which is
 * the documented fallback and never a fabricated token.
 */
internal class BgutilPotTokenClient(
    private val transport: BgutilSidecarTransport,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TOKEN_CACHE_TTL_MS,
    private val maxEntries: Int = TOKEN_CACHE_SIZE,
) {
    private class CachedToken(val token: String, val expiresAtMs: Long)

    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, CachedToken>()

    suspend fun mint(baseUrl: String, videoId: String, sessionId: String): MintedPoTokens? {
        if (videoId.isBlank() || sessionId.isBlank()) return null
        val playerToken = token(baseUrl, videoId) ?: return null
        val sessionToken = token(baseUrl, sessionId) ?: return null
        return MintedPoTokens(playerToken = playerToken, sessionToken = sessionToken)
    }

    suspend fun token(baseUrl: String, contentBinding: String): String? {
        if (contentBinding.isBlank()) return null
        mutex.withLock {
            val cached = cache.remove(contentBinding)
            if (cached != null && cached.expiresAtMs > nowMs()) {
                // Re-insert so the bounded map keeps least-recently-used entries at the front.
                cache[contentBinding] = cached
                return cached.token
            }
        }
        val token = transport.fetchPot(baseUrl, contentBinding)?.takeIf(String::isNotBlank) ?: return null
        mutex.withLock {
            cache.remove(contentBinding)
            cache[contentBinding] = CachedToken(token, nowMs() + ttlMs)
            while (cache.size > maxEntries) {
                val oldest = cache.keys.firstOrNull() ?: break
                cache.remove(oldest)
            }
        }
        return token
    }

    /** Drops every cached binding; called when the helper is invalidated, restarted or stopped. */
    suspend fun clear() {
        mutex.withLock { cache.clear() }
    }

    internal companion object {
        /** Ten minutes, matching Metrolist's provider cache window and comfortably inside the helper's six-hour TTL. */
        const val TOKEN_CACHE_TTL_MS = 10L * 60L * 1_000L
        const val TOKEN_CACHE_SIZE = 200
    }
}

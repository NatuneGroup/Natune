package moe.rukamori.archivetune.shared.data.provider

/**
 * The exponential backoff a pool feed applies after the server refuses the presented read key:
 * `401`/`403` are configuration state — a missing, revoked or foreign key cannot be fixed by
 * asking again — so the first refusal already buys hours and each further one doubles toward
 * [MAX_WINDOW_MS]. `429`/`503` are the pool throttling or being briefly unavailable; those honour
 * the server's own `Retry-After` exactly and only fall back to the doubling window when the
 * header is absent. Windows this class chooses itself carry ±[JITTER_PERCENT] jitter so a release
 * cannot synchronize every install's next attempt; a server-provided window is never shortened.
 *
 * Hosts persist [failedAtMs], [windowMs] and [failedKeyHash] alongside their feed cache so the
 * backoff survives restarts. The refused key is part of the state: a different key (a new build)
 * clears it instead of inheriting the refusal ([clearsFor]).
 */
class PoolAuthBackoff {
    /** The refused attempt's timestamp, or 0 when no backoff is active. */
    var failedAtMs = 0L
        private set

    /** The current quiet period; doubles on each refusal inside the cap. */
    var windowMs = 0L
        private set

    /** Identity of the key the last refusal was earned with — a different key resets the backoff. */
    var failedKeyHash = 0
        private set

    /** Restores the persisted fields after a restart. */
    fun restore(failedAtMs: Long, windowMs: Long, failedKeyHash: Int) {
        this.failedAtMs = failedAtMs
        this.windowMs = windowMs
        this.failedKeyHash = failedKeyHash
    }

    /** Whether another fetch inside the quiet period would just re-hear the same refusal. */
    fun blocked(nowMs: Long): Boolean = failedAtMs > 0L && nowMs - failedAtMs < windowMs

    /** The quiet period's end, or 0 when no backoff is active. */
    fun blockedUntilMs(): Long = if (windowMs <= 0L) 0L else failedAtMs + windowMs

    /**
     * True when a backoff is active and [keyHash] is not the refused key, so the stored backoff
     * does not apply to it. The refused key hashes to 0 when it is empty, so "a previous refusal
     * exists" is read off [windowMs] — never off a non-zero hash, which would let a different key
     * inherit the empty key's backoff.
     */
    fun clearsFor(keyHash: Int): Boolean = windowMs > 0L && failedKeyHash != keyHash

    /**
     * Records one unauthorized refusal (`401`/`403`): the quiet period starts at
     * [UNAUTHORIZED_WINDOW_MS] and doubles inside [MAX_WINDOW_MS] with jitter.
     */
    fun onAuthRejected(nowMs: Long, keyHash: Int) {
        refuse(nowMs, keyHash, if (windowMs <= 0L) UNAUTHORIZED_WINDOW_MS else windowMs * 2, jitter = true)
    }

    /**
     * Records one throttled/unavailable refusal (`429`/`503`). A present [retryAfterMs] is the
     * window verbatim (clamped to [MIN_WINDOW_MS]..[MAX_WINDOW_MS]); without one the doubling
     * window applies, starting at [TRANSIENT_WINDOW_MS].
     */
    fun onThrottled(nowMs: Long, keyHash: Int, retryAfterMs: Long? = null) {
        val window =
            retryAfterMs?.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
                ?: if (windowMs <= 0L) TRANSIENT_WINDOW_MS else windowMs * 2
        refuse(nowMs, keyHash, window, jitter = retryAfterMs == null)
    }

    /**
     * Records whichever refusal [status] is: `401`/`403` as a configuration refusal (hours),
     * `429`/`503` as throttling (the server's `Retry-After`, else a shorter doubling window).
     */
    fun onRefusal(nowMs: Long, keyHash: Int, status: Int, retryAfterMs: Long? = null) {
        if (status == 401 || status == 403) onAuthRejected(nowMs, keyHash) else onThrottled(nowMs, keyHash, retryAfterMs)
    }

    /** Clears the backoff after a successful fetch or a key change. */
    fun reset() = restore(0L, 0L, 0)

    private fun refuse(nowMs: Long, keyHash: Int, window: Long, jitter: Boolean) {
        failedAtMs = nowMs
        // Jitter first, then the cap: no refusal may buy more than a day of silence.
        windowMs = (if (jitter) jittered(window, nowMs) else window).coerceAtMost(MAX_WINDOW_MS)
        failedKeyHash = keyHash
    }

    private fun jittered(window: Long, seed: Long): Long {
        if (JITTER_PERCENT <= 0) return window
        val spread = window / 100 * JITTER_PERCENT
        if (spread <= 0L) return window
        val offset = ((seed xor (seed ushr 13)) and 0xFFFF) % (2 * spread + 1) - spread
        return (window + offset).coerceAtLeast(MIN_WINDOW_MS)
    }

    private companion object {
        private const val JITTER_PERCENT = 20L
        private const val MIN_WINDOW_MS = 30L * 1000L
        private const val TRANSIENT_WINDOW_MS = 5L * 60L * 1000L
        private const val UNAUTHORIZED_WINDOW_MS = 6L * 60L * 60L * 1000L
        private const val MAX_WINDOW_MS = 24L * 60L * 60L * 1000L
    }
}

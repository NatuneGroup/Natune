package moe.rukamori.archivetune.shared.data.provider

/**
 * The exponential backoff a pool account feed applies after the server refuses the presented read
 * key (HTTP 401): an unkeyed or revoked-key deployment cannot be fixed by asking again, so each
 * refusal doubles the quiet period from [MIN_WINDOW_MS] toward [MAX_WINDOW_MS] until a success or
 * a different key clears it. Hosts persist [failedAtMs], [windowMs] and [failedKeyHash] alongside
 * their feed cache so the backoff survives restarts.
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

    /** True when [keyHash] is not the refused key, so the stored backoff does not apply to it. */
    fun clearsFor(keyHash: Int): Boolean = failedKeyHash != 0 && failedKeyHash != keyHash

    /** Records one key refusal: the quiet period doubles inside [MIN_WINDOW_MS]..[MAX_WINDOW_MS]. */
    fun onAuthRejected(nowMs: Long, keyHash: Int) {
        failedAtMs = nowMs
        windowMs = (if (windowMs <= 0L) MIN_WINDOW_MS else windowMs * 2).coerceAtMost(MAX_WINDOW_MS)
        failedKeyHash = keyHash
    }

    /** Clears the backoff after a successful fetch or a key change. */
    fun reset() = restore(0L, 0L, 0)

    private companion object {
        private const val MIN_WINDOW_MS = 15L * 60L * 1000L
        private const val MAX_WINDOW_MS = 24L * 60L * 60L * 1000L
    }
}

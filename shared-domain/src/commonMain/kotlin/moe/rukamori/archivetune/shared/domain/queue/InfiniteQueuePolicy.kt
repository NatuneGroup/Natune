package moe.rukamori.archivetune.shared.domain.queue

/** Recommendation paging is separate from finite playlist membership and from audio-source resolution. */
object InfiniteQueuePolicy {
    const val SEED_REMAINING_ITEMS = 3
    const val PAGE_REMAINING_ITEMS = 5
    const val MAX_BOOTSTRAP_PAGES = 3
    const val REQUEST_TIMEOUT_MS = 15_000L

    /** Includes the current song, in actual play order rather than timeline order under shuffle. */
    fun shouldAppend(enabled: Boolean, repeatOff: Boolean, remainingItems: Int, continuation: Boolean): Boolean =
        enabled && repeatOff && remainingItems in 1..(if (continuation) PAGE_REMAINING_ITEMS else SEED_REMAINING_ITEMS)

    /** Only recommendations are deduplicated; deliberate occurrences in finite/manual queues are untouched. */
    fun <T, K> fresh(items: List<T>, queued: Iterable<K>, key: (T) -> K): List<T> {
        val known = queued.toHashSet()
        return items.filter { known.add(key(it)) }
    }
}

/**
 * One in-flight append and bounded exponential failure/empty-result backoff. Owners supply monotonic milliseconds.
 * Disabling or replacing a queue invalidates its ticket, including a transport that ignores cancellation.
 */
class InfiniteQueueLoadGate {
    private var sequence = 0L
    private var active: Long? = null
    private var failures = 0
    private var retryAtMs = Long.MIN_VALUE

    fun begin(
        enabled: Boolean,
        repeatOff: Boolean,
        remainingItems: Int,
        continuation: Boolean,
        nowMs: Long,
    ): Long? {
        if (!InfiniteQueuePolicy.shouldAppend(enabled, repeatOff, remainingItems, continuation) ||
            active != null || nowMs < retryAtMs
        ) return null
        return (++sequence).also { active = it }
    }

    fun isCurrent(ticket: Long): Boolean = active == ticket

    fun finish(ticket: Long, added: Int, nowMs: Long): Boolean {
        if (!isCurrent(ticket)) return false
        active = null
        if (added > 0) {
            failures = 0
            retryAtMs = Long.MIN_VALUE
        } else {
            failures = (failures + 1).coerceAtMost(MAX_FAILURE_STEPS)
            val delay = (INITIAL_BACKOFF_MS * (1L shl (failures - 1))).coerceAtMost(MAX_BACKOFF_MS)
            retryAtMs = if (nowMs > Long.MAX_VALUE - delay) Long.MAX_VALUE else nowMs + delay
        }
        return true
    }

    /** An off/on toggle does not bypass a failed provider's cooldown; a different queue starts afresh. */
    fun invalidate(newQueue: Boolean = false) {
        sequence++
        active = null
        if (newQueue) {
            failures = 0
            retryAtMs = Long.MIN_VALUE
        }
    }

    companion object {
        const val INITIAL_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 300_000L
        private const val MAX_FAILURE_STEPS = 5
    }
}

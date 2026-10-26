package moe.rukamori.archivetune.shared.ui.lyrics

import kotlinx.coroutines.yield
import kotlin.time.TimeSource

/** The cooperative slice that a prewarm may use before it yields the UI thread. */
const val LYRICS_PREWARM_BUDGET_NANOS = 3_000_000L

/**
 * A bounded, least-recently-used cache of shaped lyric values. The shape work runs once per key, and a hit
 * moves the entry to the back, so the rows on screen stay resident. Use it on the UI thread only.
 */
class LyricsShapeCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val shape: (K) -> V,
) {
    private val entries = LinkedHashMap<K, V>()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    val size: Int
        get() = entries.size

    fun get(key: K): V {
        entries.remove(key)?.let { value ->
            entries[key] = value
            return value
        }
        val value = shape(key)
        entries[key] = value
        while (entries.size > maxEntries) {
            entries.remove(entries.keys.first())
        }
        return value
    }

    /**
     * Shapes [keys] in order, yielding after each [budgetNanos] slice of work. The budget is checked after an
     * item, so a single slow item may overrun it. Each key that is already cached costs nothing.
     */
    suspend fun prewarm(keys: Iterable<K>, budgetNanos: Long = LYRICS_PREWARM_BUDGET_NANOS) {
        var slice = TimeSource.Monotonic.markNow()
        for (key in keys) {
            if (!entries.containsKey(key)) get(key)
            if (slice.elapsedNow().inWholeNanoseconds >= budgetNanos) {
                yield()
                slice = TimeSource.Monotonic.markNow()
            }
        }
    }

    fun clear() {
        entries.clear()
    }
}

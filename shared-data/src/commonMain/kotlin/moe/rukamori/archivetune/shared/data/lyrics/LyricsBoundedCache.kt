/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A least-recently-used map with a fixed [capacity], written in common code because the JVM
 * access-order `LinkedHashMap` is not available here. `mutableMapOf` iterates in insertion order,
 * so remove-and-reinsert on every read keeps the eldest entry the least recently used one.
 *
 * Guarded by a [Mutex] instead of `synchronized`, so callers are suspending and a read never blocks
 * a thread that a provider callback may be waiting on.
 */
internal class LyricsBoundedCache<K : Any, V : Any>(
    private val capacity: Int,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lock = Mutex()
    private val entries = mutableMapOf<K, V>()

    /** Returns the value and marks it most recently used. */
    suspend fun get(key: K): V? =
        lock.withLock {
            val value = entries.remove(key) ?: return@withLock null
            entries[key] = value
            value
        }

    suspend fun put(
        key: K,
        value: V,
    ) {
        lock.withLock {
            entries.remove(key)
            entries[key] = value
            while (entries.size > capacity) {
                entries.remove(entries.keys.first())
            }
        }
    }

    suspend fun remove(key: K) {
        lock.withLock { entries.remove(key) }
    }

    suspend fun clear() {
        lock.withLock { entries.clear() }
    }

    suspend fun size(): Int = lock.withLock { entries.size }

    /** Keys from least to most recently used. Exposed for tests of eviction order. */
    suspend fun keysLeastRecentFirst(): List<K> = lock.withLock { entries.keys.toList() }
}

/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

object PoolRefreshPolicy {
    const val FULL_CACHE_INTERVAL_MS = 24L * 60L * 60L * 1000L
    const val PARTIAL_CACHE_INTERVAL_MS = 5L * 60L * 60L * 1000L

    fun intervalMs(hasEveryService: Boolean): Long =
        if (hasEveryService) FULL_CACHE_INTERVAL_MS else PARTIAL_CACHE_INTERVAL_MS

    /**
     * [intervalMs] moved by up to ±10%, deterministically from the last attempt so every install
     * keeps its own offset: a release must not synchronize every client's next account refresh.
     */
    fun jitteredIntervalMs(hasEveryService: Boolean, seedMs: Long): Long {
        val base = intervalMs(hasEveryService)
        val spread = base / 10
        if (spread <= 0L) return base
        val offset = ((seedMs xor (seedMs ushr 17)) and 0xFFFF) % (2 * spread + 1) - spread
        return base + offset
    }

    fun latestAttemptAtMs(inMemoryAtMs: Long, persistedAtMs: Long): Long =
        maxOf(0L, inMemoryAtMs, persistedAtMs)

    fun shouldRefresh(
        nowMs: Long,
        lastAttemptAtMs: Long,
        intervalMs: Long,
        force: Boolean,
    ): Boolean {
        if (force || lastAttemptAtMs <= 0L) return true
        return nowMs >= lastAttemptAtMs && nowMs - lastAttemptAtMs >= intervalMs
    }
}

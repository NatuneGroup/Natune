/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.ai

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.playback.SystemMonotonicClock

class AiRateLimitException : Exception("Local AI request budget reached")

/**
 * Share one instance across all AI consumers, not one per request or track. Each feature keeps its own
 * window so lyric work cannot spend the AI Mix budget or the reverse. Budgets mirror the Android limiter:
 * lyric batches are spaced and smoothed, while AI Mix refuses rapid repeats outright.
 */
class AiRateLimiter(
    private val nowMs: () -> Long = { SystemMonotonicClock.nowNanos() / 1_000_000 },
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * [minIntervalMs] is the spacing between reservations, [maxPerHour] caps a sliding hour, and
     * [smoothingWaitMs] is the longest total wait one call may accept before it is refused instead.
     */
    enum class Feature(
        val minIntervalMs: Long,
        val maxPerHour: Int,
        val smoothingWaitMs: Long,
    ) {
        LYRICS_TRANSLATION(minIntervalMs = 1_000L, maxPerHour = 60, smoothingWaitMs = 5_000L),
        LYRICS_ROMANIZATION(minIntervalMs = 1_000L, maxPerHour = 60, smoothingWaitMs = 5_000L),
        AI_MIX(minIntervalMs = 10L * 60_000L, maxPerHour = 6, smoothingWaitMs = 0L),
    }

    private data class Reservation(val id: Long, val at: Long)
    private val mutex = Mutex()
    private val history = mutableMapOf<Feature, MutableList<Reservation>>()
    private var serial = 0L

    suspend fun <T> withLimit(feature: Feature, block: suspend () -> T): T {
        val began = nowMs()
        var reservation: Reservation? = null
        while (reservation == null) {
            val deficit = mutex.withLock {
                val now = nowMs()
                val calls = history.getOrPut(feature) { mutableListOf() }
                calls.removeAll { now - it.at > HOUR_MS }
                if (calls.size >= feature.maxPerHour) throw AiRateLimitException()
                val spacing = calls.lastOrNull()?.let { feature.minIntervalMs - (now - it.at) } ?: 0L
                if (spacing > 0) {
                    if (now - began + spacing > feature.smoothingWaitMs) throw AiRateLimitException()
                    spacing
                } else {
                    reservation = Reservation(++serial, now).also(calls::add)
                    0L
                }
            }
            if (deficit > 0) wait(deficit)
        }
        val reservedId = checkNotNull(reservation).id
        return try {
            block()
        } catch (failure: Throwable) {
            // Identity, not timestamp or deque position: an older cancelled call may finish last.
            withContext(NonCancellable) {
                mutex.withLock { history[feature]?.removeAll { it.id == reservedId } }
            }
            throw failure
        }
    }

    private companion object { const val HOUR_MS = 3_600_000L }
}

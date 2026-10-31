package moe.rukamori.archivetune.shared.ui.marquee

import kotlin.time.TimeSource

internal const val MARQUEE_PHASE_CAPACITY = 64
internal const val MARQUEE_PHASE_EXPIRY_MS = 60_000L

/** The caller's key with the text it scrolls, so the same text under two callers keeps two phases. */
internal data class MarqueePhaseKey(val caller: Any?, val text: String)

private val marqueeOrigin = TimeSource.Monotonic.markNow()

internal fun marqueeClockMs(): Long = marqueeOrigin.elapsedNow().inWholeMilliseconds

/** [iterations] passes: the first starts after [initialDelayMs], each later one after [restMs]. */
internal data class MarqueeTiming(val initialDelayMs: Long, val iterations: Int, val restMs: Long)

/**
 * Remembers where each marquee was in its passes, so leaving and re-entering a page continues the pass rather than
 * starting it again. A key that no composition has observed for [expiryMs] starts over. At most [capacity] keys are
 * kept, and the least recently used unobserved key goes first. Used from composition on the main thread only.
 */
internal class MarqueePhaseStore(
    private val clockMs: () -> Long = ::marqueeClockMs,
    private val capacity: Int = MARQUEE_PHASE_CAPACITY,
    private val expiryMs: Long = MARQUEE_PHASE_EXPIRY_MS,
) {
    private class Phase(val startMs: Long, var observers: Int, var unobservedSinceMs: Long)

    // Insertion order is recency: a key that is touched is removed and appended again.
    private val phases = LinkedHashMap<MarqueePhaseKey, Phase>()

    /** Start time of the pass for [key]. A new pass starts now when none is stored or the stored one has expired. */
    fun begin(key: MarqueePhaseKey): Long {
        val now = clockMs()
        phases.entries.removeAll { it.value.observers == 0 && now - it.value.unobservedSinceMs > expiryMs }
        val previous = phases.remove(key)
        val phase = Phase(
            startMs = previous?.startMs ?: now,
            observers = (previous?.observers ?: 0) + 1,
            unobservedSinceMs = now,
        )
        phases[key] = phase
        while (phases.size > capacity) {
            val victim = phases.entries.firstOrNull { it.value.observers == 0 }?.key ?: phases.keys.first()
            phases.remove(victim)
        }
        return phase.startMs
    }

    /** Called when a composition that began [key] leaves. The phase is kept only until the expiry lapses. */
    fun end(key: MarqueePhaseKey) {
        val phase = phases[key] ?: return
        if (phase.observers > 0) phase.observers--
        if (phase.observers == 0) phase.unobservedSinceMs = clockMs()
    }
}

internal val MarqueePhases = MarqueePhaseStore()

/**
 * Offset in px of the text [elapsedMs] after its phase began. A pass moves [travelPx] over [passMs] and then rests at
 * zero. The trailing copy sits one [travelPx] to the right, so the jump back to zero shows the same frame.
 */
internal fun marqueeOffsetPx(elapsedMs: Long, travelPx: Float, passMs: Long, timing: MarqueeTiming): Float {
    val sinceFirstPass = elapsedMs - timing.initialDelayMs
    if (sinceFirstPass < 0L || passMs <= 0L || travelPx <= 0f || timing.iterations <= 0) return 0f
    val cycleMs = passMs + timing.restMs
    val pass = sinceFirstPass / cycleMs
    if (pass >= timing.iterations) return 0f
    val intoPass = sinceFirstPass - pass * cycleMs
    return if (intoPass < passMs) travelPx * (intoPass.toFloat() / passMs.toFloat()) else 0f
}

/** Time from the phase start until the last pass has finished. */
internal fun marqueeEndMs(passMs: Long, timing: MarqueeTiming): Long =
    timing.initialDelayMs + timing.iterations * (passMs + timing.restMs) - timing.restMs

internal fun marqueePassMs(travelPx: Float, velocityPxPerSecond: Float): Long =
    if (travelPx > 0f && velocityPxPerSecond > 0f) (travelPx / velocityPxPerSecond * 1000f).toLong() else 0L

/** The left fade ramps in as the text leaves its start and ramps out again as the pass ends, so it never pops. */
internal fun marqueeLeftFadePx(offsetPx: Float, travelPx: Float, fadePx: Float): Float =
    minOf(offsetPx, travelPx - offsetPx, fadePx).coerceAtLeast(0f)

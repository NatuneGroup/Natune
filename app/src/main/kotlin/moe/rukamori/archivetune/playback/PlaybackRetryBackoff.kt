package moe.rukamori.archivetune.playback

import kotlin.random.Random

/**
 * The one bounded exponential backoff every playback recovery retry shares: the first retry is
 * immediate, each later attempt waits out a doubling delay with symmetric jitter, the delay never
 * exceeds [maxDelayMs], and no attempt past [maxAttempts] may run at all. It exists so
 * stream-resolution retries and player-error recovery take the same curve instead of separate
 * ad-hoc constants — quick to retry a blip, increasingly reluctant to hammer a dead stream.
 */
internal class PlaybackRetryBackoff(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val jitterFraction: Double = DEFAULT_JITTER_FRACTION,
    private val random: Random = Random.Default,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(baseDelayMs > 0L) { "baseDelayMs must be positive" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs must be at least baseDelayMs" }
        require(jitterFraction in 0.0..1.0) { "jitterFraction must be inside [0, 1]" }
    }

    /** Whether retry number [attempt] (1-based) may still run. */
    fun allows(attempt: Int): Boolean = attempt in 1..maxAttempts

    /**
     * The wait before retry number [attempt] (1-based). Attempt one is immediate; later attempts
     * double [baseDelayMs] per step and add ±[jitterFraction] jitter, capped at [maxDelayMs].
     */
    fun delayMs(attempt: Int): Long {
        if (attempt <= 1) return 0L
        var capped = baseDelayMs
        repeat(attempt - 2) { capped = (capped * 2).coerceAtMost(maxDelayMs) }
        val jitter = (capped * jitterFraction * (random.nextDouble() * 2.0 - 1.0)).toLong()
        return (capped + jitter).coerceIn(0L, maxDelayMs)
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_BASE_DELAY_MS = 600L
        const val DEFAULT_MAX_DELAY_MS = 6_000L
        const val DEFAULT_JITTER_FRACTION = 0.25
    }
}

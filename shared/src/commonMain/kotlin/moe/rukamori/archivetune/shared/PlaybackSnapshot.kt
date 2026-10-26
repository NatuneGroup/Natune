package moe.rukamori.archivetune.shared

import kotlinx.serialization.Serializable

/** Queue occurrence, not song ID: repeating a song creates a distinct clock owner. */
@Serializable
data class PlaybackEpoch(val occurrenceId: String, val discontinuity: Long) {
    init {
        require(occurrenceId.isNotBlank())
        require(discontinuity >= 0)
    }
}

@Serializable
enum class ClockAuthority { LOCAL, REMOTE }

/**
 * One atomic observation supplied by an actual playback adapter, never a timer engine.
 *
 * Monotonic timestamps are process-local and must share an origin; serialized samples
 * are diagnostics only, not resumable clocks. An adapter must publish a new epoch
 * after seek/repeat/source discontinuity and a nonadvancing sample on stall/pause.
 * The consumer retains its current epoch to reject late events from old owners.
 */
@Serializable
data class PlaybackSnapshot(
    val epoch: PlaybackEpoch,
    val positionMillis: Long,
    val observedAtNanos: Long,
    val durationMillis: Long?,
    val speed: Double,
    val playRequested: Boolean,
    val actuallyAdvancing: Boolean,
    val authority: ClockAuthority = ClockAuthority.LOCAL,
) {
    init {
        require(positionMillis >= 0)
        require(durationMillis == null || durationMillis >= positionMillis)
        require(speed.isFinite() && speed > 0)
    }

    /**
     * Bounded display interpolation, not a claim about audible hardware position.
     * Stale/hidden/remote/future samples hold their last observation. A mismatched
     * epoch has no usable position. Requesting play is deliberately not evidence
     * of advancement; conversely a draining output may advance after intent changes.
     */
    fun positionAt(
        nowNanos: Long,
        expectedEpoch: PlaybackEpoch = epoch,
        visibleAndResumed: Boolean = true,
        maxExtrapolationMillis: Long = 1_000,
    ): Long? {
        require(maxExtrapolationMillis in 0..60_000)
        if (expectedEpoch != epoch) return null
        val elapsed = nowNanos - observedAtNanos
        // Explicit ordering also rejects arithmetic overflow on impossible origins.
        if (!actuallyAdvancing || !visibleAndResumed || authority != ClockAuthority.LOCAL ||
            nowNanos < observedAtNanos || elapsed < 0 ||
            elapsed > maxExtrapolationMillis * 1_000_000
        ) return positionMillis
        val advance = (elapsed.toDouble() / 1_000_000 * speed).toLong()
        val projected = if (advance > Long.MAX_VALUE - positionMillis) Long.MAX_VALUE
            else positionMillis + advance
        return minOf(projected, durationMillis ?: Long.MAX_VALUE)
    }

    /** Hold the currently displayable observation; does not issue a playback command. */
    fun freeze(nowNanos: Long): PlaybackSnapshot = copy(
        positionMillis = positionAt(nowNanos) ?: positionMillis,
        observedAtNanos = nowNanos,
        actuallyAdvancing = false,
    )
}

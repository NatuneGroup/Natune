package moe.rukamori.archivetune.shared.data.playback

/**
 * Converts a MediaPlayer remote transport seek into the millisecond target the player accepts.
 *
 * MPRemoteCommandCenter reports absolute scrubber positions and interval skips in seconds, while
 * the shared player seeks in milliseconds. The result is bounded exactly like the on-screen seek
 * buttons: never before zero, never past a known positive duration, and never through a Long
 * overflow. A null, zero or negative duration is unbounded, because the engine owns the playable
 * range of a stream whose length it has not reported yet; clamping that stream to zero would send
 * every forward skip back to the start.
 *
 * Returns null for a non-finite request, so callers use it both as the command's validity check and
 * as its target. Platform-independent so the Lock Screen, Control Center and the on-screen controls
 * cannot drift apart.
 */
internal fun nowPlayingSeekTargetMs(
    basePositionMs: Long,
    deltaSeconds: Double,
    durationMs: Long?,
): Long? {
    if (!deltaSeconds.isFinite()) return null
    val scaled = deltaSeconds * 1_000.0
    // A finite seconds value can still overflow here (e.g. Double.MAX_VALUE * 1_000).
    if (!scaled.isFinite()) return null
    val base = basePositionMs.coerceAtLeast(0L).toDouble()
    val upper = durationMs?.takeIf { it > 0L }?.toDouble() ?: Double.MAX_VALUE
    // coerceIn also absorbs a finite-but-enormous (base + scaled) when the duration is unknown.
    val target = (base + scaled).coerceIn(0.0, upper)
    // Saturate explicitly: the Double -> Long conversion of an out-of-range value is not something
    // the transport path should depend on, and this keeps the ceiling obvious to every backend.
    if (target >= Long.MAX_VALUE.toDouble()) return Long.MAX_VALUE
    return target.toLong()
}

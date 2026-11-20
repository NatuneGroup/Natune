
package app.natune.playback

fun resolveSeekByTargetMs(
    currentPositionMs: Long,
    deltaMs: Long,
    durationMs: Long,
): Long {
    val currentPosition = currentPositionMs.coerceAtLeast(0L)
    val targetPosition =
        (
            if (deltaMs > 0L && currentPosition > Long.MAX_VALUE - deltaMs) {
                Long.MAX_VALUE
            } else {
                currentPosition + deltaMs
            }
        ).coerceAtLeast(0L)
    return if (durationMs > 0L) targetPosition.coerceAtMost(durationMs) else targetPosition
}

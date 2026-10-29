package moe.rukamori.archivetune.shared.data.playback

import kotlin.math.roundToLong

/** Invalid or unrepresentable native estimates stay unknown, rather than overflowing into an invented offset. */
internal fun outputLatencyMillis(seconds: Double): Long? {
    if (!seconds.isFinite() || seconds < 0.0) return null
    val milliseconds = seconds * 1_000.0
    if (!milliseconds.isFinite() || milliseconds >= Long.MAX_VALUE.toDouble()) return null
    return milliseconds.roundToLong()
}

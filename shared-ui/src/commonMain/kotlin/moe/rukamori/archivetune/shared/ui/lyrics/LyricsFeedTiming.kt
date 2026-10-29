package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.math.roundToLong

/**
 * How playback position becomes the position that lyric rows are matched against. Line-only (LRC) timing is
 * written early relative to the sung audio, so it gets a lead. Word-timed rows already line up. The lead and the
 * user's sync offset move the feed only, so row times stay canonical for seeking.
 */
object LyricsFeedTiming {
    const val LRC_LEAD_MS = 300L
    const val TTML_LEAD_MS = 0L

    /** A fixed visual tuning applied on top of the lead, as the Android renderers do. */
    const val VISUAL_TUNING_OFFSET_MS = 150L

    /**
     * The lyric model does not record its container, so the lead follows the timing shape: rows with no word
     * timings are line-only and take the LRC lead.
     */
    fun leadMsFor(rows: List<LyricsRenderRow>): Long =
        if (rows.isNotEmpty() && rows.all { it.words == null }) LRC_LEAD_MS else TTML_LEAD_MS

    fun feedMs(positionMs: Long, syncOffsetMs: Long, leadMs: Long): Long =
        positionMs + syncOffsetMs + leadMs + VISUAL_TUNING_OFFSET_MS

    /** AirPlay reports about two seconds; anything past this bound is a bad reading rather than a real route. */
    const val MAX_OUTPUT_LATENCY_MS = 3_000L

    /**
     * The sync offset that holds lyrics back until the audio is heard, for a host whose playback position is the
     * rendered position rather than the presented one. [outputLatencySeconds] and [bufferSeconds] are the route's
     * reported output latency and I/O buffer; a missing or nonsensical value counts as none.
     */
    fun outputLatencyOffsetMs(outputLatencySeconds: Double, bufferSeconds: Double): Long {
        fun usable(seconds: Double): Double = if (seconds.isFinite() && seconds > 0.0) seconds else 0.0
        val totalMs = ((usable(outputLatencySeconds) + usable(bufferSeconds)) * 1_000.0).roundToLong()
        return -(totalMs.coerceAtMost(MAX_OUTPUT_LATENCY_MS))
    }
}

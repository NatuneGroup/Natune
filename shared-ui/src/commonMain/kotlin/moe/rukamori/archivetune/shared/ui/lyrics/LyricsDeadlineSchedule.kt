package moe.rukamori.archivetune.shared.ui.lyrics

/**
 * When the lyric feed can change what a frame shows. While the feed sits between two of these
 * instants and inside no live span, the drawn state is static: no amount of re-computing moves a
 * highlight until the next word starts or the next row activates. The clock loop then sleeps to
 * the next boundary instead of requesting a frame every vsync — the idle fix's deadline rule.
 *
 * [liveStarts]/[liveEnds] are paired ascending spans where continuous motion runs (a word sweep in
 * flight, an interlude's breathing dots); [edges] are the instants where a discrete visual change
 * lands (a row activating or ending, a word ending, a span closing).
 */
internal class LyricsDeadlineSchedule(
    private val liveStarts: LongArray,
    private val liveEnds: LongArray,
    private val edges: LongArray,
) {
    /** True while [feedMs] sits inside a continuously animating span. */
    fun isLive(feedMs: Long): Boolean {
        val index = firstGreaterThan(liveEnds, feedMs)
        return index < liveEnds.size && liveStarts[index] <= feedMs
    }

    /** The earliest instant after [feedMs] at which the drawn state changes, or null past the last row. */
    fun nextChangeAfter(feedMs: Long): Long? {
        val edge = firstGreaterThan(edges, feedMs).let { if (it < edges.size) edges[it] else null }
        val live = firstGreaterThan(liveStarts, feedMs).let { if (it < liveStarts.size) liveStarts[it] else null }
        return when {
            edge == null -> live
            live == null -> edge
            else -> minOf(edge, live)
        }
    }

    private fun firstGreaterThan(values: LongArray, v: Long): Int {
        // LongArray.binarySearch's return shape differs across targets; an explicit upper-bound loop
        // stays Int-only on every platform.
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high).ushr(1)
            if (values[mid] <= v) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        /**
         * The schedule for [rows]: word sweeps (each running at least [minSweepMs]) and, with
         * [includeInterludes], the breathing interludes are the live spans; row activation and every
         * span boundary are the discrete edges.
         */
        fun of(rows: List<LyricsRenderRow>, minSweepMs: Long, includeInterludes: Boolean): LyricsDeadlineSchedule {
            val starts = ArrayList<Long>()
            val ends = ArrayList<Long>()
            val changes = ArrayList<Long>()
            fun span(start: Long, end: Long) {
                starts.add(start)
                ends.add(end)
                changes.add(start)
                changes.add(end)
            }
            for (row in rows) {
                // The visual change lands at activation, which the lead-in moves ahead of the timed start.
                changes.add(row.scanStartMs - row.activationLeadMs)
                changes.add(row.scanEndMs)
                for (words in listOfNotNull(row.words, row.backgroundWords)) {
                    for (word in words) {
                        span(word.startMs, maxOf(word.endMs, word.startMs + minSweepMs))
                    }
                }
            }
            if (includeInterludes) {
                for (interlude in LyricsInterludes.byRow(rows).values) {
                    span(interlude.startMs, interlude.endMs)
                }
            }
            return LyricsDeadlineSchedule(
                liveStarts = starts.sorted().toLongArray(),
                liveEnds = ends.sorted().toLongArray(),
                edges = changes.distinct().sorted().toLongArray(),
            )
        }
    }
}

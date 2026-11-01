package moe.rukamori.archivetune.lyrics

import kotlinx.coroutines.delay
import kotlin.math.roundToLong

/**
 * When the lyric feed can change what a frame shows. While the feed sits between two of these
 * instants and inside no live span, the drawn state is static: no amount of re-computing moves a
 * highlight until the next word starts or the next line activates. The position loop then sleeps
 * to the next boundary instead of requesting a frame every vsync — the idle-frame rule.
 *
 * [liveStarts]/[liveEnds] are paired ascending spans where continuous motion runs (a word sweep in
 * flight); [edges] are the instants where a discrete visual change lands (a line activating, a word
 * ending, a span closing). Word ends count as edges so waking out of a live span re-evaluates the
 * next boundary cleanly.
 */
class LyricsDeadlineSchedule(
    private val liveStarts: LongArray,
    private val liveEnds: LongArray,
    private val edges: LongArray,
) {
    /** True while [feedMs] sits inside a continuously animating span. */
    fun isLive(feedMs: Long): Boolean {
        val index = firstGreaterThan(liveEnds, feedMs)
        return index < liveEnds.size && liveStarts[index] <= feedMs
    }

    /** The earliest instant after [feedMs] at which the drawn state changes, or null past the last line. */
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
        var index = values.binarySearch(v)
        if (index < 0) index = index.inv()
        while (index < values.size && values[index] <= v) index++
        return index
    }

    companion object {
        /**
         * The schedule for [entries]: each word's timed span (stretched to at least [minSweepMs], the
         * same floor the sweep itself applies) animates continuously; every line time and word
         * boundary is a discrete edge.
         */
        fun of(entries: List<LyricsEntry>, minSweepMs: Long = 0L): LyricsDeadlineSchedule {
            val starts = ArrayList<Long>()
            val ends = ArrayList<Long>()
            val changes = ArrayList<Long>()
            for (entry in entries) {
                changes.add(entry.time)
                for (word in entry.words.orEmpty()) {
                    val start = (word.startTime * 1000.0).roundToLong()
                    val end = maxOf((word.endTime * 1000.0).roundToLong(), start + minSweepMs)
                    starts.add(start)
                    ends.add(end)
                    changes.add(start)
                    changes.add(end)
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

/**
 * Suspends until the next deadline boundary (bounded by [idlePollMs]) — the app renderers park
 * their position loop on this while the drawn state is static between a word's sweep end and the
 * next word/line edge.
 */
internal suspend fun LyricsDeadlineSchedule.sleepUntilNext(
    feedMs: Long,
    minSleepMs: Long = 1L,
    idlePollMs: Long = 250L,
) {
    val next = nextChangeAfter(feedMs)
    delay(if (next == null) idlePollMs else (next - feedMs).coerceIn(minSleepMs, idlePollMs))
}

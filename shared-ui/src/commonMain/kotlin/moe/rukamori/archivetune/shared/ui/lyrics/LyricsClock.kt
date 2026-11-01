package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.playback.SystemMonotonicClock
import kotlin.math.roundToLong

/**
 * The one lyric clock of a surface. It feeds [LyricsSmoothPosition] from playback snapshots and, while the frame
 * loop runs, advances it once per frame. Renderers read only [feedMs] and [discontinuities], so a frame writes one
 * state value and only the readers of that value redraw.
 */
@Stable
internal class LyricsClock {
    private val smooth = LyricsSmoothPosition()
    private var syncOffsetMs = 0L
    private var leadMs = 0L
    private var outputDelayMs = 0L

    /** The lyric feed position in milliseconds: playback position with the sync offset, lead and visual tuning. */
    val feedMs: MutableLongState = mutableLongStateOf(0L)

    /** Counts discontinuity resets. A change tells the scroll owner to jump rather than animate. */
    val discontinuities: MutableIntState = mutableIntStateOf(0)

    fun accept(snapshot: PlaybackSnapshot, nowNanos: Long): LyricsAnchorChange {
        val change = smooth.accept(snapshot, nowNanos)
        if (change != LyricsAnchorChange.STALE) {
            outputDelayMs = ((snapshot.outputLatencyMs ?: 0L).toDouble() * snapshot.playbackSpeed).roundToLong()
        }
        if (change == LyricsAnchorChange.RESET) {
            discontinuities.intValue += 1
        }
        publish(smooth.advance(nowNanos))
        return change
    }

    fun advance(nowNanos: Long) {
        publish(smooth.advance(nowNanos))
    }

    fun retime(syncOffsetMs: Long, leadMs: Long) {
        this.syncOffsetMs = syncOffsetMs
        this.leadMs = leadMs
        publish(smooth.positionMs)
    }

    private fun publish(positionMs: Long) {
        // Output delay is wall time; convert it at the media rate without changing row timestamps or tap-to-seek.
        val feed = LyricsFeedTiming.feedMs(positionMs, syncOffsetMs, leadMs)
        feedMs.longValue = if (feed < Long.MIN_VALUE + outputDelayMs) Long.MIN_VALUE else feed - outputDelayMs
    }
}

/**
 * Keeps [LyricsClock] current for one surface. A new snapshot is accepted once. The frame loop runs only when
 * [frameLoop] is true, which [LyricsFrameGate] decides from visibility, resume, motion and play state.
 *
 * With a [deadlines] schedule the loop requests a frame only while a span animates continuously;
 * between events it sleeps until the next boundary (bounded by [IDLE_POLL_MS] so a seek or a stale
 * anchor still lands within a quarter second) instead of drawing an unchanged frame every vsync.
 */
@Composable
internal fun rememberLyricsClock(
    snapshot: PlaybackSnapshot?,
    syncOffsetMs: Long,
    leadMs: Long,
    frameLoop: Boolean,
    deadlines: LyricsDeadlineSchedule? = null,
): LyricsClock {
    val clock = remember { LyricsClock() }
    LaunchedEffect(snapshot) {
        snapshot?.let { clock.accept(it, SystemMonotonicClock.nowNanos()) }
    }
    LaunchedEffect(syncOffsetMs, leadMs) {
        clock.retime(syncOffsetMs, leadMs)
    }
    LaunchedEffect(frameLoop, deadlines) {
        if (!frameLoop) return@LaunchedEffect
        while (true) {
            clock.advance(SystemMonotonicClock.nowNanos())
            if (deadlines != null && !deadlines.isLive(clock.feedMs.longValue)) {
                val next = deadlines.nextChangeAfter(clock.feedMs.longValue)
                delay(if (next == null) IDLE_POLL_MS else (next - clock.feedMs.longValue).coerceIn(1L, IDLE_POLL_MS))
            } else {
                withFrameNanos { clock.advance(SystemMonotonicClock.nowNanos()) }
            }
        }
    }
    return clock
}

/** The slowest the clock re-checks its feed while idle; matches the native surface's held-position poll. */
private const val IDLE_POLL_MS = 250L

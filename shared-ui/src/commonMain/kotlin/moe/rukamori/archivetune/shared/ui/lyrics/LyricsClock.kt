/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
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
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.playback.SystemMonotonicClock

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

    /** The lyric feed position in milliseconds: playback position with the sync offset, lead and visual tuning. */
    val feedMs: MutableLongState = mutableLongStateOf(0L)

    /** Counts discontinuity resets. A change tells the scroll owner to jump rather than animate. */
    val discontinuities: MutableIntState = mutableIntStateOf(0)

    fun accept(snapshot: PlaybackSnapshot, nowNanos: Long) {
        if (smooth.accept(snapshot, nowNanos) == LyricsAnchorChange.RESET) {
            discontinuities.intValue += 1
        }
        publish(smooth.advance(nowNanos))
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
        feedMs.longValue = LyricsFeedTiming.feedMs(positionMs, syncOffsetMs, leadMs)
    }
}

/**
 * Keeps [LyricsClock] current for one surface. A new snapshot is accepted once. The frame loop runs only when
 * [frameLoop] is true, which [LyricsFrameGate] decides from visibility, resume, motion and play state.
 */
@Composable
internal fun rememberLyricsClock(
    snapshot: PlaybackSnapshot?,
    syncOffsetMs: Long,
    leadMs: Long,
    frameLoop: Boolean,
): LyricsClock {
    val clock = remember { LyricsClock() }
    LaunchedEffect(snapshot) {
        snapshot?.let { clock.accept(it, SystemMonotonicClock.nowNanos()) }
    }
    LaunchedEffect(syncOffsetMs, leadMs) {
        clock.retime(syncOffsetMs, leadMs)
    }
    LaunchedEffect(frameLoop) {
        if (frameLoop) {
            while (true) {
                withFrameNanos { clock.advance(SystemMonotonicClock.nowNanos()) }
            }
        }
    }
    return clock
}

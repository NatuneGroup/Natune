package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The word-completion lift: how long a word rises, when that rise runs inside the word's own span,
 * and the height the renderer reads at a position — the pure math all three platforms share.
 */
class LyricsLiftTest {
    @Test
    fun theLiftTakesHalfTheWordInsideItsBounds() {
        // Half the word's own span, clamped to [LIFT_MIN_MS]..[LIFT_MAX_MS].
        assertEquals(
            LyricsSweepGeometry.LIFT_MAX_MS,
            LyricsSweepGeometry.liftDurationMs(1_000L, 2_000L, Long.MAX_VALUE),
        )
        assertEquals(200L, LyricsSweepGeometry.liftDurationMs(0L, 400L, Long.MAX_VALUE))
        assertEquals(
            LyricsSweepGeometry.LIFT_MIN_MS,
            LyricsSweepGeometry.liftDurationMs(0L, 100L, Long.MAX_VALUE),
        )
    }

    @Test
    fun theLiftKeepsItsFloorThroughATightHandOff() {
        // A 50 ms gap is too short for the rise, but the floor still applies so the word does not
        // snap into place — the next word sits low while this one finishes.
        assertEquals(
            LyricsSweepGeometry.LIFT_MIN_MS,
            LyricsSweepGeometry.liftDurationMs(0L, 300L, 350L),
        )
        // A comfortable gap leaves the half-span window alone.
        assertEquals(150L, LyricsSweepGeometry.liftDurationMs(0L, 300L, 800L))
    }

    @Test
    fun theLiftRunsInsideTheWordAndNeverBeforeItsStart() {
        // A one-second word rises through its last 300 ms, seated exactly at its end.
        assertEquals(1_700L, LyricsSweepGeometry.liftStartMs(1_000L, 2_000L, Long.MAX_VALUE))
        assertEquals(2_000L, LyricsSweepGeometry.liftEndMs(1_000L, 2_000L, Long.MAX_VALUE))
        // A word shorter than the window starts lifting at its own start and settles just past its end.
        assertEquals(0L, LyricsSweepGeometry.liftStartMs(0L, 100L, Long.MAX_VALUE))
        assertEquals(LyricsSweepGeometry.LIFT_MIN_MS, LyricsSweepGeometry.liftEndMs(0L, 100L, Long.MAX_VALUE))
    }

    @Test
    fun theRiseIsLowBeforeItRunsAndSeatedWhenItEnds() {
        val at = { position: Long -> LyricsSweepGeometry.liftAt(1_000L, 2_000L, Long.MAX_VALUE, position, false) }
        assertEquals(0f, at(0L))
        assertEquals(0f, at(1_700L))
        assertEquals(1f, at(2_000L))
        assertEquals(1f, at(5_000L))
        // Halfway through the window the ease-out is already most of the way up: 1 - (1 - 0.5)^3.
        assertEquals(0.875f, at(1_850L), 0.001f)
        // A quarter through: 1 - 0.75^3 — a seek to mid-rise lands on this frame, not a guess.
        assertEquals(0.578125f, at(1_775L), 0.001f)
    }

    @Test
    fun theRiseNeverFallsAsThePositionAdvances() {
        var highest = 0f
        for (position in 1_600L..2_100L step 25) {
            val share = LyricsSweepGeometry.liftAt(1_000L, 2_000L, Long.MAX_VALUE, position, false)
            assertTrue(share >= highest, "lift fell from $highest to $share at $position")
            highest = share
        }
        assertEquals(1f, highest)
    }

    @Test
    fun reducedMotionSitsEveryWordSeated() {
        // The lift is a completion detail; with reduced motion every word sits where it is sung.
        assertEquals(1f, LyricsSweepGeometry.liftAt(1_000L, 2_000L, Long.MAX_VALUE, 0L, true))
        assertEquals(1f, LyricsSweepGeometry.liftAt(1_000L, 2_000L, Long.MAX_VALUE, 1_500L, true))
    }

    @Test
    fun theFrameDeadlineStaysAwakeThroughTheLift() {
        // A word whose lift outlives its own span keeps the clock loop running until it settles,
        // or the rise would freeze mid-air — here with a zero minimum sweep so the lift is binding.
        val row = rowWithWord(startMs = 100L, endMs = 200L)
        val schedule = LyricsDeadlineSchedule.of(listOf(row), minSweepMs = 0L, includeInterludes = false)
        assertTrue(schedule.isLive(210L))
        assertFalse(schedule.isLive(230L))
        assertEquals(220L, schedule.nextChangeAfter(200L))
    }

    @Test
    fun theRowSinksIntoItsLowSeatJustBeforeItsFirstWord() {
        val first = 1_000L
        val sink = { position: Long -> LyricsSweepGeometry.sinkAt(first, position, false) }
        assertEquals(0f, sink(first - LyricsSweepGeometry.SINK_MS))
        assertEquals(0.5f, sink(first - LyricsSweepGeometry.SINK_MS / 2), 0.001f)
        assertEquals(1f, sink(first))
        assertEquals(1f, sink(first + 5_000L))
        // An inactive row draws seated; a row lit on even the shortest lead has not started sinking.
        assertTrue(LyricsSweepGeometry.SINK_MS <= LyricsActivation.LEAD_DEFAULT_MS)
        assertEquals(0f, LyricsSweepGeometry.sinkAt(first, first, true))
    }

    @Test
    fun theDropIsOneCurveFromSeatThroughTheLowWaitBackToSeat() {
        // A row of two words; the second one waits low until its own completion lift.
        val drop = { position: Long -> LyricsSweepGeometry.dropAt(1_000L, 1_500L, 2_500L, Long.MAX_VALUE, position, false) }
        assertEquals(0f, drop(0L))
        assertEquals(1f, drop(1_000L))
        assertEquals(1f, drop(2_200L))
        assertEquals(0f, drop(2_500L))
        var previous = drop(800L)
        for (position in 800L..1_000L step 10) {
            val now = drop(position)
            assertTrue(now >= previous, "the sink rose from $previous to $now at $position")
            previous = now
        }
        for (position in 1_000L..2_600L step 10) {
            val now = drop(position)
            assertTrue(now <= previous, "the word fell from $previous to $now at $position")
            assertTrue(previous - now < 0.2f, "the word jumped from $previous to $now at $position")
            previous = now
        }
        assertEquals(0f, LyricsSweepGeometry.dropAt(1_000L, 1_500L, 2_500L, Long.MAX_VALUE, 1_200L, true))
    }

    @Test
    fun theFrameDeadlineStaysAwakeThroughTheSink() {
        val schedule =
            LyricsDeadlineSchedule.of(listOf(rowWithWord(startMs = 1_000L, endMs = 2_000L)), minSweepMs = 0L, includeInterludes = false)
        assertFalse(schedule.isLive(1_000L - LyricsSweepGeometry.SINK_MS - 1L))
        assertTrue(schedule.isLive(1_000L - LyricsSweepGeometry.SINK_MS / 2))
        assertEquals(1_000L - LyricsSweepGeometry.SINK_MS, schedule.nextChangeAfter(0L))
    }

    private fun rowWithWord(startMs: Long, endMs: Long): LyricsRenderRow =
        LyricsRenderRow(
            index = 0,
            timeMs = 0L,
            scanStartMs = 0L,
            scanEndMs = 5_000L,
            text = "la",
            phonetic = null,
            translation = null,
            words = listOf(LyricsWordTiming(startMs = startMs, endMs = endMs, range = 0..1)),
            agent = null,
            isInstrumental = false,
            isSynced = true,
            isSecondVoice = false,
            isRtl = false,
        )
}

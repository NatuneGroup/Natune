package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The interlude dots: a 9s break, breathing, and the end-of-break swell toward the next line. */
class LyricsInterludeTest {
    private fun row(index: Int, start: Long, end: Long, instrumental: Boolean = false) =
        LyricsRenderRow(
            index = index, timeMs = start, scanStartMs = start, scanEndMs = end,
            text = "line $index", phonetic = null, translation = null, words = null,
            agent = null, isInstrumental = instrumental, isSynced = true, isSecondVoice = false, isRtl = false,
        )

    @Test
    fun onlyA9sSilenceOrLongerOwnsDots() {
        val short = listOf(row(0, 0, 1_000), row(1, 9_900, 10_000))
        val long = listOf(row(0, 0, 1_000), row(1, 10_000, 11_000))
        assertTrue(LyricsInterludes.byRow(short).isEmpty())
        assertEquals(LyricsInterlude(1_000, 10_000), LyricsInterludes.byRow(long)[1])
    }

    @Test
    fun theSwellRisesOverTheLast1500ms() {
        val interlude = LyricsInterlude(0L, 10_000L)
        // Outside its window the swell is a neutral factor of 1, so it can be multiplied into the breath
        // without a jump; the previous shape returned 0 out there and started at 1.05 inside, which made
        // the dots snap 5 % larger on the frame the window opened.
        assertEquals(1f, LyricsInterludeMotion.endSwell(interlude, 8_400), 0.001f)
        assertEquals(1f, LyricsInterludeMotion.endSwell(interlude, 8_500), 0.001f)
        val middle = LyricsInterludeMotion.endSwell(interlude, 9_250)
        assertTrue(
            middle > 1f && middle < LyricsInterludeMotion.END_SWELL_TO,
            "the swell eases through the window, got $middle",
        )
        assertEquals(
            LyricsInterludeMotion.END_SWELL_TO,
            LyricsInterludeMotion.endSwell(interlude, 9_999),
            0.01f,
        )
    }

    @Test
    fun theDotsScaleIsContinuousAcrossTheSwellWindow() {
        val interlude = LyricsInterlude(0L, 10_000L)
        var previous = LyricsInterludeMotion.scale(interlude, 0L, reduceMotion = false)
        // The break's own window, end excluded: the indicator fades out over its last 400 ms and draws nothing from
        // endMs on (pinned below), so the frames a jump could be seen on are the ones inside this loop.
        for (position in 0L until 10_000L step 16L) {
            val scale = LyricsInterludeMotion.scale(interlude, position, reduceMotion = false)
            assertTrue(
                abs(scale - previous) < 0.02f,
                "the dots jumped at $position: $previous -> $scale",
            )
            previous = scale
        }
        assertEquals(0f, LyricsInterludeMotion.visibility(interlude, 10_000L), 0.0001f)
    }

    @Test
    fun reducedMotionKeepsTheDotsAtTheirSeatedSize() {
        val interlude = LyricsInterlude(0L, 10_000L)
        assertEquals(1f, LyricsInterludeMotion.scale(interlude, 1_000L, reduceMotion = true), 0.0001f)
        // The end swell counts as motion: a reduced-motion indicator must not grow 1.3x into the next line.
        assertEquals(1f, LyricsInterludeMotion.scale(interlude, 9_999L, reduceMotion = true), 0.0001f)
    }
}

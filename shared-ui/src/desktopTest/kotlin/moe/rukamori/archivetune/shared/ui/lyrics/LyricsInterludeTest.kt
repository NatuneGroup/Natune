package moe.rukamori.archivetune.shared.ui.lyrics

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
        assertEquals(0f, LyricsInterludeMotion.endSwell(interlude, 8_400))
        assertEquals(LyricsInterludeMotion.END_SWELL_FROM, LyricsInterludeMotion.endSwell(interlude, 8_500), 0.001f)
        val middle = LyricsInterludeMotion.endSwell(interlude, 9_250)
        assertTrue(
            middle > LyricsInterludeMotion.END_SWELL_FROM && middle < LyricsInterludeMotion.END_SWELL_TO,
            "the swell lerps through the window, got $middle",
        )
        assertEquals(0f, LyricsInterludeMotion.endSwell(interlude, 10_000))
    }
}

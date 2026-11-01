package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

/** The activation lead: a fixed default outside the gap band, scaled inside it. */
class LyricsActivationTest {
    @Test
    fun theFirstLineAndLongSilencesGetTheDefaultLead() {
        assertEquals(LyricsActivation.LEAD_DEFAULT_MS, LyricsActivation.leadMs(null))
        assertEquals(LyricsActivation.LEAD_DEFAULT_MS, LyricsActivation.leadMs(0L))
        assertEquals(LyricsActivation.LEAD_DEFAULT_MS, LyricsActivation.leadMs(LyricsActivation.GAP_MIN_MS - 1))
        assertEquals(LyricsActivation.LEAD_DEFAULT_MS, LyricsActivation.leadMs(LyricsActivation.GAP_MAX_MS + 1))
        assertEquals(LyricsActivation.LEAD_DEFAULT_MS, LyricsActivation.leadMs(30_000L))
    }

    @Test
    fun insideTheBandTheLeadGrowsWithTheGap() {
        assertEquals(LyricsActivation.LEAD_SHORT_GAP_MS, LyricsActivation.leadMs(LyricsActivation.GAP_MIN_MS))
        assertEquals(LyricsActivation.LEAD_LONG_GAP_MS, LyricsActivation.leadMs(LyricsActivation.GAP_MAX_MS))
        val middle = LyricsActivation.leadMs((LyricsActivation.GAP_MIN_MS + LyricsActivation.GAP_MAX_MS) / 2)
        assertEquals(
            (LyricsActivation.LEAD_SHORT_GAP_MS + LyricsActivation.LEAD_LONG_GAP_MS) / 2,
            middle,
        )
    }

    @Test
    fun builtRowsCarryTheLeadFromTheSilenceBeforeThem() {
        val rows = LyricsRenderRows.build(
            listOf(
                karaoke(startMs = 1_000, first = "one", second = "line"),
                karaoke(startMs = 3_000, first = "two", second = "line"),
                karaoke(startMs = 20_000, first = "three", second = "line"),
            ),
        )
        // The first line gets the default lead; a 2s gap is past the band, so the second does too.
        assertEquals(250L, rows[0].activationLeadMs)
        assertEquals(250L, rows[1].activationLeadMs)
        // A 16s silence stays the default lead as well.
        assertEquals(250L, rows[2].activationLeadMs)
    }

    @Test
    fun aTightHandOffScalesTheLeadAndDuetVoicesLightTogether() {
        val rows = LyricsRenderRows.build(
            listOf(
                karaoke(startMs = 1_000, first = "one", second = "line"),
                karaoke(startMs = 2_300, first = "lead", second = "voice"), // 300ms gap, inside the band
                karaoke(startMs = 2_300, first = "back", second = "up", agent = "v2"), // duet on the same start
            ),
        )
        val expected = LyricsActivation.leadMs(300L)
        assertEquals(expected, rows[1].activationLeadMs)
        assertEquals(expected, rows[2].activationLeadMs)
        val scanner = LyricsRenderRows.scanner(rows)
        // Before the duet pair's shared activation only the first row is lit.
        assertEquals(listOf(0), scanner.activeAt(2_300 - expected - 1))
        assertEquals(listOf(0, 1, 2), scanner.activeAt(2_300 - expected))
    }
}

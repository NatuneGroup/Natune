package moe.rukamori.archivetune.ui.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player's window rules. The bug these pin down: a floating pop-up window reports itself as
 * PORTRAIT and used to be handed the layout of a full-height phone, so the artwork stage collapsed
 * and the controls were pushed out of the window.
 */
class PlayerWindowLayoutTest {
    /** The inner height of a phone in landscape — a "compact height" window by Material's rules. */
    private val phoneLandscapeDp = 411f

    /** A floating pop-up window: taller than wide, and short. */
    private val popupWidthDp = 420f
    private val popupHeightDp = 400f

    @Test
    fun aFullPhoneWindowIsRegular() {
        val atTheBreakpoint = PlayerWindowLayout.modeFor(PlayerWindowLayout.COMPACT_HEIGHT_DP)
        assertEquals(PlayerWindowMode.REGULAR, PlayerWindowLayout.modeFor(891f))
        assertEquals(PlayerWindowMode.REGULAR, atTheBreakpoint)
        assertFalse(PlayerWindowLayout.isCompact(891f))
    }

    @Test
    fun aPopupWindowIsCompactAndAVeryShortOneIsShort() {
        val justBelowTheShortBreakpoint = PlayerWindowLayout.SHORT_HEIGHT_DP - 10f
        assertEquals(PlayerWindowMode.COMPACT, PlayerWindowLayout.modeFor(popupHeightDp))
        assertEquals(PlayerWindowMode.COMPACT, PlayerWindowLayout.modeFor(phoneLandscapeDp))
        assertEquals(PlayerWindowMode.SHORT, PlayerWindowLayout.modeFor(320f))
        assertEquals(
            PlayerWindowMode.SHORT,
            PlayerWindowLayout.modeFor(justBelowTheShortBreakpoint),
        )
        assertTrue(PlayerWindowLayout.isCompact(popupHeightDp))
    }

    @Test
    fun theModeOnlyEverGrowsWithTheWindow() {
        var previous = PlayerWindowLayout.modeFor(0f)
        var height = 10f
        while (height <= 1_200f) {
            val mode = PlayerWindowLayout.modeFor(height)
            assertTrue("mode went backwards at " + height + " dp", mode.ordinal >= previous.ordinal)
            previous = mode
            height += 10f
        }
        assertEquals(PlayerWindowMode.REGULAR, previous)
    }

    @Test
    fun aTallPhoneStaysStacked() {
        // The portrait phone arrangement is not in question here — only short windows change.
        assertFalse(PlayerWindowLayout.usesWideArrangement(411f, 891f))
        assertFalse(PlayerWindowLayout.usesWideArrangement(600f, 900f))
    }

    @Test
    fun aLandscapeWindowStaysTwoPane() {
        assertTrue(PlayerWindowLayout.usesWideArrangement(891f, phoneLandscapeDp))
        assertTrue(PlayerWindowLayout.usesWideArrangement(1_200f, 500f))
    }

    @Test
    fun aShortWindowWideEnoughForTwoPanesGetsThem() {
        // 700x400 is "portrait" by configuration and used to get the tall stack; side by side it
        // has room for both halves. So does the narrowest window that is still worth splitting.
        val narrowestSplit = PlayerWindowLayout.SIDE_BY_SIDE_MIN_WIDTH_DP
        assertTrue(PlayerWindowLayout.usesWideArrangement(700f, 400f))
        assertTrue(PlayerWindowLayout.usesWideArrangement(500f, 300f))
        assertTrue(PlayerWindowLayout.usesWideArrangement(narrowestSplit, 400f))
        // Squarer than that: neither the aspect rule nor the short-window one applies.
        assertFalse(PlayerWindowLayout.usesWideArrangement(narrowestSplit - 20f, 460f))
    }

    @Test
    fun aNarrowPopupStaysStacked() {
        // Two 210 dp panes could not hold the transport row; the compact stack is the lesser evil,
        // and a pop-up this shape should keep opening on the artwork rather than on lyrics.
        assertFalse(PlayerWindowLayout.usesWideArrangement(popupWidthDp, popupHeightDp))
        assertFalse(PlayerWindowLayout.usesWideArrangement(320f, 300f))
        assertFalse(PlayerWindowLayout.usesWideArrangement(380f, 340f))
    }

    @Test
    fun theTwoPaneArtworkNeverOverflowsItsPane() {
        for (width in listOf(400f, 420f, 600f, 891f, 1_280f)) {
            for (height in listOf(300f, 360f, 411f, 480f, 800f)) {
                val artwork = PlayerWindowLayout.sideBySideArtworkSizeDp(width, height, 96f)
                val where = width.toString() + "x" + height
                assertTrue("artwork overflows the pane at " + where, artwork <= width / 2f)
                assertTrue("artwork overflows the window at " + where, artwork <= height)
            }
        }
    }

    @Test
    fun theTwoPaneArtworkGrowsWithTheWindowAndStopsAtZero() {
        val small = PlayerWindowLayout.sideBySideArtworkSizeDp(400f, 320f, 96f)
        val large = PlayerWindowLayout.sideBySideArtworkSizeDp(1_280f, 800f, 96f)
        assertTrue("small was " + small + " dp", small > 0f)
        assertTrue("large was " + large + " dp", large > small)
        // A pane with no room left reports zero rather than a negative size.
        assertEquals(0f, PlayerWindowLayout.sideBySideArtworkSizeDp(120f, 80f, 96f), 0.001f)
    }

    @Test
    fun theStackedArtworkNeverExceedsTheWindow() {
        for (height in listOf(240f, 300f, 360f, 400f, 480f, 900f)) {
            for (width in listOf(320f, 420f, 600f)) {
                val artwork = PlayerWindowLayout.stackedArtworkSizeDp(width, height)
                assertTrue("artwork too tall at " + width + "x" + height, artwork <= height)
                assertTrue("artwork too wide at " + width + "x" + height, artwork <= width)
            }
        }
    }

    @Test
    fun aShortWindowGivesTheControlsTheRoomTheTallOneDoesNotNeed() {
        // The stacked split is the other half of the same fix: on a 400 dp window the controls
        // block used to claim 43.7% of the height while needing far more than that.
        val tallGap = PlayerWindowLayout.controlsTopGapDp(891f)
        val compactGap = PlayerWindowLayout.controlsTopGapDp(popupHeightDp)
        val compactArtworkShare = PlayerWindowLayout.artworkStageFraction(popupHeightDp)
        val tallArtworkShare = PlayerWindowLayout.artworkStageFraction(891f)
        val compactControlsShare = PlayerWindowLayout.controlsStageFraction(popupHeightDp)
        val tallControlsShare = PlayerWindowLayout.controlsStageFraction(891f)
        assertTrue("the compact controls kept the tall gap", tallGap > compactGap)
        assertTrue(compactArtworkShare < tallArtworkShare)
        assertTrue(compactControlsShare > tallControlsShare)
        assertEquals(1.0f, compactArtworkShare + compactControlsShare, 0.06f)
    }

    @Test
    fun theVolumeRowOnlyGoesAwayWhenTheTransportNeedsTheRoom() {
        assertTrue(PlayerWindowLayout.showsVolumeRow(891f))
        assertTrue(PlayerWindowLayout.showsVolumeRow(phoneLandscapeDp))
        assertTrue(PlayerWindowLayout.showsVolumeRow(PlayerWindowLayout.SHORT_HEIGHT_DP))
        val veryShort = PlayerWindowLayout.SHORT_HEIGHT_DP - 1f
        assertFalse(PlayerWindowLayout.showsVolumeRow(veryShort))
    }

    @Test
    fun theLyricsListKeepsTheHeightTheShortHeaderGivesBack() {
        val tall = PlayerWindowLayout.lyricsTopInsetDp(891f)
        val compact = PlayerWindowLayout.lyricsTopInsetDp(popupHeightDp)
        assertTrue("compact inset was " + compact + " dp against " + tall + " dp", compact < tall)
        assertTrue("a compact window still needs a header", compact >= 48f)
    }

    @Test
    fun theStackedStagesAgreeWithTheIndividualRules() {
        for (height in listOf(240f, 360f, 400f, 479f, 480f, 891f)) {
            val stages = PlayerWindowLayout.stackedStages(height)
            assertEquals(
                PlayerWindowLayout.artworkStageFraction(height),
                stages.artworkFraction,
                0.001f,
            )
            assertEquals(
                PlayerWindowLayout.controlsStageFraction(height),
                stages.controlsFraction,
                0.001f,
            )
            assertEquals(PlayerWindowLayout.controlsTopGapDp(height), stages.topGap.value, 0.001f)
        }
    }

    @Test
    fun theFittedArtworkIsACappedSquare() {
        // A stage that is wide but short gets a square of its height, never a wider-than-tall slot.
        val short = PlayerWindowLayout.fittedArtwork(400.dp, 300.dp)
        assertEquals(300f * PlayerWindowLayout.artworkStageFraction(300f), short.value, 0.001f)
        // A tall stage gets its full width.
        val tall = PlayerWindowLayout.fittedArtwork(400.dp, 900.dp)
        assertEquals(400f, tall.value, 0.001f)
    }
}

package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.flow.MutableStateFlow
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine
import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.LyricsPhonetics
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fullscreen looks and compact strips in a composed tree. Each test drives the real renderer: rows, taps,
 * selection, the manual-scroll hold, seeks, reduced motion, strips and asynchronous romanization.
 */
@OptIn(ExperimentalTestApi::class)
class LyricsModeComposeTest {
    @Test
    fun everyFullscreenLookRendersItsRowsWithBothVoicesOfADuet() = runComposeUiTest {
        val playback = MutableStateFlow<PlaybackSnapshot?>(testSnapshot(positionMs = 1_200, sampleMonotonicNanos = nanos(1)))
        val listState = LazyListState()
        var mode by mutableStateOf(LyricsMode.V2)
        setContent {
            FullscreenLyricsHarness(
                lines = duetLines(),
                playback = playback,
                romanization = offRomanization(),
                settings = LyricsSurfaceSettings(mode = mode),
                actions = noopLyricsActions(),
                listState = listState,
            )
        }

        for (candidate in LyricsMode.entries) {
            mode = candidate
            waitForIdle()
            onNodeWithText("hello world").assertExists()
            onNodeWithText("back up").assertExists()
            onNodeWithText("closing line").assertExists()
        }
    }

    @Test
    fun aTapSeeksToATimedRowOnlyWhileTapToSeekIsOn() = runComposeUiTest {
        val playback = MutableStateFlow<PlaybackSnapshot?>(testSnapshot(positionMs = 0, sampleMonotonicNanos = nanos(1)))
        val listState = LazyListState()
        val seeks = mutableListOf<Long>()
        var tapToSeek by mutableStateOf(true)
        setContent {
            FullscreenLyricsHarness(
                lines = timedLines(8),
                playback = playback,
                romanization = offRomanization(),
                settings = LyricsSurfaceSettings(mode = LyricsMode.ENHANCED, tapToSeek = tapToSeek),
                actions = LyricsSurfaceActions(onSeek = { seeks += it }, onShareCard = {}),
                listState = listState,
            )
        }

        waitForIdle()
        onNodeWithTag(lyricsRowTag(3)).performClick()
        waitForIdle()
        assertEquals(listOf(3_000L), seeks)

        tapToSeek = false
        waitForIdle()
        onNodeWithTag(lyricsRowTag(5)).performClick()
        waitForIdle()
        assertEquals(listOf(3_000L), seeks, "a tap with tap-to-seek off must not seek")
    }

    @Test
    fun longPressSelectsLinesWhileTapsToggleThemAndShareHandsOneCardToTheHost() = runComposeUiTest {
        val playback = MutableStateFlow<PlaybackSnapshot?>(testSnapshot(positionMs = 0, sampleMonotonicNanos = nanos(1)))
        val listState = LazyListState()
        val seeks = mutableListOf<Long>()
        val shared = mutableListOf<ImageBitmap>()
        setContent {
            FullscreenLyricsHarness(
                lines = timedLines(12),
                playback = playback,
                romanization = offRomanization(),
                settings = LyricsSurfaceSettings(mode = LyricsMode.ENHANCED),
                actions = LyricsSurfaceActions(onSeek = { seeks += it }, onShareCard = { shared += it }),
                listState = listState,
            )
        }

        waitForIdle()
        onNodeWithTag(lyricsRowTag(1)).performTouchInput { longClick() }
        onNodeWithTag(lyricsRowTag(2)).performTouchInput { longClick() }
        waitForIdle()
        onNodeWithText("2 selected").assertIsDisplayed()

        onNodeWithTag(lyricsRowTag(3)).performClick()
        waitForIdle()
        onNodeWithText("3 selected").assertIsDisplayed()
        assertTrue(seeks.isEmpty(), "a tap while selecting toggles the line instead of seeking")

        onNodeWithText("Share").performClick()
        waitForIdle()
        assertEquals(1, shared.size, "share hands exactly one card to the host")
        assertTrue(shared.single().width > 0 && shared.single().height > 0, "the card has a size")

        onNodeWithText("Clear").performClick()
        waitForIdle()
        onNodeWithText("3 selected").assertDoesNotExist()
    }

    @Test
    fun reducedMotionHoldsAWordStillWhileMotionSweepsIt() = runComposeUiTest {
        val playback = MutableStateFlow<PlaybackSnapshot?>(testSnapshot(positionMs = 1_100, sampleMonotonicNanos = nanos(1)))
        val listState = LazyListState()
        var reduceMotion by mutableStateOf(true)
        setContent {
            FullscreenLyricsHarness(
                lines = listOf(karaoke(startMs = 1_000, first = "sung", second = "words")),
                playback = playback,
                romanization = offRomanization(),
                settings = LyricsSurfaceSettings(mode = LyricsMode.ENHANCED, reduceMotion = reduceMotion),
                actions = noopLyricsActions(),
                listState = listState,
            )
        }

        waitForIdle()
        val reducedStart = onRoot().captureToImage().toAwtImage()
        playback.value = testSnapshot(positionMs = 1_200, sampleMonotonicNanos = nanos(2))
        waitForIdle()
        val reducedLater = onRoot().captureToImage().toAwtImage()
        assertTrue(sameImage(reducedStart, reducedLater), "reduced motion must not change the frame inside one word")

        reduceMotion = false
        waitForIdle()
        val motionStart = onRoot().captureToImage().toAwtImage()
        playback.value = testSnapshot(positionMs = 1_400, sampleMonotonicNanos = nanos(3))
        waitForIdle()
        val motionLater = onRoot().captureToImage().toAwtImage()
        assertFalse(sameImage(motionStart, motionLater), "motion must sweep the word as the position moves")
    }

    @Test
    fun manualScrollHoldsAutoFollowForFourAndAHalfSecondsThenReturnsToThePlayhead() = runComposeUiTest {
        val playback = MutableStateFlow<PlaybackSnapshot?>(testSnapshot(positionMs = 0, sampleMonotonicNanos = nanos(1)))
        val listState = LazyListState()
        setContent {
            FullscreenLyricsHarness(
                lines = timedLines(60),
                playback = playback,
                romanization = offRomanization(),
                settings = LyricsSurfaceSettings(mode = LyricsMode.ENHANCED),
                actions = noopLyricsActions(),
                listState = listState,
            )
        }

        waitForIdle()
        mainClock.autoAdvance = false
        fun advanceTo(timeMs: Long) = mainClock.advanceTimeBy(timeMs - mainClock.currentTime)

        val swipeAt = mainClock.currentTime
        onNodeWithTag(LYRICS_LIST_TAG).performTouchInput { swipeUp() }
        waitForIdle()
        assertTrue(listState.firstVisibleItemIndex > 0, "the swipe must scroll the list away from the playhead")

        playback.value = testSnapshot(positionMs = 45_000, sampleMonotonicNanos = nanos(2))
        waitForIdle()
        advanceTo(swipeAt + 2_500)
        waitForIdle()
        assertFalse(isRowVisible(listState, 45), "auto-follow must hold during the manual-scroll window")

        advanceTo(swipeAt + 4_400)
        waitForIdle()
        assertFalse(isRowVisible(listState, 45), "auto-follow must still hold just before the hold expires")

        advanceTo(swipeAt + 5_600)
        waitForIdle()
        assertTrue(isRowVisible(listState, 45), "auto-follow must return to the playhead once the hold expires")
    }

    @Test
    fun v2HoldsAutoFollowForFourAndAHalfSecondsAfterItsSwipe() = runComposeUiTest {
        assertSwipeHoldExpires(LyricsMode.V2, holdMs = 4_500L)
    }

    @Test
    fun spotifyHoldsAutoFollowForFourAndAHalfSecondsAfterItsSwipe() = runComposeUiTest {
        assertSwipeHoldExpires(LyricsMode.SPOTIFY, holdMs = 4_500L)
    }

    @Test
    fun simpMusicHoldsAutoFollowForFourSecondsAfterItsSwipe() = runComposeUiTest {
        assertSwipeHoldExpires(LyricsMode.SIMPMUSIC, holdMs = 4_000L)
    }

    @Test
    fun simpMusicHoldStaysOpenWhileTheFingerRestsAndCountsFromTheLift() = runComposeUiTest {
        val playback = MutableStateFlow<PlaybackSnapshot?>(testSnapshot(positionMs = 0, sampleMonotonicNanos = nanos(1)))
        val listState = LazyListState()
        setManualScrollFixture(LyricsMode.SIMPMUSIC, playback, listState)

        waitForIdle()
        mainClock.autoAdvance = false
        onNodeWithTag(LYRICS_LIST_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, -300f))
        }
        waitForIdle()
        assertTrue(listState.firstVisibleItemIndex > 0, "the drag must scroll the list away from the playhead")
        playback.value = testSnapshot(positionMs = 45_000, sampleMonotonicNanos = nanos(2))
        waitForIdle()

        advanceClockTo(mainClock.currentTime + 6_000)
        waitForIdle()
        assertFalse(isRowVisible(listState, 45), "a finger still on the list must keep auto-follow suspended")

        onNodeWithTag(LYRICS_LIST_TAG).performTouchInput { up() }
        waitForIdle()
        val liftedAt = mainClock.currentTime
        advanceClockTo(liftedAt + 3_500)
        waitForIdle()
        assertFalse(isRowVisible(listState, 45), "the four-second hold runs from the lift, not from the drag start")

        advanceClockTo(liftedAt + 5_000)
        waitForIdle()
        assertTrue(isRowVisible(listState, 45), "auto-follow must return to the playhead four seconds after the lift")
    }

    @Test
    fun aSecondSimpMusicSwipeRestartsTheHoldFromItsLift() = runComposeUiTest {
        val playback = MutableStateFlow<PlaybackSnapshot?>(testSnapshot(positionMs = 0, sampleMonotonicNanos = nanos(1)))
        val listState = LazyListState()
        setManualScrollFixture(LyricsMode.SIMPMUSIC, playback, listState)

        waitForIdle()
        mainClock.autoAdvance = false
        onNodeWithTag(LYRICS_LIST_TAG).performTouchInput { swipeUp() }
        waitForIdle()
        val firstLift = mainClock.currentTime
        playback.value = testSnapshot(positionMs = 45_000, sampleMonotonicNanos = nanos(2))
        waitForIdle()

        advanceClockTo(firstLift + 3_000)
        onNodeWithTag(LYRICS_LIST_TAG).performTouchInput { swipeUp() }
        waitForIdle()
        val secondLift = mainClock.currentTime
        advanceClockTo(secondLift + 3_500)
        waitForIdle()
        assertFalse(isRowVisible(listState, 45), "a second swipe inside the hold must restart it from its own lift")

        advanceClockTo(secondLift + 5_000)
        waitForIdle()
        assertTrue(isRowVisible(listState, 45), "auto-follow must return once the restarted hold expires")
    }

    @Test
    fun aSeekEndsASimpMusicHoldAtOnceWithoutRemountingTheList() = runComposeUiTest {
        val playback = MutableStateFlow<PlaybackSnapshot?>(testSnapshot(positionMs = 0, sampleMonotonicNanos = nanos(1)))
        val listState = LazyListState()
        setManualScrollFixture(LyricsMode.SIMPMUSIC, playback, listState)

        waitForIdle()
        mainClock.autoAdvance = false
        val listNode = onNodeWithTag(LYRICS_LIST_TAG).fetchSemanticsNode().id
        onNodeWithTag(LYRICS_LIST_TAG).performTouchInput { swipeUp() }
        waitForIdle()
        val liftedAt = mainClock.currentTime
        advanceClockTo(liftedAt + 1_000)
        playback.value = testSnapshot(
            positionMs = 30_000,
            sampleMonotonicNanos = nanos(2),
            discontinuityGeneration = 1,
        )
        // The seek is applied on the next frame, so the clock must move before the list is read.
        advanceClockTo(liftedAt + 1_100)
        waitForIdle()

        assertTrue(isRowVisible(listState, 30), "a seek jumps the list at once, even inside the SimpMusic hold")
        assertEquals(
            listNode,
            onNodeWithTag(LYRICS_LIST_TAG).fetchSemanticsNode().id,
            "the list keeps its node across a seek",
        )
    }

    @Test
    fun aLookChangeKeepsTheListAndTheNextSwipeUsesTheNewLooksHold() = runComposeUiTest {
        val playback = MutableStateFlow<PlaybackSnapshot?>(testSnapshot(positionMs = 0, sampleMonotonicNanos = nanos(1)))
        val listState = LazyListState()
        var mode by mutableStateOf(LyricsMode.ENHANCED)
        setContent {
            FullscreenLyricsHarness(
                lines = timedLines(60),
                playback = playback,
                romanization = offRomanization(),
                settings = LyricsSurfaceSettings(mode = mode),
                actions = noopLyricsActions(),
                listState = listState,
            )
        }

        waitForIdle()
        mainClock.autoAdvance = false
        val listNode = onNodeWithTag(LYRICS_LIST_TAG).fetchSemanticsNode().id
        onNodeWithTag(LYRICS_LIST_TAG).performTouchInput { swipeUp() }
        waitForIdle()
        val firstLift = mainClock.currentTime
        playback.value = testSnapshot(positionMs = 45_000, sampleMonotonicNanos = nanos(2))
        waitForIdle()
        advanceClockTo(firstLift + 4_400)
        waitForIdle()
        assertFalse(isRowVisible(listState, 45), "ENHANCED must still hold just before its 4.5-second hold expires")

        advanceClockTo(firstLift + 5_600)
        waitForIdle()
        assertTrue(isRowVisible(listState, 45), "ENHANCED must resume after its 4.5-second hold")

        mode = LyricsMode.SIMPMUSIC
        waitForIdle()
        assertEquals(listNode, onNodeWithTag(LYRICS_LIST_TAG).fetchSemanticsNode().id, "a look change keeps the list")

        onNodeWithTag(LYRICS_LIST_TAG).performTouchInput { swipeUp() }
        waitForIdle()
        val secondLift = mainClock.currentTime
        advanceClockTo(secondLift + 3_500)
        waitForIdle()
        assertFalse(isRowVisible(listState, 45), "after a look change the next swipe holds for SIMPMUSIC's four seconds")

        advanceClockTo(secondLift + 5_000)
        waitForIdle()
        assertTrue(isRowVisible(listState, 45), "auto-follow must return once the SIMPMUSIC hold expires")
    }

    @Test
    fun aSeekJumpsTheListWithoutRemountingIt() = runComposeUiTest {
        val playback = MutableStateFlow<PlaybackSnapshot?>(testSnapshot(positionMs = 0, sampleMonotonicNanos = nanos(1)))
        val listState = LazyListState()
        setContent {
            FullscreenLyricsHarness(
                lines = timedLines(60),
                playback = playback,
                romanization = offRomanization(),
                settings = LyricsSurfaceSettings(mode = LyricsMode.ENHANCED),
                actions = noopLyricsActions(),
                listState = listState,
            )
        }

        waitForIdle()
        val listNode = onNodeWithTag(LYRICS_LIST_TAG).fetchSemanticsNode().id
        playback.value = testSnapshot(
            positionMs = 30_000,
            sampleMonotonicNanos = nanos(2),
            discontinuityGeneration = 1,
        )
        waitForIdle()

        assertTrue(isRowVisible(listState, 30), "a seek jumps the list to the new line at once")
        assertEquals(
            listNode,
            onNodeWithTag(LYRICS_LIST_TAG).fetchSemanticsNode().id,
            "the list keeps its node across a seek",
        )
    }

    @Test
    fun aPublishedReadingReplacesTheVisibleRowAndAnotherSessionIsIgnored() = runComposeUiTest {
        val romanization = LyricsRomanizationState(LyricsRomanizationPresentation.off(TEST_SESSION))
        val playback = MutableStateFlow<PlaybackSnapshot?>(testSnapshot(positionMs = 0, sampleMonotonicNanos = nanos(1)))
        val listState = LazyListState()
        setContent {
            FullscreenLyricsHarness(
                lines = listOf(
                    SyncedLine(time = 1_000, text = "東京"),
                    SyncedLine(time = 3_000, text = "Hello"),
                ),
                playback = playback,
                romanization = romanization,
                settings = LyricsSurfaceSettings(mode = LyricsMode.V2),
                actions = noopLyricsActions(),
                listState = listState,
            )
        }

        waitForIdle()
        onNodeWithText("東京").assertIsDisplayed()
        onNodeWithText("Tokyo").assertDoesNotExist()

        romanization.publish(
            LyricsRomanizationPresentation(
                sessionKey = TEST_SESSION,
                source = LyricsRomanizationSource.AI,
                phonetics = listOf(LyricsPhonetics(line = "Tokyo"), null),
            ),
        )
        waitForIdle()
        onNodeWithText("Tokyo").assertIsDisplayed()

        romanization.publish(
            LyricsRomanizationPresentation(
                sessionKey = "another-track",
                source = LyricsRomanizationSource.AI,
                phonetics = listOf(LyricsPhonetics(line = "Wrong"), null),
            ),
        )
        waitForIdle()
        onNodeWithText("Wrong").assertDoesNotExist()

        romanization.publish(
            LyricsRomanizationPresentation(
                sessionKey = TEST_SESSION,
                source = LyricsRomanizationSource.AI,
                phonetics = listOf(null, LyricsPhonetics(line = "Kyoto")),
            ),
        )
        waitForIdle()
        onNodeWithText("Kyoto").assertIsDisplayed()
    }

    @Test
    fun compactStripsShowTheSungLineAndTapSeeksToIt() = runComposeUiTest {
        val rows = LyricsRenderRows.build(timedLines(4))
        val playback = MutableStateFlow<PlaybackSnapshot?>(testSnapshot(positionMs = 1_200, sampleMonotonicNanos = nanos(1)))
        val seeks = mutableListOf<Long>()
        var style by mutableStateOf(LyricsCompactStyle.BITCHORD)
        setContent {
            MaterialTheme {
                LyricsCompactStrip(
                    rows = rows,
                    playback = playback,
                    style = style,
                    actions = LyricsSurfaceActions(onSeek = { seeks += it }, onShareCard = {}),
                )
            }
        }

        waitForIdle()
        onNodeWithText("line 1").assertIsDisplayed()
        onNodeWithText("line 2").assertIsDisplayed()
        onNodeWithText("line 1").performTouchInput { click() }
        waitForIdle()
        assertEquals(listOf(1_000L), seeks)

        style = LyricsCompactStyle.SIMPMUSIC
        waitForIdle()
        onNodeWithText("line 1").assertIsDisplayed()
        onNodeWithText("line 2").assertDoesNotExist()
        onNodeWithText("line 1").performTouchInput { click() }
        waitForIdle()
        assertEquals(listOf(1_000L, 1_000L), seeks)
    }
}

private fun duetLines(): List<LyricsLine> = listOf(
    karaoke(startMs = 1_000, first = "hello", second = "world", agent = "v1"),
    karaoke(startMs = 2_000, first = "back", second = "up", agent = "v2"),
    SyncedLine(time = 4_000, text = "closing line"),
)

private fun isRowVisible(listState: LazyListState, index: Int): Boolean =
    listState.layoutInfo.visibleItemsInfo.any { it.index == index }

/** Moves the test clock to [timeMs]. Callers pass a time no earlier than the current one. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.advanceClockTo(timeMs: Long) {
    mainClock.advanceTimeBy(timeMs - mainClock.currentTime)
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.setManualScrollFixture(
    mode: LyricsMode,
    playback: MutableStateFlow<PlaybackSnapshot?>,
    listState: LazyListState,
) {
    setContent {
        FullscreenLyricsHarness(
            lines = timedLines(60),
            playback = playback,
            romanization = offRomanization(),
            settings = LyricsSurfaceSettings(mode = mode),
            actions = noopLyricsActions(),
            listState = listState,
        )
    }
}

/**
 * Swipes the list off the playhead, then moves the playhead. Auto-follow must still be held shortly before [holdMs]
 * has passed since the lift, and must have returned shortly after it.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.assertSwipeHoldExpires(mode: LyricsMode, holdMs: Long) {
    val playback = MutableStateFlow<PlaybackSnapshot?>(testSnapshot(positionMs = 0, sampleMonotonicNanos = nanos(1)))
    val listState = LazyListState()
    setManualScrollFixture(mode, playback, listState)

    waitForIdle()
    mainClock.autoAdvance = false
    onNodeWithTag(LYRICS_LIST_TAG).performTouchInput { swipeUp() }
    waitForIdle()
    val liftedAt = mainClock.currentTime
    assertTrue(listState.firstVisibleItemIndex > 0, "the swipe must scroll the list away from the playhead")
    playback.value = testSnapshot(positionMs = 45_000, sampleMonotonicNanos = nanos(2))
    waitForIdle()

    advanceClockTo(liftedAt + holdMs - 500)
    waitForIdle()
    assertFalse(isRowVisible(listState, 45), "$mode must hold auto-follow until $holdMs ms after the lift")

    advanceClockTo(liftedAt + holdMs + 1_000)
    waitForIdle()
    assertTrue(isRowVisible(listState, 45), "$mode must return to the playhead once its hold expires")
}

private fun sameImage(a: BufferedImage, b: BufferedImage): Boolean =
    a.width == b.width &&
        a.height == b.height &&
        a.getRGB(0, 0, a.width, a.height, null, 0, a.width)
            .contentEquals(b.getRGB(0, 0, b.width, b.height, null, 0, b.width))

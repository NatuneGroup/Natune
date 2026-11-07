package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pane keeps every line on screen while a line change glides, not only the sung one.
 *
 * A change moves the list at once and each row is drawn where it was, then eases into its place. The glide's
 * translation used to ride *inside* the row's depth layer, the layer that carries the look's softening, so every
 * frame of a change re-recorded a blurred layer. On the desktop that left the blurred layer unpainted: for the
 * frames right after a change every line but the sung one was gone, then the pane filled back in over about ten
 * frames — the flash seen on a real Linux desktop while the lyrics played. The translation now wraps the softened
 * layer, so a gliding row reuses its blurred one.
 *
 * The check is the pane's own pixels, because the glitch is a painted-layer one: the semantics tree still held
 * every row while the screen showed none of them.
 */
@OptIn(ExperimentalTestApi::class)
class LyricsLineChangeFlickerTest {
    @Test
    fun everyLineStaysDrawnWhileALineChangeGlides() = runComposeUiTest {
        val playback = MutableStateFlow<PlaybackSnapshot?>(
            testSnapshot(positionMs = FIRST_POSITION_MS, sampleMonotonicNanos = nanos(1)),
        )
        val listState = LazyListState()
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT).background(MaterialTheme.colorScheme.surface)) {
                    LyricsFullscreenRenderer(
                        lines = timedLines(LINE_COUNT, stepMs = LINE_MS),
                        sessionKey = TEST_SESSION,
                        playback = playback,
                        romanization = offRomanization(),
                        // The desktop look: ENHANCED with its softening, and the glide a line change takes.
                        settings = LyricsSurfaceSettings(mode = LyricsMode.ENHANCED, depthEffects = true),
                        actions = noopLyricsActions(),
                        listState = listState,
                    )
                }
            }
        }

        waitForIdle()
        mainClock.autoAdvance = false
        val listNode = onNodeWithTag(LYRICS_LIST_TAG).fetchSemanticsNode().id
        var position = FIRST_POSITION_MS
        var fewest = Int.MAX_VALUE
        var fewestAt = -1L
        repeat(FRAMES) { frame ->
            runOnIdle {
                playback.value = testSnapshot(
                    positionMs = position,
                    sampleMonotonicNanos = nanos((1 + frame).toLong()),
                )
            }
            mainClock.advanceTimeBy(FRAME_MS)
            waitForIdle()
            val drawn = drawnLines(onRoot().captureToImage().toAwtImage())
            if (drawn < fewest) {
                fewest = drawn
                fewestAt = position
            }
            position += FRAME_MS
        }

        assertEquals(
            listNode,
            onNodeWithTag(LYRICS_LIST_TAG).fetchSemanticsNode().id,
            "a line change must not recreate the list",
        )
        assertTrue(
            fewest >= FEWEST_LINES,
            "only $fewest line(s) were painted at ${fewestAt}ms, in a frame of the line change's glide: " +
                "the rows that are not sung stopped being drawn",
        )
    }

    /** The same lines played at the same size must draw every line at every playback setting the pane offers. */
    @Test
    fun everyLookKeepsItsLinesThroughALineChange() = runComposeUiTest {
        var mode by mutableStateOf(LyricsMode.ENHANCED)
        var depth by mutableStateOf(true)
        val playback = MutableStateFlow<PlaybackSnapshot?>(
            testSnapshot(positionMs = FIRST_POSITION_MS, sampleMonotonicNanos = nanos(1)),
        )
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT).background(MaterialTheme.colorScheme.surface)) {
                    LyricsFullscreenRenderer(
                        lines = timedLines(LINE_COUNT, stepMs = LINE_MS),
                        sessionKey = TEST_SESSION,
                        playback = playback,
                        romanization = offRomanization(),
                        settings = LyricsSurfaceSettings(mode = mode, depthEffects = depth),
                        actions = noopLyricsActions(),
                    )
                }
            }
        }

        waitForIdle()
        mainClock.autoAdvance = false
        for (look in LyricsMode.entries) {
            for (withDepth in listOf(true, false)) {
                runOnIdle {
                    mode = look
                    depth = withDepth
                    playback.value = testSnapshot(positionMs = FIRST_POSITION_MS, sampleMonotonicNanos = nanos(1))
                }
                waitForIdle()
                var position = FIRST_POSITION_MS
                var fewest = Int.MAX_VALUE
                repeat(FRAMES) { frame ->
                    runOnIdle {
                        playback.value = testSnapshot(
                            positionMs = position,
                            sampleMonotonicNanos = nanos((1 + frame).toLong()),
                        )
                    }
                    mainClock.advanceTimeBy(FRAME_MS)
                    waitForIdle()
                    fewest = minOf(fewest, drawnLines(onRoot().captureToImage().toAwtImage()))
                    position += FRAME_MS
                }
                assertTrue(
                    fewest >= FEWEST_LINES,
                    "$look with depth=$withDepth painted only $fewest line(s) in a frame of a line change",
                )
            }
        }
    }
}

private val VIEWPORT_WIDTH = 420.dp
private val VIEWPORT_HEIGHT = 760.dp

/** The list is scrolled well past its first line, so a line change really moves it. */
private const val FIRST_POSITION_MS = 20_000L
private const val LINE_MS = 1_500L
private const val LINE_COUNT = 40
private const val FRAMES = 40
private const val FRAME_MS = 16L

/** A frame with fewer painted lines than this is the flash: the pane keeps about a dozen rows in view. */
private const val FEWEST_LINES = 8

/** How far a sampled pixel must sit from the pane's background to count as ink. */
private const val INK_DISTANCE = 12

/**
 * The number of lyric rows painted in [image]: spans of rows holding ink, measured against the empty corner of
 * the pane. A frame that lost the rows it is not singing has one span; a whole pane has a dozen.
 */
internal fun drawnLines(image: BufferedImage): Int {
    val background = image.getRGB(image.width - 2, image.height - 2) and 0xFFFFFF
    var spans = 0
    var inSpan = false
    for (y in 0 until image.height) {
        var ink = 0
        for (x in 0 until image.width) {
            val rgb = image.getRGB(x, y) and 0xFFFFFF
            val distance = abs((rgb shr 16 and 0xFF) - (background shr 16 and 0xFF)) +
                abs((rgb shr 8 and 0xFF) - (background shr 8 and 0xFF)) +
                abs((rgb and 0xFF) - (background and 0xFF))
            if (distance > INK_DISTANCE) ink++
        }
        if (ink > 2) {
            if (!inSpan) spans++
            inSpan = true
        } else {
            inSpan = false
        }
    }
    return spans
}

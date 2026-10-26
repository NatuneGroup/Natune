/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.flow.MutableStateFlow
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine
import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.LyricsPhonetics
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Writes the desktop screenshots of the fullscreen looks. A duet is on screen mid-word, so the sweep, the glow,
 * both voices and the readings all appear in each capture.
 */
@OptIn(ExperimentalTestApi::class)
class LyricsEnhancedScreenshotTest {
    @Test
    fun enhancedLookWritesTheDesktopScreenshot() = writeLookScreenshot(LyricsMode.ENHANCED, "lyrics-enhanced-desktop.png")

    @Test
    fun v2LookWritesTheDesktopScreenshot() = writeLookScreenshot(LyricsMode.V2, "lyrics-v2-desktop.png")

    @Test
    fun spotifyLookWritesTheDesktopScreenshot() = writeLookScreenshot(LyricsMode.SPOTIFY, "lyrics-spotify-desktop.png")

    @Test
    fun simpMusicLookWritesTheDesktopScreenshot() =
        writeLookScreenshot(LyricsMode.SIMPMUSIC, "lyrics-simpmusic-desktop.png")

    private fun writeLookScreenshot(mode: LyricsMode, name: String) = runComposeUiTest {
        val playback = MutableStateFlow<PlaybackSnapshot?>(testSnapshot(positionMs = 2_250, sampleMonotonicNanos = nanos(1)))
        val listState = LazyListState()
        val romanization = LyricsRomanizationState(
            LyricsRomanizationPresentation(
                sessionKey = TEST_SESSION,
                source = LyricsRomanizationSource.BUILT_IN,
                phonetics = listOf(null, LyricsPhonetics(line = "sahng fuhrst"), LyricsPhonetics(line = "an-sar-ing voys"), null),
            ),
        )
        setContent {
            FullscreenLyricsHarness(
                lines = screenshotLines(),
                playback = playback,
                romanization = romanization,
                settings = LyricsSurfaceSettings(mode = mode),
                actions = noopLyricsActions(),
                listState = listState,
            )
        }

        waitForIdle()
        val image = onRoot().captureToImage().toAwtImage()

        assertTrue(distinctColors(image) > 1, "$name rendered as one flat colour")
        writeLyricsScreenshot(image, name)
    }
}

private fun screenshotLines(): List<LyricsLine> = listOf(
    SyncedLine(time = 0, text = "The room is quiet"),
    karaoke(startMs = 2_000, first = "sung", second = "first", wordMs = 500, agent = "v1"),
    karaoke(startMs = 2_000, first = "answering", second = "voice", wordMs = 500, agent = "v2"),
    SyncedLine(time = 5_000, text = "Closing line"),
)

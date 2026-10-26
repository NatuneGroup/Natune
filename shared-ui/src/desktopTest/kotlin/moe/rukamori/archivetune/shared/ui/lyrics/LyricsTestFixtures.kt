/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import moe.rukamori.archivetune.shared.domain.lyrics.KaraokeLine
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsWord
import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

internal const val TEST_SESSION = "session-1"

internal fun nanos(ms: Long): Long = ms * 1_000_000L

/** A measurer that works outside a composition, for the shaping tests. */
internal fun testTextMeasurer(): TextMeasurer =
    TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr, 8)

internal fun lyricsMainStyleForTests(): TextStyle =
    lyricsMainStyle(LyricsModeProfile.of(LyricsMode.ENHANCED), LyricsLineAlignment.START)

/** A snapshot at [positionMs]. A snapshot that is not advancing holds its position exactly, which keeps tests exact. */
internal fun testSnapshot(
    positionMs: Long,
    sampleMonotonicNanos: Long = 0L,
    advancing: Boolean = false,
    buffering: Boolean = false,
    playbackSpeed: Float = 1f,
    playbackGeneration: Long = 1L,
    discontinuityGeneration: Long = 0L,
    occurrenceId: String = "occurrence-1",
    outputRoute: PlaybackOutputRoute = PlaybackOutputRoute.LOCAL,
    durationMs: Long? = 600_000L,
): PlaybackSnapshot =
    PlaybackSnapshot(
        occurrenceId = occurrenceId,
        mediaId = "media-1",
        playbackGeneration = playbackGeneration,
        discontinuityGeneration = discontinuityGeneration,
        positionMs = positionMs,
        sampleMonotonicNanos = sampleMonotonicNanos,
        durationMs = durationMs,
        isSeekable = true,
        playbackSpeed = playbackSpeed,
        playWhenReady = true,
        isAudioAdvancing = advancing,
        isBuffering = buffering,
        outputRoute = outputRoute,
    )

/** Line-only (LRC style) rows, one every [stepMs]. */
internal fun timedLines(count: Int, stepMs: Long = 1_000L, startMs: Long = 0L): List<LyricsLine> =
    (0 until count).map { index -> SyncedLine(time = startMs + index * stepMs, text = "line $index") }

/** A karaoke row with two words, each [wordMs] long, starting at [startMs]. */
internal fun karaoke(
    startMs: Long,
    first: String,
    second: String,
    wordMs: Long = 500L,
    agent: String? = null,
): KaraokeLine {
    val words = listOf(
        LyricsWord(first, startMs / 1_000.0, (startMs + wordMs) / 1_000.0),
        LyricsWord(second, (startMs + wordMs) / 1_000.0, (startMs + 2 * wordMs) / 1_000.0),
    )
    return KaraokeLine(
        time = startMs,
        text = "$first $second",
        agent = agent,
        words = words,
    )
}

/** A screenshot writer shared by the lyric desktop tests. The directory comes from the Gradle test task. */
internal fun writeLyricsScreenshot(image: BufferedImage, name: String): File {
    val directory = File(requireNotNull(System.getProperty(SCREENSHOT_DIR_PROPERTY)))
    directory.mkdirs()
    val target = File(directory, name)
    assertTrue(ImageIO.write(image, "png", target), "no PNG writer for ${target.path}")
    assertTrue(target.length() > 0L, "empty screenshot at ${target.path}")
    return target
}

/** The number of distinct sampled colours. A flat or blank capture has one. */
internal fun distinctColors(image: BufferedImage): Int {
    val colors = HashSet<Int>()
    for (y in 0 until image.height step SAMPLE_STEP) {
        for (x in 0 until image.width step SAMPLE_STEP) {
            colors += image.getRGB(x, y)
        }
    }
    return colors.size
}

private const val SCREENSHOT_DIR_PROPERTY = "archivetune.screenshotDir"
private const val SAMPLE_STEP = 4

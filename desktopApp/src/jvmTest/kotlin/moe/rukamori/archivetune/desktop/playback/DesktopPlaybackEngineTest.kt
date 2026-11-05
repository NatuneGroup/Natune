package moe.rukamori.archivetune.desktop.playback

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.data.playback.EnginePlaybackState

/**
 * The native engine in manual output mode: the test pulls every frame, so positions and samples
 * are deterministic and do not depend on an audio device.
 */
class DesktopPlaybackEngineTest {
    @Test
    fun manualWavPlaybackRendersTheRampFromTheStart() {
        openManualEngine().use { engine ->
            assertTrue(engine.load(PlaybackFixtures.localPath("ramp-48k.wav"), 0L, playWhenReady = true) > 0L)

            assertRampAt(index = 0L, samples = engine.renderLeft(FRAMES_PER_100_MS), context = "wav start")
        }
    }

    @Test
    fun losslessSeeksLandOnTheRequestedSample() {
        for (fixture in listOf("ramp-48k.wav", "ramp-48k.flac")) {
            openManualEngine().use { engine ->
                engine.load(PlaybackFixtures.localPath(fixture), 0L, playWhenReady = true)
                engine.renderLeft(FRAMES_PER_100_MS)

                for ((seekCount, targetUs) in listOf(2_000_000L, 1_250_000L, 3_500_000L).withIndex()) {
                    engine.seek(targetUs)
                    val expectedFrame = targetUs * PlaybackFixtures.RAMP_SAMPLE_RATE / MICROS_PER_SECOND
                    assertRampAt(expectedFrame, engine.renderLeft(FRAMES_PER_100_MS), "$fixture seek to $targetUs us")
                    assertEquals(seekCount + 1L, engine.snapshot().seekGeneration, "$fixture seek count")
                }
            }
        }
    }

    @Test
    fun fractionalSeekTargetLandsWithinOneFrame() {
        openManualEngine().use { engine ->
            engine.load(PlaybackFixtures.localPath("ramp-48k.wav"), 0L, playWhenReady = true)
            engine.renderLeft(FRAMES_PER_100_MS)

            engine.seek(333_333L)
            val targetFrame = 333_333.0 * PlaybackFixtures.RAMP_SAMPLE_RATE / MICROS_PER_SECOND
            assertRampStartsNear(targetFrame, engine.renderLeft(FRAMES_PER_100_MS))
        }
    }

    @Test
    fun pauseHoldsThePositionAndPlayResumesFromIt() {
        openManualEngine().use { engine ->
            engine.load(PlaybackFixtures.localPath("ramp-48k.wav"), 0L, playWhenReady = true)
            engine.renderLeft(FRAMES_PER_100_MS * 5)

            engine.pause()
            val silence = engine.renderLeft(FRAMES_PER_100_MS * 5)
            val paused = engine.snapshot()

            assertTrue(silence.all { it == 0f }, "a paused engine must render silence")
            assertFalse(paused.isAdvancing, "a paused engine must not report advancing audio")
            val pausedAt = paused.positionUs

            engine.play()
            engine.renderLeft(FRAMES_PER_100_MS * 5)
            val advanced = engine.snapshot().positionUs - pausedAt
            assertTrue(abs(advanced - FIVE_HUNDRED_MS_US) <= POSITION_TOLERANCE_US, "advanced $advanced us after resume")
        }
    }

    @Test
    fun speedChangeScalesMediaTimeOnceTheQueuedAudioHasPlayed() {
        openManualEngine().use { engine ->
            engine.load(PlaybackFixtures.localPath("ramp-48k.wav"), 0L, playWhenReady = true)
            engine.renderLeft(FRAMES_PER_100_MS)

            engine.setSpeed(2f)
            assertEquals(2f, engine.snapshot().playbackSpeed)
            // The ring holds up to two seconds rendered at the old speed. Draining exactly that much
            // first makes the measured window reflect the new speed alone. A longer drain at double
            // speed would run past the four-second fixture and end the playback before the window.
            engine.renderLeft(FRAMES_PER_SECOND * 2)
            val windowStart = engine.snapshot().positionUs
            engine.renderLeft(FRAMES_PER_SECOND / 2)
            val advanced = engine.snapshot().positionUs - windowStart

            // Half a second of output at 2x covers one second of media.
            assertTrue(abs(advanced - MICROS_PER_SECOND) <= 100_000L, "half a second at 2x advanced $advanced us")
        }
    }

    @Test
    fun lossyFilesReportTheirDurationAndSeekToTheRequestedPosition() {
        for (fixture in listOf("ramp-44k1.mp3", "ramp-48k.ogg")) {
            openManualEngine().use { engine ->
                engine.load(PlaybackFixtures.localPath(fixture), 0L, playWhenReady = true)
                val durationUs = awaitValue("the duration of $fixture") {
                    engine.snapshot().durationUs.takeIf { it >= 0L }
                }
                val expectedUs = PlaybackFixtures.RAMP_DURATION_MS * MICROS_PER_MILLI
                assertTrue(abs(durationUs - expectedUs) <= 100_000L, "$fixture duration $durationUs us")
                engine.renderLeft(FRAMES_PER_100_MS)

                engine.seek(2 * MICROS_PER_SECOND)
                assertEquals(2 * MICROS_PER_SECOND, engine.snapshot().positionUs, "$fixture position after seek")
                engine.renderLeft(FRAMES_PER_100_MS * 5)
                val after = engine.snapshot().positionUs
                assertTrue(abs(after - 2_500_000L) <= 50_000L, "$fixture advanced to $after us after seeking to 2 s")
            }
        }
    }

    @Test
    fun missingFileReportsAnOpenErrorWithTheEngineMessage() {
        openManualEngine().use { engine ->
            engine.load("/nonexistent/archivetune/ramp.wav", 0L, playWhenReady = true)

            val failed = awaitValue("an open error") { engine.snapshot().takeIf { it.errorCode != 0 } }

            assertEquals(EnginePlaybackState.ERROR_OPEN, failed.errorCode)
            assertTrue(failed.hasSource)
            assertTrue(engine.lastError().isNotBlank(), "the engine should explain the failed open")
        }
    }

    /**
     * GetStringUTFChars hands native code CESU-8, which is not valid UTF-8, so a path with a
     * supplementary-plane character could not be opened even though the Java file APIs saw it.
     */
    @Test
    fun supplementaryPlaneFilenameOpens() {
        val copy = Files.createTempFile("archivetune-\uD83C\uDFB5-", ".wav")
        try {
            Files.copy(Path.of(PlaybackFixtures.localPath("ramp-48k.wav")), copy, StandardCopyOption.REPLACE_EXISTING)
            openManualEngine().use { engine ->
                engine.load(copy.toAbsolutePath().toString(), 0L, playWhenReady = true)

                val loaded = awaitValue("a supplementary-plane filename to load") {
                    engine.snapshot().takeIf { it.errorCode != 0 || (it.hasSource && it.durationUs > 0L) }
                }
                assertEquals(0, loaded.errorCode, engine.lastError())
                assertRampAt(0L, engine.renderLeft(FRAMES_PER_100_MS), "supplementary-plane filename")
            }
        } finally {
            Files.deleteIfExists(copy)
        }
    }

    private fun openManualEngine(): NativePlaybackEngine =
        NativePlaybackEngine.create(EngineOutputMode.MANUAL, EngineBackend.NULL)

    /** Renders [frames] of the left channel. The ramp fixtures are mono, so both channels match. */
    private fun NativePlaybackEngine.renderLeft(frames: Int): FloatArray {
        val stereo = FloatArray(frames * 2)
        assertEquals(frames, render(stereo, frames), "manual render returned fewer frames than requested")
        return FloatArray(frames) { stereo[it * 2] }
    }

    private fun assertRampAt(index: Long, samples: FloatArray, context: String = "ramp") {
        val mismatch = samples.indices.firstOrNull { offset ->
            abs(PlaybackFixtures.rampSample(index + offset) - samples[offset]) > SAMPLE_TOLERANCE
        }
        assertNull(mismatch, "$context: frame ${index + (mismatch ?: 0)} differs from the ramp")
    }

    private fun assertRampStartsNear(targetFrame: Double, samples: FloatArray) {
        val nearest = floor(targetFrame).toLong()
        val start = (nearest - 2..nearest + 2).firstOrNull { candidate ->
            samples.indices.all { offset ->
                abs(PlaybackFixtures.rampSample(candidate + offset) - samples[offset]) <= SAMPLE_TOLERANCE
            }
        }
        val matched = assertNotNull(start, "output does not match the ramp near frame $targetFrame")
        assertTrue(abs(matched - targetFrame) <= 1.0, "output starts at frame $matched, target $targetFrame")
    }

    private companion object {
        const val FRAMES_PER_100_MS = 4_800
        const val FRAMES_PER_SECOND = 48_000
        const val MICROS_PER_MILLI = 1_000L
        const val MICROS_PER_SECOND = 1_000_000L
        const val FIVE_HUNDRED_MS_US = 500_000L
        const val POSITION_TOLERANCE_US = 1_000L
        const val SAMPLE_TOLERANCE = 1e-6f
    }
}

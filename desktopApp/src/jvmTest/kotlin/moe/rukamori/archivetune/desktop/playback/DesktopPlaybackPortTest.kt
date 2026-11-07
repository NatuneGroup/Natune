package moe.rukamori.archivetune.desktop.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackCommand
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailureReason
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.playback.PlaybackPort
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest

/**
 * The desktop port on the real output path, with the null backend so no sound card is needed. The
 * clock is the device clock, so these tests run in real time and wait on published snapshots.
 */
class DesktopPlaybackPortTest {
    @Test
    fun localWavPlaybackPublishesDurationBufferAndAdvancingPosition() {
        withPort { port ->
            port.submit(PlaybackCommand.Load(localRequest("occ-wav", "ramp-48k.wav"), 0L, playWhenReady = true))

            val playing = port.awaitSnapshot("advancing audio past 300 ms") {
                it.isAudioAdvancing && it.positionMs >= 300L
            }

            assertEquals("occ-wav", playing.occurrenceId)
            assertEquals("media-ramp-48k.wav", playing.mediaId)
            assertEquals(PlaybackFixtures.RAMP_DURATION_MS, playing.durationMs)
            assertTrue(playing.isSeekable)
            assertTrue(playing.playWhenReady)
            assertFalse(playing.isBuffering)
            assertEquals(1f, playing.playbackSpeed)
            assertEquals(PlaybackOutputRoute.LOCAL, playing.outputRoute)
            assertEquals(0L, playing.discontinuityGeneration)
            val buffered = assertNotNull(playing.bufferedPositionMs, "a local file must report its buffered range")
            assertTrue(buffered >= playing.positionMs, "buffered $buffered ms is behind position ${playing.positionMs} ms")
        }
    }

    @Test
    fun pauseFreezesThePositionAndStopsAdvancement() {
        withPort { port ->
            port.submit(PlaybackCommand.Load(localRequest("occ-pause", "ramp-48k.wav"), 0L, playWhenReady = true))
            port.awaitSnapshot("playback past 200 ms") { it.positionMs >= 200L }

            port.submit(PlaybackCommand.Pause)
            // The pause reaches the engine on its own audio callback and the next poll reads that, so the settled
            // snapshot is the first one that also reports the engine stopped advancing. Asserting on the first
            // playWhenReady=false read only measured how quickly the command was published.
            val paused = port.awaitSnapshot("a settled paused snapshot") {
                !it.playWhenReady && !it.isAudioAdvancing
            }
            Thread.sleep(300L)
            val later = assertNotNull(port.snapshots.value)

            assertEquals(paused.positionMs, later.positionMs, "position moved while paused")
            assertFalse(later.isAudioAdvancing)
        }
    }

    @Test
    fun resumeContinuesFromThePausedPosition() {
        withPort { port ->
            port.submit(PlaybackCommand.Load(localRequest("occ-resume", "ramp-48k.wav"), 0L, playWhenReady = true))
            port.awaitSnapshot("playback past 200 ms") { it.positionMs >= 200L }
            port.submit(PlaybackCommand.Pause)
            val paused = port.awaitSnapshot("a paused snapshot") { !it.playWhenReady }

            port.submit(PlaybackCommand.Play)
            val resumed = port.awaitSnapshot("advancing audio after resume") {
                it.isAudioAdvancing && it.positionMs > paused.positionMs + 200L
            }

            assertTrue(resumed.playWhenReady)
            assertEquals(0L, resumed.discontinuityGeneration, "resume is not a seek")
        }
    }

    @Test
    fun seekPublishesOneDiscontinuityAndTheTargetPosition() {
        withPort { port ->
            port.submit(PlaybackCommand.Load(localRequest("occ-seek", "ramp-48k.wav"), 0L, playWhenReady = true))
            port.awaitSnapshot("playback past 200 ms") { it.positionMs >= 200L }

            port.submit(PlaybackCommand.SeekTo(2_000L))
            val seeked = port.awaitSnapshot("the completed seek") { it.discontinuityGeneration == 1L }

            assertTrue(seeked.positionMs in 2_000L..2_500L, "seek to 2000 ms published ${seeked.positionMs} ms")
            val playingOn = port.awaitSnapshot("playback past the seek target") {
                it.isAudioAdvancing && it.positionMs >= 2_300L
            }
            assertEquals(1L, playingOn.discontinuityGeneration, "a seek must count once")
            assertEquals(seeked.playbackGeneration, playingOn.playbackGeneration)
        }
    }

    @Test
    fun speedIsPublishedAsRequestedAndClampedToTheEngineRange() {
        withPort { port ->
            port.submit(PlaybackCommand.Load(localRequest("occ-speed", "ramp-48k.wav"), 0L, playWhenReady = true))
            port.awaitSnapshot("advancing audio") { it.isAudioAdvancing }

            port.submit(PlaybackCommand.SetSpeed(2f))
            port.awaitSnapshot("2x speed") { it.playbackSpeed == 2f }

            port.submit(PlaybackCommand.SetSpeed(0.25f))
            val clamped = port.awaitSnapshot("the 0.5x floor") { it.playbackSpeed == 0.5f }
            assertEquals(0.5f, clamped.playbackSpeed, "the engine reports the speed it applied, not the request")
        }
    }

    @Test
    fun missingLocalFileFailsOnceForItsOccurrence() {
        withPort { port ->
            FailureRecorder(port).use { failures ->
                val request = PlaybackRequest(
                    occurrenceId = "occ-missing",
                    mediaId = "media-missing",
                    locator = PlaybackLocator.LocalFile("/nonexistent/archivetune/ramp.wav"),
                )
                port.submit(PlaybackCommand.Load(request, 0L, playWhenReady = true))

                val failure = failures.awaitFirst()
                Thread.sleep(200L)

                assertEquals("occ-missing", failure.occurrenceId)
                assertEquals(PlaybackFailureReason.OPEN_FAILED, failure.reason)
                assertTrue(failure.message.isNotBlank())
                assertEquals(1, failures.all.size, "a load reports at most one failure")
            }
        }
    }

    @Test
    fun unsupportedLocatorsFailWithoutOpeningAnything() {
        withPort { port ->
            FailureRecorder(port).use { failures ->
                val requests = listOf(
                    PlaybackRequest("occ-tidal", "media-tidal", PlaybackLocator.Remote("tidal-dash://track/1")),
                    PlaybackRequest("occ-telegram", "media-telegram", PlaybackLocator.Remote("telegram://chat/1/2")),
                    PlaybackRequest("occ-relative", "media-relative", PlaybackLocator.LocalFile("relative/ramp.wav")),
                )
                requests.forEach { port.submit(PlaybackCommand.Load(it, 0L, playWhenReady = true)) }

                val reported = failures.awaitCount(requests.size)

                assertEquals(
                    listOf("occ-tidal", "occ-telegram", "occ-relative"),
                    reported.map { it.occurrenceId },
                )
                assertTrue(reported.all { it.reason == PlaybackFailureReason.UNSUPPORTED_LOCATOR })
            }
        }
    }

    @Test
    fun loadWithoutPlayStaysAtTheStartUntilPlayIsSent() {
        withPort { port ->
            port.submit(PlaybackCommand.Load(localRequest("occ-held", "ramp-48k.wav"), 0L, playWhenReady = false))
            val held = port.awaitSnapshot("the held occurrence") { it.occurrenceId == "occ-held" }
            Thread.sleep(200L)

            assertFalse(held.playWhenReady)
            assertEquals(0L, port.snapshots.value?.positionMs)

            port.submit(PlaybackCommand.Play)
            port.awaitSnapshot("advancing audio after play") { it.isAudioAdvancing && it.positionMs > 0L }
        }
    }

    @Test
    fun stopHoldsTheLastPositionAndANewLoadStartsAFreshGeneration() {
        withPort { port ->
            port.submit(PlaybackCommand.Load(localRequest("occ-first", "ramp-48k.wav"), 0L, playWhenReady = true))
            port.awaitSnapshot("playback past 300 ms") { it.positionMs >= 300L }

            port.submit(PlaybackCommand.Stop)
            val stopped = port.awaitSnapshot("the stopped snapshot") { !it.playWhenReady && !it.isAudioAdvancing }
            Thread.sleep(150L)
            assertEquals(stopped.positionMs, port.snapshots.value?.positionMs, "stop must hold the position")

            port.submit(PlaybackCommand.Load(localRequest("occ-second", "ramp-48k.wav"), 0L, playWhenReady = true))
            val second = port.awaitSnapshot("the second occurrence") { it.occurrenceId == "occ-second" }

            assertEquals(stopped.playbackGeneration + 1, second.playbackGeneration)
            assertEquals(0L, second.discontinuityGeneration)
        }
    }

    @Test
    fun controllerHoldsTheInterpolatedPositionWhilePaused() {
        val port = newPort()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = MusicPlayerController(port, scope)
        try {
            controller.load(localRequest("occ-controller", "ramp-48k.wav"))
            awaitValue("the controller advancing past 300 ms") {
                controller.snapshot.value?.takeIf { it.isAudioAdvancing && it.positionMs >= 300L }
            }

            controller.pause()
            // The pause reaches the engine on its own audio callback, so the settled snapshot — not the first
            // playWhenReady=false read — is where the interpolated position has to hold. Reading it earlier measured
            // how quickly the command was published: the drain that follows keeps the anchor advancing, so the
            // position had already moved by the time it was compared.
            val settled = awaitValue("the controller seeing the settled pause") {
                controller.snapshot.value?.takeIf { !it.playWhenReady && !it.isAudioAdvancing }
            }
            val held = assertNotNull(controller.positionMs())
            assertEquals(settled.positionMs, held, "a settled pause is the position the controller holds")
            Thread.sleep(300L)

            assertEquals(held, assertNotNull(controller.positionMs()), "the controller moved while paused")
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun mutedOutputKeepsRenderingAndAdvancing() {
        val port = DesktopPlaybackPort(backend = EngineBackend.NULL)
        try {
            port.submit(PlaybackCommand.Load(localRequest("occ-mute", "ramp-48k.wav"), 0L, playWhenReady = true))
            port.awaitSnapshot("playback past 200 ms") { it.positionMs >= 200L }

            port.setAudioOutputEnabled(false)
            awaitValue("the muted flag") { port.audioOutputEnabled.value.takeIf { !it } }
            val muted = port.awaitSnapshot("position advancing while muted") { it.positionMs >= 500L }
            assertTrue(muted.playWhenReady)
            assertTrue(muted.isAudioAdvancing)

            port.setAudioOutputEnabled(true)
            awaitValue("the unmuted flag") { port.audioOutputEnabled.value.takeIf { it } }
            val unmuted = port.awaitSnapshot("position past the mute") { it.positionMs >= muted.positionMs + 200L }
            assertTrue(unmuted.isAudioAdvancing)
        } finally {
            port.close()
        }
    }

    @Test
    fun releaseParksTheDeviceAndPlayReopensAtTheParkedPosition() {
        val port = DesktopPlaybackPort(backend = EngineBackend.NULL)
        try {
            port.submit(PlaybackCommand.Load(localRequest("occ-park", "ramp-48k.wav"), 0L, playWhenReady = true))
            port.awaitSnapshot("playback past 300 ms") { it.positionMs >= 300L }
            port.submit(PlaybackCommand.Pause)
            // Parking stops the polls, so the snapshot the parked state is judged by is the last one published:
            // wait for the engine to report the pause before freeing the output.
            val paused = port.awaitSnapshot("a settled paused snapshot") {
                !it.playWhenReady && !it.isAudioAdvancing
            }

            port.releaseOutput()
            awaitValue("the parked output") { port.outputSuspended.value.takeIf { it } }
            // A second release while parked is a no-op, not a second teardown.
            port.releaseOutput()
            Thread.sleep(150L)
            val held = assertNotNull(port.snapshots.value)
            assertEquals("occ-park", held.occurrenceId)
            assertFalse(held.playWhenReady)
            assertFalse(held.isAudioAdvancing)

            port.submit(PlaybackCommand.Play)
            val resumed = port.awaitSnapshot("audio after the device reopened") {
                it.isAudioAdvancing && it.positionMs >= paused.positionMs
            }
            assertEquals("occ-park", resumed.occurrenceId)
            awaitValue("the unparked flag") { port.outputSuspended.value.takeIf { !it } }
        } finally {
            port.close()
        }
    }

    @Test
    fun releaseIsIgnoredWhileAudioIsPlaying() {
        val port = DesktopPlaybackPort(backend = EngineBackend.NULL)
        try {
            port.submit(PlaybackCommand.Load(localRequest("occ-playing", "ramp-48k.wav"), 0L, playWhenReady = true))
            port.awaitSnapshot("advancing audio") { it.isAudioAdvancing }

            port.releaseOutput()
            Thread.sleep(300L)

            assertFalse(port.outputSuspended.value, "a playing occurrence keeps the device")
            assertTrue(port.snapshots.value?.isAudioAdvancing == true, "release never interrupted playback")
        } finally {
            port.close()
        }
    }

    @Test
    fun releaseWithoutALoadedOccurrenceIsQuiet() {
        val port = DesktopPlaybackPort(backend = EngineBackend.NULL)
        try {
            port.releaseOutput()
            Thread.sleep(200L)
            assertFalse(port.outputSuspended.value)
            assertNull(port.snapshots.value)
        } finally {
            port.close()
        }
    }

    @Test
    fun seekReopensTheParkedOutputAtTheRequestedPosition() {
        val port = DesktopPlaybackPort(backend = EngineBackend.NULL)
        try {
            port.submit(PlaybackCommand.Load(localRequest("occ-park-seek", "ramp-48k.wav"), 0L, playWhenReady = true))
            port.awaitSnapshot("playback past 200 ms") { it.positionMs >= 200L }
            port.submit(PlaybackCommand.Pause)
            port.awaitSnapshot("a paused snapshot") { !it.playWhenReady }
            port.releaseOutput()
            awaitValue("the parked output") { port.outputSuspended.value.takeIf { it } }

            port.submit(PlaybackCommand.SeekTo(2_000L))
            val moved = port.awaitSnapshot("the parked occurrence moved") {
                it.positionMs in 2_000L..2_800L && it.discontinuityGeneration >= 1L
            }
            assertFalse(moved.playWhenReady, "a seek on a parked output reopens it still paused")
            awaitValue("the unparked flag") { port.outputSuspended.value.takeIf { !it } }
        } finally {
            port.close()
        }
    }

    @Test
    fun stopOnAParkedOutputEndsTheOccurrence() {
        val port = DesktopPlaybackPort(backend = EngineBackend.NULL)
        try {
            port.submit(PlaybackCommand.Load(localRequest("occ-park-stop", "ramp-48k.wav"), 0L, playWhenReady = true))
            port.awaitSnapshot("playback past 200 ms") { it.positionMs >= 200L }
            port.submit(PlaybackCommand.Pause)
            port.awaitSnapshot("a paused snapshot") { !it.playWhenReady }
            port.releaseOutput()
            awaitValue("the parked output") { port.outputSuspended.value.takeIf { it } }

            port.submit(PlaybackCommand.Stop)
            Thread.sleep(200L)

            assertFalse(port.outputSuspended.value, "stop clears the parked flag")
            assertFalse(port.snapshots.value?.playWhenReady == true)
        } finally {
            port.close()
        }
    }

    @Test
    fun closeStopsPublishingAndIgnoresLaterCommands() {
        val port = newPort()
        port.submit(PlaybackCommand.Load(localRequest("occ-closed", "ramp-48k.wav"), 0L, playWhenReady = true))
        port.awaitSnapshot("playback past 200 ms") { it.positionMs >= 200L }

        port.close()
        Thread.sleep(100L)
        val frozen = port.snapshots.value
        port.submit(PlaybackCommand.Play)
        port.submit(PlaybackCommand.SeekTo(3_000L))
        Thread.sleep(200L)

        assertEquals(frozen, port.snapshots.value)
    }

    private fun newPort(): PlaybackPort = DesktopPlaybackPort(backend = EngineBackend.NULL)

    private fun withPort(block: (PlaybackPort) -> Unit) {
        val port = newPort()
        try {
            block(port)
        } finally {
            port.close()
        }
    }
}

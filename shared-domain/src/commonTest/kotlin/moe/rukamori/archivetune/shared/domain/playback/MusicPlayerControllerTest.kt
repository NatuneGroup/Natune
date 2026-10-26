package moe.rukamori.archivetune.shared.domain.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MusicPlayerControllerTest {
    private val request = PlaybackRequest(
        occurrenceId = "occ-1",
        mediaId = "media-1",
        locator = PlaybackLocator.LocalFile("/music/tone.wav"),
    )

    @Test
    fun commandsReachThePortInArrivalOrder() = runTest {
        val port = FakePlaybackPort()
        val controller = MusicPlayerController(port, scopeFor(this))

        controller.load(request, startPositionMs = 250L, playWhenReady = false)
        controller.play()
        controller.seekTo(1_500L)
        controller.setPlaybackSpeed(2f)
        controller.pause()
        controller.stop()

        assertEquals(
            listOf(
                PlaybackCommand.Load(request, startPositionMs = 250L, playWhenReady = false),
                PlaybackCommand.Play,
                PlaybackCommand.SeekTo(1_500L),
                PlaybackCommand.SetSpeed(2f),
                PlaybackCommand.Pause,
                PlaybackCommand.Stop,
            ),
            port.commands,
        )
    }

    @Test
    fun invalidArgumentsAreRejectedBeforeAnyCommandIsSent() = runTest {
        val port = FakePlaybackPort()
        val controller = MusicPlayerController(port, scopeFor(this))

        assertFailsWith<IllegalArgumentException> { controller.seekTo(-1L) }
        assertFailsWith<IllegalArgumentException> { controller.setPlaybackSpeed(0f) }
        assertFailsWith<IllegalArgumentException> { controller.setPlaybackSpeed(Float.NaN) }

        assertTrue(port.commands.isEmpty())
    }

    @Test
    fun snapshotIsNullUntilTheFirstObservation() = runTest {
        val controller = MusicPlayerController(FakePlaybackPort(), scopeFor(this))

        assertNull(controller.snapshot.value)
        assertNull(controller.positionMs(nowNanos = 1_000_000_000L))
    }

    @Test
    fun staleObservationDoesNotMoveTheAnchorBackwards() = runTest {
        val port = FakePlaybackPort()
        val controller = MusicPlayerController(port, scopeFor(this))

        val newer = snapshot(discontinuity = 0L, sampleNanos = 100L, positionMs = 1_000L)
        val stale = snapshot(discontinuity = 0L, sampleNanos = 50L, positionMs = 500L)
        port.publish(newer)
        port.publish(stale)

        assertEquals(newer, controller.snapshot.value)
    }

    @Test
    fun seekAnchorReplacesTheOlderTimelineEvenWhenItsTimestampIsEarlier() = runTest {
        val port = FakePlaybackPort()
        val controller = MusicPlayerController(port, scopeFor(this))

        port.publish(snapshot(discontinuity = 0L, sampleNanos = 500L, positionMs = 1_000L))
        val afterSeek = snapshot(discontinuity = 1L, sampleNanos = 400L, positionMs = 3_000L)
        port.publish(afterSeek)

        assertEquals(afterSeek, controller.snapshot.value)
    }

    @Test
    fun positionInterpolatesWithSpeedOnlyWhileAudioAdvances() = runTest {
        val port = FakePlaybackPort()
        val controller = MusicPlayerController(port, scopeFor(this))

        port.publish(snapshot(sampleNanos = 0L, positionMs = 1_000L, speed = 2f, advancing = true))
        assertEquals(2_000L, controller.positionMs(nowNanos = 500_000_000L))

        port.publish(snapshot(sampleNanos = 0L, positionMs = 1_000L, speed = 2f, advancing = false))
        assertEquals(1_000L, controller.positionMs(nowNanos = 500_000_000L))
    }

    @Test
    fun failuresFromThePortAreForwardedToTheController() = runTest {
        val port = FakePlaybackPort()
        val scope = scopeFor(this)
        val controller = MusicPlayerController(port, scope)
        val received = mutableListOf<PlaybackFailure>()
        val subscriber = scope.launch { controller.failures.collect { received += it } }

        val failure = PlaybackFailure("occ-1", PlaybackFailureReason.OPEN_FAILED, "no such file")
        port.fail(failure)
        subscriber.cancel()

        assertEquals(listOf(failure), received)
    }

    @Test
    fun closeReleasesThePortAndStopsTrackingSnapshots() = runTest {
        val port = FakePlaybackPort()
        val controller = MusicPlayerController(port, scopeFor(this))
        val first = snapshot(sampleNanos = 10L, positionMs = 100L)
        port.publish(first)

        controller.close()
        port.publish(snapshot(sampleNanos = 20L, positionMs = 900L))

        assertTrue(port.closed)
        assertEquals(first, controller.snapshot.value)
    }

    /** Collects synchronously on every publish, so assertions need no scheduler advance. */
    private fun scopeFor(testScope: TestScope): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScope.testScheduler))

    private fun snapshot(
        discontinuity: Long = 0L,
        sampleNanos: Long,
        positionMs: Long,
        speed: Float = 1f,
        advancing: Boolean = true,
    ) = PlaybackSnapshot(
        occurrenceId = "occ-1",
        mediaId = "media-1",
        playbackGeneration = 1L,
        discontinuityGeneration = discontinuity,
        positionMs = positionMs,
        sampleMonotonicNanos = sampleNanos,
        durationMs = 4_000L,
        isSeekable = true,
        playbackSpeed = speed,
        playWhenReady = true,
        isAudioAdvancing = advancing,
        isBuffering = false,
        outputRoute = PlaybackOutputRoute.LOCAL,
    )
}

private class FakePlaybackPort : PlaybackPort {
    private val latest = MutableStateFlow<PlaybackSnapshot?>(null)
    private val failureFlow = MutableSharedFlow<PlaybackFailure>(extraBufferCapacity = 16)

    val commands = mutableListOf<PlaybackCommand>()
    var closed = false
        private set

    override val snapshots: StateFlow<PlaybackSnapshot?> = latest
    override val failures: SharedFlow<PlaybackFailure> = failureFlow

    override fun submit(command: PlaybackCommand) {
        commands += command
    }

    override fun close() {
        closed = true
    }

    fun publish(snapshot: PlaybackSnapshot) {
        latest.value = snapshot
    }

    fun fail(failure: PlaybackFailure) {
        failureFlow.tryEmit(failure)
    }
}

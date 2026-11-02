package moe.rukamori.archivetune.desktop.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopOutputLifecycleTest {
    @Test
    fun parkabilityFollowsPauseSeekAndLiveness() {
        assertTrue(pausedOutputReleasable(paused()))
        assertFalse(pausedOutputReleasable(null))
        assertFalse(pausedOutputReleasable(paused().copy(playWhenReady = true)))
        assertFalse(pausedOutputReleasable(paused().copy(isEnded = true)))
        assertFalse(pausedOutputReleasable(paused().copy(isBuffering = true)))
        assertFalse(pausedOutputReleasable(paused().copy(isSeekable = false)))
    }

    @Test
    fun foregroundPauseReleasesOnceAfterTheLongGrace() = runTest {
        val releases = mutableListOf<Unit>()
        lifecycle(snapshots(paused()), activity(windowed), releases).use {
            advanceTimeBy(PAUSED_GRACE_MS - 1)
            runCurrent()
            assertEquals(0, releases.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, releases.size)
            advanceTimeBy(4 * PAUSED_GRACE_MS)
            runCurrent()
            assertEquals(1, releases.size, "one pause parks the device once")
        }
    }

    @Test
    fun backgroundedPauseReleasesAfterTheShortGrace() = runTest {
        val releases = mutableListOf<Unit>()
        lifecycle(snapshots(paused()), activity(minimized), releases).use {
            advanceTimeBy(BACKGROUND_GRACE_MS - 1)
            runCurrent()
            assertEquals(0, releases.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, releases.size)
        }
    }

    @Test
    fun resumeCancelsTheWaitAndTheNextPauseStartsFresh() = runTest {
        val snapshots = snapshots(paused())
        val releases = mutableListOf<Unit>()
        lifecycle(snapshots, activity(windowed), releases).use {
            advanceTimeBy(PAUSED_GRACE_MS - 200)
            runCurrent()
            snapshots.value = paused().copy(playWhenReady = true, isAudioAdvancing = true)
            advanceTimeBy(2 * PAUSED_GRACE_MS)
            runCurrent()
            assertEquals(0, releases.size, "resumed playback never parks")
            snapshots.value = paused().copy(positionMs = 2_000)
            advanceTimeBy(PAUSED_GRACE_MS - 1)
            runCurrent()
            assertEquals(0, releases.size, "a fresh pause gets the full grace")
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, releases.size)
        }
    }

    @Test
    fun positionEmissionsDoNotRestartOrDuplicateTheWait() = runTest {
        val snapshots = snapshots(paused())
        val releases = mutableListOf<Unit>()
        lifecycle(snapshots, activity(windowed), releases).use {
            var position = 1_000L
            repeat(PAUSED_GRACE_MS.toInt() / 50) {
                position += 50
                snapshots.value = paused().copy(positionMs = position)
                advanceTimeBy(50)
                runCurrent()
            }
            assertEquals(1, releases.size, "paused ticks share the wait; it fires once")
        }
    }

    @Test
    fun movingOffscreenShortensTheWait() = runTest {
        val activity = activity(windowed)
        val releases = mutableListOf<Unit>()
        lifecycle(snapshots(paused()), activity, releases).use {
            advanceTimeBy(PAUSED_GRACE_MS - 2 * BACKGROUND_GRACE_MS)
            runCurrent()
            assertEquals(0, releases.size)
            activity.value = minimized
            advanceTimeBy(BACKGROUND_GRACE_MS)
            runCurrent()
            assertEquals(1, releases.size, "an off-screen pause releases on the short grace")
        }
    }

    @Test
    fun returningOnscreenRestoresTheLongGrace() = runTest {
        val activity = activity(minimized)
        val releases = mutableListOf<Unit>()
        lifecycle(snapshots(paused()), activity, releases).use {
            advanceTimeBy(BACKGROUND_GRACE_MS - 10)
            runCurrent()
            activity.value = windowed
            advanceTimeBy(PAUSED_GRACE_MS)
            runCurrent()
            assertEquals(1, releases.size, "the restored window re-arms the long grace")
        }
    }

    @Test
    fun playingEndedBufferingAndUnseekableSessionsNeverPark() = runTest {
        val releases = mutableListOf<Unit>()
        for (snapshot in listOf(
            paused().copy(playWhenReady = true, isAudioAdvancing = true),
            paused().copy(isEnded = true),
            paused().copy(isBuffering = true),
            paused().copy(isSeekable = false),
        )) {
            lifecycle(snapshots(snapshot), activity(minimized), releases).use {
                advanceTimeBy(4 * PAUSED_GRACE_MS)
                runCurrent()
            }
        }
        assertEquals(0, releases.size)
    }

    @Test
    fun closeCancelsAnArmedWait() = runTest {
        val releases = mutableListOf<Unit>()
        val lifecycle = lifecycle(snapshots(paused()), activity(windowed), releases)
        advanceTimeBy(PAUSED_GRACE_MS - 1)
        runCurrent()
        lifecycle.close()
        advanceTimeBy(2 * PAUSED_GRACE_MS)
        runCurrent()
        assertEquals(0, releases.size)
    }

    private fun TestScope.lifecycle(
        snapshots: StateFlow<PlaybackSnapshot?>,
        activity: StateFlow<DesktopWindowActivity>,
        releases: MutableList<Unit>,
    ) = DesktopOutputLifecycle(
        snapshots = snapshots,
        activity = activity,
        scope = backgroundScope,
        release = { releases += Unit },
        pausedDelayMs = PAUSED_GRACE_MS,
        backgroundDelayMs = BACKGROUND_GRACE_MS,
    )

    private fun snapshots(snapshot: PlaybackSnapshot?) = MutableStateFlow(snapshot)

    private fun activity(state: DesktopWindowActivity) = MutableStateFlow(state)

    private fun paused() = PlaybackSnapshot(
        occurrenceId = "first",
        mediaId = "media-first",
        playbackGeneration = 1,
        discontinuityGeneration = 0,
        positionMs = 1_000,
        sampleMonotonicNanos = 0,
        durationMs = 10_000,
        isSeekable = true,
        playbackSpeed = 1f,
        playWhenReady = false,
        isAudioAdvancing = false,
        isBuffering = false,
        outputRoute = PlaybackOutputRoute.LOCAL,
    )

    private companion object {
        const val PAUSED_GRACE_MS = 1_000L
        const val BACKGROUND_GRACE_MS = 100L
    }
}

private val windowed = DesktopWindowActivity(visible = true, minimized = false, occluded = false, resumed = true)
private val minimized = windowed.copy(minimized = true)

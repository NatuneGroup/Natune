package moe.rukamori.archivetune.shared.ui.menu.playerqueue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class SharedSleepTimerTest {
    @Test fun durationTimerPublishesItsDeadlineAndPausesWhenItRunsOut() = runTest {
        var now = 1_000_000L
        var pauses = 0
        val timer = SharedSleepTimer(
            scope = backgroundScope,
            snapshot = MutableStateFlow(null),
            pause = { pauses++ },
            nowEpochMs = { now },
        )
        timer.startSleepTimer(30)
        assertEquals(
            PlayerSleepTimerState(
                isActive = true,
                pauseWhenSongEnd = false,
                triggerAtEpochMs = now + 30.minutes.inWholeMilliseconds,
            ),
            timer.state.value,
        )
        advanceTimeBy(29.minutes)
        runCurrent()
        assertEquals(0, pauses)
        assertTrue(timer.state.value.isActive)
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(1, pauses)
        assertFalse(timer.state.value.isActive)
        // A fired timer does not pause again when the clock moves past the deadline a second time.
        now += 60.minutes.inWholeMilliseconds
        advanceTimeBy(60.minutes)
        runCurrent()
        assertEquals(1, pauses)
    }

    @Test fun endOfSongPausesOnTheNextOccurrenceAndIgnoresTheCurrentSong() = runTest {
        val snapshots = MutableStateFlow<PlaybackSnapshot?>(snapshot("one"))
        var pauses = 0
        val timer = SharedSleepTimer(
            scope = backgroundScope,
            snapshot = snapshots,
            pause = { pauses++ },
            nowEpochMs = { 0L },
        )
        timer.startSleepTimerEndOfSong()
        assertEquals(
            PlayerSleepTimerState(isActive = true, pauseWhenSongEnd = true, triggerAtEpochMs = 0L),
            timer.state.value,
        )
        runCurrent()
        // A republished snapshot of the same occurrence is not a song change.
        snapshots.value = snapshot("one")
        runCurrent()
        assertEquals(0, pauses)
        assertTrue(timer.state.value.isActive)
        snapshots.value = snapshot("two")
        runCurrent()
        assertEquals(1, pauses)
        assertFalse(timer.state.value.isActive)
    }

    @Test fun endOfSongPausesWhenTheCurrentSongEnds() = runTest {
        val snapshots = MutableStateFlow<PlaybackSnapshot?>(snapshot("one"))
        var pauses = 0
        val timer = SharedSleepTimer(
            scope = backgroundScope,
            snapshot = snapshots,
            pause = { pauses++ },
            nowEpochMs = { 0L },
        )
        timer.startSleepTimerEndOfSong()
        runCurrent()
        snapshots.value = snapshot("one", ended = true)
        runCurrent()
        assertEquals(1, pauses)
        assertFalse(timer.state.value.isActive)
    }

    @Test fun clearCancelsAPendingTimerWithoutPausing() = runTest {
        val snapshots = MutableStateFlow<PlaybackSnapshot?>(snapshot("one"))
        var pauses = 0
        val timer = SharedSleepTimer(
            scope = backgroundScope,
            snapshot = snapshots,
            pause = { pauses++ },
            nowEpochMs = { 0L },
        )
        timer.startSleepTimer(5)
        timer.clearSleepTimer()
        assertFalse(timer.state.value.isActive)
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(0, pauses)
        timer.startSleepTimerEndOfSong()
        timer.clearSleepTimer()
        snapshots.value = snapshot("two")
        runCurrent()
        assertEquals(0, pauses)
    }

    @Test fun restartingATimerReplacesThePendingOne() = runTest {
        var pauses = 0
        val timer = SharedSleepTimer(
            scope = backgroundScope,
            snapshot = MutableStateFlow(null),
            pause = { pauses++ },
            nowEpochMs = { 0L },
        )
        timer.startSleepTimer(5)
        timer.startSleepTimerEndOfSong()
        assertTrue(timer.state.value.pauseWhenSongEnd)
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(0, pauses)
        assertTrue(timer.state.value.isActive)
    }

    private fun snapshot(occurrenceId: String, ended: Boolean = false) = PlaybackSnapshot(
        occurrenceId = occurrenceId,
        mediaId = occurrenceId,
        playbackGeneration = 1L,
        discontinuityGeneration = 0L,
        positionMs = 0L,
        sampleMonotonicNanos = 0L,
        durationMs = 180_000L,
        isSeekable = true,
        playbackSpeed = 1f,
        playWhenReady = true,
        isAudioAdvancing = true,
        isBuffering = false,
        outputRoute = PlaybackOutputRoute.LOCAL,
        isEnded = ended,
    )
}

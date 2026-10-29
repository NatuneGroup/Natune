package moe.rukamori.archivetune.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastProgressTest {
    @Test fun ninetyFivePercentFinishesAndResetsTheResumePosition() {
        assertFalse(row(949_999L, 1_000_000L).finished)
        val played = row(950_000L, 1_000_000L)
        assertTrue(played.finished)
        assertEquals(0L, played.positionMs)
        assertEquals(0L, played.resumePositionMs)
        assertEquals(1f, played.fraction)
    }

    @Test fun finalThirtySecondsAlsoFinishesButUnplayedZeroDoesNot() {
        assertFalse(row(209_999L, 240_000L).finished)
        assertTrue(row(210_000L, 240_000L).finished)
        assertFalse(row(0L, 20_000L).finished)
    }

    @Test fun unknownDurationsOnlyFinishAtAnActualEndAndBadPositionsAreClamped() {
        assertEquals(0L, row(-1L, null).positionMs)
        assertFalse(row(900_000L, null).finished)
        assertNull(row(900_000L, 0L).durationMs)
        assertNull(row(900_000L, null).fraction)
        assertTrue(row(0L, null, ended = true).finished)
        assertEquals(0L, row(Long.MAX_VALUE, Long.MAX_VALUE).positionMs)
        assertFalse(row(1L, Long.MAX_VALUE).finished)
    }

    @Test fun writesEveryFiveSecondsOfObservationTimeNotMediaTime() = runTest {
        val store = Store()
        val recorder = recorder(store)
        recorder.observe(sample(position = 0L, time = 0L))
        runCurrent()
        recorder.observe(sample(position = 80_000L, time = 4_999L))
        runCurrent()
        assertTrue(store.writes.isEmpty())
        recorder.observe(sample(position = 90_000L, time = 5_000L))
        runCurrent()
        assertEquals(listOf(90_000L), store.writes.map { it.positionMs })
        recorder.observe(sample(position = 91_000L, time = 9_999L))
        runCurrent()
        assertEquals(1, store.writes.size)
        recorder.close()
        assertEquals(91_000L, store.writes.last().positionMs)
    }

    @Test fun pauseSeekAndStopFlushWithoutWaitingForTheNextFiveSeconds() = runTest {
        val store = Store()
        val recorder = recorder(store)
        recorder.observe(sample(0L))
        runCurrent()
        recorder.observe(sample(2_000L, time = 2_000L, advancing = false))
        runCurrent()
        assertEquals(2_000L, store.writes.last().positionMs)
        recorder.observe(sample(40_000L, time = 2_100L, advancing = false, discontinuity = 1L))
        runCurrent()
        assertEquals(40_000L, store.writes.last().positionMs)
        recorder.observe(sample(41_000L, time = 3_100L, discontinuity = 1L))
        recorder.observe(null)
        recorder.close()
        assertEquals(41_000L, store.writes.last().positionMs)
    }

    @Test fun unchangedPausedAndUnplayedRowsAreNotWrittenRepeatedly() = runTest {
        val store = Store()
        val recorder = recorder(store)
        recorder.observe(sample(0L, advancing = false))
        runCurrent()
        recorder.flush()
        recorder.observe(sample(0L, time = 6_000L, advancing = false))
        recorder.close()
        assertTrue(store.writes.isEmpty())
    }

    @Test fun unfinishedEpisodeWaitsForSeekabilityAndResumesExactlyOnce() = runTest {
        val store = Store(row(80_000L, 600_000L))
        val seeks = ArrayList<Long>()
        val recorder = recorder(store, seeks)
        recorder.observe(sample(0L, advancing = false, seekable = false))
        runCurrent()
        assertTrue(seeks.isEmpty())
        recorder.observe(sample(0L, advancing = false))
        assertEquals(listOf(80_000L), seeks)
        recorder.observe(sample(0L, time = 500L, advancing = false))
        runCurrent()
        assertTrue(store.writes.isEmpty(), "A stale pre-seek sample must not erase a bookmark")
        recorder.observe(sample(80_000L, time = 600L, advancing = false, discontinuity = 1L))
        recorder.observe(sample(81_000L, time = 1_600L, discontinuity = 1L))
        recorder.close()
        assertEquals(listOf(80_000L), seeks)
        assertEquals(81_000L, store.writes.last().positionMs)
    }

    @Test fun finishedEpisodeStartsAtZeroAndANewListenClearsItsPlayedMarker() = runTest {
        val store = Store(row(600_000L, 600_000L))
        val seeks = ArrayList<Long>()
        val recorder = recorder(store, seeks)
        recorder.observe(sample(12_000L, advancing = false))
        runCurrent()
        assertEquals(listOf(0L), seeks)
        recorder.observe(sample(0L, time = 100L, advancing = false, discontinuity = 1L))
        recorder.observe(sample(1_000L, time = 1_100L, discontinuity = 1L))
        recorder.close()
        assertFalse(store.writes.last().finished)
        assertEquals(1_000L, store.writes.last().positionMs)
    }

    @Test fun userSeekWhileReadIsPendingWinsOverTheOldBookmark() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = Store(row(80_000L, 600_000L), gate)
        val seeks = ArrayList<Long>()
        val recorder = recorder(store, seeks)
        recorder.observe(sample(0L))
        runCurrent()
        recorder.observe(sample(40_000L, time = 1_000L, advancing = false, discontinuity = 1L))
        gate.complete(Unit)
        runCurrent()
        recorder.close()
        assertTrue(seeks.isEmpty())
        assertEquals(40_000L, store.writes.last().positionMs)
    }

    @Test fun staleReadCannotSeekAnotherOccurrenceOrEraseAnUnresumedBookmark() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = Store(row(80_000L, 600_000L), gate)
        val seeks = ArrayList<Long>()
        val recorder = recorder(store, seeks)
        recorder.observe(sample(0L))
        runCurrent()
        recorder.observe(sample(2_000L, time = 2_000L))
        recorder.observe(null)
        gate.complete(Unit)
        recorder.close()
        assertTrue(seeks.isEmpty())
        assertTrue(store.writes.isEmpty())
        assertEquals(80_000L, store.rows.getValue("youtube:episode").positionMs)
    }

    @Test fun serviceStopDrainsAShortListenEvenIfItsReadHasNotFinished() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = Store(gate = gate)
        val recorder = recorder(store)
        recorder.observe(sample(0L))
        runCurrent()
        recorder.observe(sample(2_000L, time = 2_000L))
        val drained = recorder.finish()
        assertFalse(drained.isCompleted)
        gate.complete(Unit)
        drained.join()
        assertEquals(2_000L, store.writes.single().positionMs)
    }

    @Test fun outgoingFlushIsReadBeforeResumingARepeatedEpisode() = runTest {
        val store = Store()
        val seeks = ArrayList<Long>()
        val recorder = recorder(store, seeks)
        recorder.observe(sample(0L))
        runCurrent()
        recorder.observe(sample(4_000L, time = 4_000L))
        recorder.observe(sample(0L, time = 4_100L, occurrence = "replay", advancing = false))
        runCurrent()
        assertEquals(listOf(4_000L), seeks)
        recorder.close()
        assertEquals(4_000L, store.rows.getValue("youtube:episode").positionMs)
    }

    @Test fun naturalEndWithUnknownDurationPersistsAPlayedMarker() = runTest {
        val store = Store()
        val recorder = recorder(store)
        recorder.observe(sample(0L).copy(durationMs = null))
        runCurrent()
        recorder.observe(sample(1_000L, time = 1_000L, advancing = false).copy(durationMs = null, ended = true))
        recorder.close()
        assertTrue(store.writes.single().finished)
        assertEquals(0L, store.writes.single().positionMs)
        assertNull(store.writes.single().durationMs)
    }

    @Test fun naturalEndWhileTheBookmarkReadIsPendingStillMarksTheEpisodePlayed() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = Store(row(80_000L, null), gate)
        val recorder = recorder(store)
        recorder.observe(sample(0L).copy(durationMs = null))
        runCurrent()
        recorder.observe(sample(1_000L, time = 1_000L, advancing = false).copy(durationMs = null, ended = true))
        val drained = recorder.finish()
        gate.complete(Unit)
        drained.join()
        assertTrue(store.writes.single().finished)
    }

    @Test fun failedPausedWriteCanRetryWithoutRequiringMorePlayback() = runTest {
        val store = Store(failuresRemaining = 1)
        val reports = ArrayList<String>()
        val recorder = PodcastProgressRecorder(this, store, {}, { reports += it }, { 100L })
        recorder.observe(sample(0L))
        runCurrent()
        recorder.observe(sample(2_000L, time = 2_000L, advancing = false))
        runCurrent()
        assertTrue(store.writes.isEmpty())
        assertEquals(1, reports.size)
        recorder.flush()
        runCurrent()
        recorder.close()
        assertEquals(2_000L, store.writes.single().positionMs)
    }

    private fun kotlinx.coroutines.CoroutineScope.recorder(store: Store, seeks: MutableList<Long> = ArrayList()) =
        PodcastProgressRecorder(this, store, { seeks += it }, { error(it) }, { 100L })

    private class Store(
        initial: PodcastProgress? = null,
        private val gate: CompletableDeferred<Unit>? = null,
        private var failuresRemaining: Int = 0,
    ) : PodcastProgressStore {
        val rows = mutableMapOf<String, PodcastProgress>().apply { if (initial != null) put(initial.episodeId, initial) }
        val writes = ArrayList<PodcastProgress>()
        override suspend fun read(episodeId: String): PodcastProgress? { gate?.await(); return rows[episodeId] }
        override suspend fun write(progress: PodcastProgress) {
            if (failuresRemaining > 0) {
                failuresRemaining--
                error("Storage unavailable")
            }
            writes += progress
            rows[progress.episodeId] = progress
        }
        override fun observeAll(): Flow<List<PodcastProgress>> = flowOf(rows.values.toList())
    }

    private fun sample(
        position: Long,
        time: Long = 0L,
        advancing: Boolean = true,
        seekable: Boolean = true,
        discontinuity: Long = 0L,
        occurrence: String = "first",
    ) = PodcastProgressSample(occurrence, "youtube:episode", position, 600_000L, advancing, seekable, discontinuity, time)

    private fun row(position: Long, duration: Long?, ended: Boolean = false) =
        PodcastProgressPolicy.row("youtube:episode", position, duration, ended, 1L)
}

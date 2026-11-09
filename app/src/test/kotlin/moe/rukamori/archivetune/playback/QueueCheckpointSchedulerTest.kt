package moe.rukamori.archivetune.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.playback.queues.EmptyQueue
import moe.rukamori.archivetune.playback.queues.YouTubePlaylistQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueCheckpointSchedulerTest {
    @Test
    fun burstRequests_snapshotOnceAndWriteTheFinalQueue() = runTest {
        val fixture = Fixture(this)
        repeat(30) { revision ->
            fixture.items = listOf("current", "upcoming-$revision")
            fixture.scheduler.request()
            runCurrent()
            advanceTimeBy(10L)
        }
        assertTrue(fixture.snapshots.isEmpty())

        advanceUntilIdle()

        assertEquals(1, fixture.snapshots.size)
        assertEquals(listOf(listOf("current", "upcoming-29")), fixture.written)
    }

    @Test
    fun requestsDuringWrite_checkpointTheFinalQueueAfterTheInFlightWrite() = runTest {
        val fixture = Fixture(this)
        val releaseFirstWrite = CompletableDeferred<Unit>()
        fixture.beforeWrite = { if (it.items == listOf("seed")) releaseFirstWrite.await() }
        fixture.scheduler.request()
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(1, fixture.snapshots.size)

        repeat(30) { revision ->
            fixture.items = listOf("seed", "added-$revision")
            fixture.scheduler.request()
        }
        releaseFirstWrite.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, fixture.snapshots.size)
        assertEquals(listOf(listOf("seed"), listOf("seed", "added-29")), fixture.written)
    }

    @Test
    fun newerTeardownSnapshot_rejectsAnOlderCheckpointThatArrivesLater() = runTest {
        val fixture = Fixture(this)
        val releaseOlderWrite = CompletableDeferred<Unit>()
        fixture.beforeWrite = { releaseOlderWrite.await() }
        fixture.scheduler.request()
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(1, fixture.snapshots.size)

        fixture.items = listOf("current", "newer")
        val teardownSnapshot = fixture.snapshot()
        assertTrue(fixture.commit(teardownSnapshot))
        releaseOlderWrite.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(listOf("current", "newer")), fixture.written)
        assertFalse(fixture.commit(fixture.snapshots.first()))
    }

    @Test
    fun generationBump_discardsAnInFlightOlderSave() = runTest {
        val fixture = Fixture(this)
        val releaseOlderWrite = CompletableDeferred<Unit>()
        fixture.beforeWrite = { if (it.checkpoint.saveGeneration == 0L) releaseOlderWrite.await() }
        fixture.scheduler.request()
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(1, fixture.snapshots.size)

        fixture.invalidate()
        fixture.items = listOf("restored", "final")
        fixture.scheduler.request()
        releaseOlderWrite.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, fixture.snapshots.size)
        assertEquals(listOf(listOf("restored", "final")), fixture.written)
        assertFalse(fixture.commit(fixture.snapshots.first()))
    }

    @Test
    fun generationBump_discardsADebouncedRequestUntilANewRequestArrives() = runTest {
        val fixture = Fixture(this)
        fixture.scheduler.request()
        runCurrent()
        fixture.invalidate()
        advanceUntilIdle()
        assertTrue(fixture.snapshots.isEmpty())
        assertTrue(fixture.written.isEmpty())

        fixture.items = listOf("replacement")
        fixture.scheduler.request()
        advanceUntilIdle()

        assertEquals(listOf(listOf("replacement")), fixture.written)
    }

    @Test
    fun disabledSetting_neverSnapshotsOrWritesIncludingWhenDisabledDuringDebounce() = runTest {
        val fixture = Fixture(this)
        fixture.enabled = false
        repeat(30) { fixture.scheduler.request() }
        advanceUntilIdle()
        assertTrue(fixture.snapshots.isEmpty())
        assertTrue(fixture.written.isEmpty())

        fixture.enabled = true
        fixture.scheduler.request()
        runCurrent()
        advanceTimeBy(50L)
        fixture.enabled = false
        advanceUntilIdle()

        assertTrue(fixture.snapshots.isEmpty())
        assertTrue(fixture.written.isEmpty())
    }

    @Test
    fun restoreAndHydration_deferRequestsAndCheckpointThePostRestoreState() = runTest {
        val fixture = Fixture(this)
        fixture.restoring = true
        fixture.scheduler.request()
        advanceUntilIdle()
        assertTrue(fixture.snapshots.isEmpty())

        fixture.restoring = false
        fixture.hydrating = true
        fixture.items = listOf("restored-window")
        fixture.scheduler.request()
        advanceUntilIdle()
        assertTrue(fixture.snapshots.isEmpty())

        fixture.items = listOf("prefix", "restored-window", "suffix")
        repeat(30) { fixture.scheduler.request() }
        advanceUntilIdle()
        assertTrue(fixture.written.isEmpty())

        fixture.hydrating = false
        fixture.scheduler.request()
        advanceUntilIdle()

        assertEquals(1, fixture.snapshots.size)
        assertEquals(listOf(listOf("prefix", "restored-window", "suffix")), fixture.written)
    }

    @Test
    fun finiteMutation_defersSnapshottingUntilTheAcceptedStateIsComplete() = runTest {
        val fixture = Fixture(this)
        fixture.mutating = true
        fixture.items = listOf("partial")
        fixture.scheduler.request()
        advanceUntilIdle()
        assertTrue(fixture.snapshots.isEmpty())

        fixture.items = listOf("prefix", "current", "suffix")
        fixture.mutating = false
        fixture.scheduler.request()
        advanceUntilIdle()

        assertEquals(listOf(listOf("prefix", "current", "suffix")), fixture.written)
    }

    @Test
    fun requestsDuringPreferenceRead_coalesceBeforeMappingTheQueue() = runTest {
        val fixture = Fixture(this)
        val releasePreferenceRead = CompletableDeferred<Unit>()
        var reads = 0
        fixture.readEnabled = {
            if (++reads == 1) releasePreferenceRead.await()
            fixture.enabled
        }
        fixture.scheduler.request()
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(1, reads)
        assertTrue(fixture.snapshots.isEmpty())

        repeat(30) { revision ->
            fixture.items = listOf("latest-$revision")
            fixture.scheduler.request()
        }
        releasePreferenceRead.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, fixture.snapshots.size)
        assertEquals(listOf(listOf("latest-29")), fixture.written)
    }

    @Test
    fun emptyQueueCheckpoint_supersedesThePreviouslyWrittenQueue() = runTest {
        val fixture = Fixture(this)
        fixture.scheduler.request()
        advanceUntilIdle()
        val nonEmptySnapshot = fixture.snapshots.single()

        fixture.items = emptyList()
        fixture.scheduler.request()
        advanceUntilIdle()

        assertEquals(listOf(listOf("seed"), emptyList<String>()), fixture.written)
        assertFalse(fixture.commit(nonEmptySnapshot))
        assertTrue(fixture.written.last().isEmpty())
    }

    @Test
    fun teardownCheckpoint_includesAcceptedClearsButHonorsTheDisabledSetting() {
        assertTrue(TeardownQueuePersistence.shouldSave(true, 0, hasQueueTimelineChanges = true))
        assertFalse(TeardownQueuePersistence.shouldSave(false, 0, hasQueueTimelineChanges = true))
        assertFalse(TeardownQueuePersistence.shouldSave(true, 0, hasQueueTimelineChanges = false))
    }

    @Test
    fun newerResumeState_doesNotDropAPendingQueueOrAllowOlderStateToOverwriteIt() = runTest {
        val order = QueueCheckpointOrder { 0L }
        val queues = mutableListOf<Int>()
        val states = mutableListOf<Int>()
        val olderQueue = order.nextSnapshot()
        val newerPosition = order.nextSnapshot()
        order.writePlayerStateIfCurrent(newerPosition) { states += 2 }

        assertTrue(order.writeIfCurrent(olderQueue) {
            queues += 1
            order.writePlayerStateIfCurrent(olderQueue) { states += 1 }
        })
        assertEquals(listOf(1), queues)
        assertEquals(listOf(2), states)

        val newestQueue = order.nextSnapshot()
        order.writeIfCurrent(newestQueue) {
            queues += 3
            order.writePlayerStateIfCurrent(newestQueue) { states += 3 }
        }
        assertFalse(order.writePlayerStateIfCurrent(newerPosition) { states += 2 })
        assertEquals(listOf(1, 3), queues)
        assertEquals(listOf(2, 3), states)
    }

    @Test
    fun temporaryFiniteClear_doesNotReplaceThePreviousCheckpoint() {
        val queue = YouTubePlaylistQueue(WatchEndpoint(playlistId = "fixture"))
        assertFalse(queue.hasUnacceptedFiniteReplacement())

        queue.initialTimelineCleared = true
        assertTrue(queue.hasUnacceptedFiniteReplacement())

        queue.timelineInstalled = true
        assertFalse(queue.hasUnacceptedFiniteReplacement())
        assertFalse(EmptyQueue.hasUnacceptedFiniteReplacement())
    }

    @Test
    fun persistenceReenabled_requestsACheckpointForChangesMadeWhileDisabled() = runTest {
        val enabled = MutableStateFlow(false)
        var restoreCompleted = true
        var checkpoints = 0
        observeQueuePersistence(backgroundScope, enabled, { restoreCompleted }) { checkpoints++ }
        runCurrent()
        assertEquals(0, checkpoints)

        enabled.value = true
        runCurrent()
        assertEquals(1, checkpoints)

        enabled.value = false
        runCurrent()
        restoreCompleted = false
        enabled.value = true
        runCurrent()
        assertEquals(1, checkpoints)

        restoreCompleted = true
        enabled.value = false
        runCurrent()
        enabled.value = true
        runCurrent()
        assertEquals(2, checkpoints)
    }

    @Test
    fun previousServiceTeardown_cannotOverwriteANewerServiceCheckpoint() = runTest {
        val persistence = QueuePersistenceState()
        val previous = Fixture(this, persistence)
        val current = Fixture(this, persistence)
        val releasePreviousWrite = CompletableDeferred<Unit>()
        previous.beforeWrite = { releasePreviousWrite.await() }
        previous.scheduler.request()
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(1, previous.snapshots.size)

        current.items = listOf("new-service")
        assertTrue(current.commit(current.snapshot()))
        releasePreviousWrite.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(listOf("new-service")), current.written)
        assertTrue(previous.written.isEmpty())
        assertFalse(previous.commit(previous.snapshots.first()))
    }

    private data class Snapshot(val checkpoint: QueueCheckpointToken, val items: List<String>)

    private class Fixture(
        scope: CoroutineScope,
        private val persistence: QueuePersistenceState = QueuePersistenceState(),
    ) {
        var enabled = true
        var restoring = false
        var hydrating = false
        var mutating = false
        var items = listOf("seed")
        val snapshots = mutableListOf<Snapshot>()
        val written = mutableListOf<List<String>>()
        var readEnabled: suspend () -> Boolean = { enabled }
        var beforeWrite: suspend (Snapshot) -> Unit = {}
        val scheduler = QueueCheckpointScheduler(
            scope = { scope },
            generation = { persistence.generation.get() },
            enabled = { readEnabled() },
            blocked = { restoring || hydrating || mutating },
            snapshot = { snapshot() },
            write = {
                beforeWrite(it)
                commit(it)
            },
        )

        fun snapshot(): Snapshot = Snapshot(persistence.checkpointOrder.nextSnapshot(), items.toList()).also { snapshots += it }

        fun commit(snapshot: Snapshot): Boolean = synchronized(persistence.lock) {
            persistence.checkpointOrder.writeIfCurrent(snapshot.checkpoint) { written += snapshot.items }
        }

        fun invalidate() = synchronized(persistence.lock) { persistence.generation.incrementAndGet() }
    }
}

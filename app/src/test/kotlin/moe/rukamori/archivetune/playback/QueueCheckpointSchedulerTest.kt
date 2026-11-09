package moe.rukamori.archivetune.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.extensions.toPersistQueue
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.models.QueueType
import moe.rukamori.archivetune.playback.queues.EmptyQueue
import moe.rukamori.archivetune.playback.queues.FinitePlaylistPhase
import moe.rukamori.archivetune.playback.queues.FinitePlaylistSnapshot
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.playback.queues.YouTubePlaylistQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun untouchedInitialFailure_preservesThePreviousCheckpointIncludingAtTeardown() = runTest {
        val fixture = Fixture(this)
        fixture.scheduler.request()
        advanceUntilIdle()

        val queue = fixture.beginFiniteReplacement()
        queue.fail()
        fixture.scheduler.request()
        advanceUntilIdle()

        assertTrue(queue.hasUnacceptedFiniteReplacement())
        assertEquals(listOf(listOf("seed")), fixture.written)
        assertFalse(fixture.savePosition(200L))
        assertNull(fixture.teardownSnapshot())
        assertEquals(1, fixture.snapshots.size)
    }

    @Test
    fun manualAdditionAfterInitialFailure_checkpointsWithoutChangingLoaderRetryState() = runTest {
        val fixture = Fixture(this)
        val queue = fixture.beginFiniteReplacement()
        queue.fail()
        fixture.changeTimeline(listOf("manual"))
        advanceUntilIdle()

        assertEquals(listOf(listOf("manual")), fixture.written)
        assertTrue(queue.initialTimelineCleared)
        assertFalse(queue.timelineInstalled)
        assertTrue(queue.hasUnacceptedFiniteReplacement())
        assertTrue(fixture.savePosition(300L))
        assertEquals(listOf(300L), fixture.positions)
    }

    @Test
    fun manualAdditionDuringInitialRequest_remainsAcceptedAfterTheRequestFails() = runTest {
        val fixture = Fixture(this)
        val queue = fixture.beginFiniteReplacement()
        val finishInitialRequest = CompletableDeferred<Unit>()
        val load = launch {
            finishInitialRequest.await()
            queue.fail()
        }
        fixture.changeTimeline(listOf("manual-during-request"))
        advanceUntilIdle()
        assertEquals(listOf(listOf("manual-during-request")), fixture.written)

        finishInitialRequest.complete(Unit)
        load.join()
        fixture.scheduler.request()
        advanceUntilIdle()

        assertEquals(FinitePlaylistPhase.FAILED, queue.snapshot.phase)
        assertEquals(listOf("manual-during-request"), fixture.written.last())
        assertFalse(queue.timelineInstalled)
        assertTrue(fixture.savePosition(400L))
    }

    @Test
    fun removingManualAdditionAfterInitialFailure_persistsAnAcceptedEmptyList() = runTest {
        val fixture = Fixture(this)
        val queue = fixture.beginFiniteReplacement()
        queue.fail()
        fixture.changeTimeline(listOf("manual"))
        advanceUntilIdle()
        val manualSnapshot = fixture.snapshots.single()

        fixture.changeTimeline(emptyList())
        advanceUntilIdle()

        assertEquals(listOf(listOf("manual"), emptyList<String>()), fixture.written)
        assertEquals(QueueType.LIST, fixture.snapshots.last().queueType)
        assertFalse(fixture.commit(manualSnapshot))
        assertTrue(queue.hasUnacceptedFiniteReplacement())
        assertTrue(fixture.commit(requireNotNull(fixture.teardownSnapshot())))
        assertTrue(fixture.written.last().isEmpty())
    }

    @Test
    fun newFiniteReplacement_resetsAcceptanceOfThePreviousManualQueue() = runTest {
        val fixture = Fixture(this)
        fixture.beginFiniteReplacement().fail()
        fixture.changeTimeline(listOf("manual"))
        advanceUntilIdle()

        fixture.beginFiniteReplacement().fail()
        advanceUntilIdle()

        assertEquals(listOf(listOf("manual")), fixture.written)
        assertNull(fixture.teardownSnapshot())
    }

    @Test
    fun unacceptedReplacement_blocksPositionOnlyWritesWithANonemptyTimeline() = runTest {
        val fixture = Fixture(this)
        val queue = fixture.beginFiniteReplacement()
        // A retained item is not accepted until the playlist-changed callback observes a user edit.
        fixture.items = listOf("retained")
        queue.fail()
        assertFalse(fixture.savePosition(500L))
        assertTrue(fixture.positions.isEmpty())
        assertNull(fixture.snapshotIfAccepted())

        fixture.changeTimeline(listOf("manual"))
        advanceUntilIdle()
        assertTrue(fixture.savePosition(600L))
        assertEquals(listOf(600L), fixture.positions)
    }

    @Test
    fun nextPageFailure_keepsTheAcceptedNonemptyTimelineCheckpointable() = runTest {
        val fixture = Fixture(this)
        val queue = fixture.beginFiniteReplacement(FinitePlaylistSnapshot(
            continuation = "next-page", phase = FinitePlaylistPhase.PAGEABLE,
        ))
        fixture.mutating = true
        queue.timelineInstalled = true
        fixture.changeTimeline(listOf("accepted-page"))
        fixture.mutating = false
        queue.fail()
        fixture.scheduler.request()
        advanceUntilIdle()

        assertEquals(listOf(listOf("accepted-page")), fixture.written)
        assertEquals(QueueType.YOUTUBE_PLAYLIST, fixture.snapshots.single().queueType)
        assertTrue(fixture.savePosition(700L))
        assertFalse(queue.hasUnacceptedFiniteReplacement())
    }

    @Test
    fun emptyFilteredFinitePage_preservesThePreviousCheckpointThroughNextPageFailure() = runTest {
        val fixture = Fixture(this)
        fixture.scheduler.request()
        advanceUntilIdle()
        val queue = fixture.beginFiniteReplacement(FinitePlaylistSnapshot(
            continuation = "next-page", phase = FinitePlaylistPhase.PAGEABLE,
        ))
        // The loader accepts a fully filtered first page and starts its continuation request.
        queue.timelineInstalled = true
        fixture.scheduler.request()
        advanceUntilIdle()
        assertEquals(listOf(listOf("seed")), fixture.written)
        assertNull(fixture.teardownSnapshot())

        queue.fail()
        fixture.scheduler.request()
        advanceUntilIdle()
        assertEquals("next-page", queue.snapshot.continuation)
        assertEquals(listOf(listOf("seed")), fixture.written)
        assertNull(fixture.snapshotIfAccepted())

        // stopAndClearPlayback switches the queue identity before clearing the player.
        fixture.queue = EmptyQueue
        fixture.changeTimeline(emptyList())
        advanceUntilIdle()
        assertEquals(emptyList<String>(), fixture.written.last())
        assertEquals(QueueType.LIST, fixture.snapshots.last().queueType)
    }

    @Test
    fun filteringAllLoadedFiniteItems_doesNotEraseTheContinuation() = runTest {
        val fixture = Fixture(this)
        val queue = fixture.beginFiniteReplacement(FinitePlaylistSnapshot(
            continuation = "next-page", phase = FinitePlaylistPhase.PAGEABLE,
        ))
        fixture.mutating = true
        queue.timelineInstalled = true
        fixture.changeTimeline(listOf("accepted-page"))
        fixture.mutating = false
        fixture.scheduler.request()
        advanceUntilIdle()

        fixture.changeTimeline(emptyList())
        advanceUntilIdle()
        queue.fail()
        fixture.scheduler.request()
        advanceUntilIdle()

        assertEquals(listOf(listOf("accepted-page")), fixture.written)
        assertNull(fixture.teardownSnapshot())
    }

    @Test
    fun teardownAfterInitialFailure_writesTheAcceptedManualQueue() = runTest {
        val fixture = Fixture(this)
        fixture.beginFiniteReplacement().fail()
        fixture.changeTimeline(listOf("manual-at-teardown"))
        // Teardown snapshots directly, before the scheduler's debounce has elapsed.
        val teardown = requireNotNull(fixture.teardownSnapshot())
        assertTrue(fixture.commit(teardown))
        advanceUntilIdle()

        assertEquals(listOf("manual-at-teardown"), fixture.written.first())
        assertEquals(QueueType.YOUTUBE_PLAYLIST, teardown.queueType)
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

    @Test
    fun replacementService_waitsForPendingTeardownBeforeReadingAndAdvancingGeneration() = runTest {
        val persistence = QueuePersistenceState()
        val previous = Fixture(this, persistence)
        previous.items = listOf("newer-B")
        val teardown = requireNotNull(previous.teardownSnapshot())
        val releaseWrite = CompletableDeferred<Unit>()
        var disk = listOf("older-A")
        persistence.teardownWrites.launch(this) {
            releaseWrite.await()
            if (previous.commit(teardown)) disk = teardown.items
        }
        var restored: List<String>? = null
        val restoration = launch {
            persistence.teardownWrites.awaitPendingWrites()
            restored = disk.toList()
            previous.invalidate()
        }
        runCurrent()

        assertNull(restored)
        assertEquals(0L, persistence.generation.get())
        releaseWrite.complete(Unit)
        restoration.join()

        assertEquals(listOf("newer-B"), restored)
        assertEquals(1L, persistence.generation.get())
        assertEquals(listOf(listOf("newer-B")), previous.written)
    }

    @Test
    fun pendingTeardown_doesNotOverwriteANewerUserCheckpointWhileRestorationWaits() = runTest {
        val persistence = QueuePersistenceState()
        val previous = Fixture(this, persistence)
        val current = Fixture(this, persistence)
        val teardown = requireNotNull(previous.teardownSnapshot())
        val releaseWrite = CompletableDeferred<Unit>()
        var accepted = true
        persistence.teardownWrites.launch(this) {
            releaseWrite.await()
            accepted = previous.commit(teardown)
        }
        val restoration = launch { persistence.teardownWrites.awaitPendingWrites() }
        runCurrent()
        current.items = listOf("newer-user-queue")
        assertTrue(current.commit(current.snapshot()))
        releaseWrite.complete(Unit)
        restoration.join()

        assertFalse(accepted)
        assertTrue(previous.written.isEmpty())
        assertEquals(listOf(listOf("newer-user-queue")), current.written)
    }

    private data class Snapshot(
        val checkpoint: QueueCheckpointToken,
        val items: List<String>,
        val queueType: QueueType,
    )

    private class Fixture(
        scope: CoroutineScope,
        private val persistence: QueuePersistenceState = QueuePersistenceState(),
    ) {
        var enabled = true
        var restoring = false
        var hydrating = false
        var mutating = false
        var items = listOf("seed")
        var queue: Queue = EmptyQueue
        var hasTimelineChanges = false
        val snapshots = mutableListOf<Snapshot>()
        val written = mutableListOf<List<String>>()
        val positions = mutableListOf<Long>()
        var readEnabled: suspend () -> Boolean = { enabled }
        var beforeWrite: suspend (Snapshot) -> Unit = {}
        val checkpointGate = QueueCheckpointGate(
            queue = { queue },
            transientMutation = { restoring || hydrating || mutating },
            mediaItemCount = { items.size },
        )
        val scheduler = QueueCheckpointScheduler(
            scope = { scope },
            generation = { persistence.generation.get() },
            enabled = { readEnabled() },
            blocked = { checkpointGate.blocked() },
            snapshot = { snapshotIfAccepted() },
            write = {
                beforeWrite(it)
                commit(it)
            },
        )

        fun beginFiniteReplacement(snapshot: FinitePlaylistSnapshot = FinitePlaylistSnapshot()): YouTubePlaylistQueue {
            val finite = YouTubePlaylistQueue(WatchEndpoint(playlistId = "fixture"), snapshot = snapshot)
            queue = finite
            checkpointGate.reset()
            finite.beginPlayback()
            finite.initialTimelineCleared = true
            changeTimeline(emptyList())
            return finite
        }

        fun changeTimeline(next: List<String>) {
            items = next
            checkpointGate.onTimelineChanged(items.isEmpty())
            hasTimelineChanges = true
            scheduler.request()
        }

        fun snapshotIfAccepted(): Snapshot? = if (checkpointGate.blocked()) null else snapshot()

        fun teardownSnapshot(): Snapshot? =
            if (TeardownQueuePersistence.shouldSave(enabled, items.size, hasTimelineChanges)) snapshotIfAccepted() else null

        fun snapshot(): Snapshot = Snapshot(
            persistence.checkpointOrder.nextSnapshot(),
            items.toList(),
            (if (items.isEmpty()) EmptyQueue else queue).toPersistQueue(null, emptyList(), 0, 0L).queueType,
        ).also { snapshots += it }

        fun savePosition(position: Long): Boolean {
            if (!enabled || checkpointGate.blocked() || items.isEmpty()) return false
            return synchronized(persistence.lock) {
                persistence.checkpointOrder.writePlayerStateIfCurrent(persistence.checkpointOrder.nextSnapshot()) {
                    positions += position
                }
            }
        }

        fun commit(snapshot: Snapshot): Boolean = synchronized(persistence.lock) {
            persistence.checkpointOrder.writeIfCurrent(snapshot.checkpoint) { written += snapshot.items }
        }

        fun invalidate() = synchronized(persistence.lock) { persistence.generation.incrementAndGet() }
    }
}

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
import moe.rukamori.archivetune.models.QueueData
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
    fun finiteReplacement_isUnacceptedFromPreparationUntilTimelineInstallation() {
        val queue = YouTubePlaylistQueue(WatchEndpoint(playlistId = "fixture"))
        assertTrue(queue.hasUnacceptedFiniteReplacement())

        queue.initialTimelineCleared = true
        assertTrue(queue.hasUnacceptedFiniteReplacement())

        queue.timelineInstalled = true
        assertFalse(queue.hasUnacceptedFiniteReplacement())
        assertFalse(EmptyQueue.hasUnacceptedFiniteReplacement())
    }

    @Test
    fun slowFinitePreparation_preservesThePreviousCheckpointBeforeTheInitialClear() = runTest {
        val fixture = Fixture(this)
        fixture.items = listOf("seed", "old-upcoming")
        fixture.scheduler.request()
        advanceUntilIdle()

        // cancelRestoredQueueHydration requests before playQueue assigns the new identity.
        fixture.scheduler.request()
        val queue = fixture.beginFiniteReplacement(clearInitialTimeline = false)
        val finishPreparation = CompletableDeferred<Unit>()
        val load = launch {
            finishPreparation.await()
            queue.initialTimelineCleared = true
            fixture.changeTimeline(emptyList())
            queue.fail()
        }
        runCurrent()
        advanceTimeBy(250L)
        runCurrent()

        assertFalse(queue.initialTimelineCleared)
        assertTrue(queue.hasUnacceptedFiniteReplacement())
        assertEquals(List(2) { listOf("seed", "old-upcoming") }, fixture.written)
        assertEquals(2, fixture.snapshots.size)
        assertTrue(fixture.savePosition(100L))
        assertEquals(QueueType.LIST, requireNotNull(fixture.teardownSnapshot()).queueType)

        // A nonempty filtering event during preparation must not accept the old timeline either.
        fixture.changeTimeline(listOf("seed"))
        advanceTimeBy(250L)
        runCurrent()
        assertEquals(listOf("seed"), fixture.written.last())
        finishPreparation.complete(Unit)
        load.join()
        advanceUntilIdle()
        assertEquals(List(2) { listOf("seed", "old-upcoming") } + listOf(listOf("seed")), fixture.written)
        assertNull(fixture.teardownSnapshot())

        queue.accept(YouTubePlaylistQueue.Proposal(
            FinitePlaylistSnapshot(phase = FinitePlaylistPhase.EXHAUSTED), emptyList(),
        ))
        fixture.installTimeline(listOf("replacement"))
        advanceUntilIdle()
        assertEquals(listOf("replacement"), fixture.written.last())
        assertEquals(QueueType.YOUTUBE_PLAYLIST, fixture.snapshots.last().queueType)
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

        fixture.removeItemsByUser(emptyList())
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
    fun retainedTimelineDuringFailedReplacement_savesPositionWithThePreviousIdentity() = runTest {
        val fixture = Fixture(this)
        val queue = fixture.beginFiniteReplacement()
        // Without installation/acceptance, these items still belong to the previous identity.
        fixture.items = listOf("retained")
        queue.fail()
        assertTrue(fixture.savePosition(500L))
        assertEquals(listOf(500L), fixture.positions)
        assertEquals(QueueType.LIST, requireNotNull(fixture.snapshotIfAccepted()).queueType)

        fixture.changeTimeline(listOf("manual"))
        advanceUntilIdle()
        assertTrue(fixture.savePosition(600L))
        assertEquals(listOf(500L, 600L), fixture.positions)
    }

    @Test
    fun nextPageFailure_keepsTheAcceptedNonemptyTimelineCheckpointable() = runTest {
        val fixture = Fixture(this)
        val queue = fixture.beginFiniteReplacement(FinitePlaylistSnapshot(
            continuation = "next-page", phase = FinitePlaylistPhase.PAGEABLE,
        ))
        fixture.mutating = true
        fixture.installTimeline(listOf("accepted-page"))
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
    fun preloadFilteredToEmpty_whileFirstPagePending_preservesThePlaylistCheckpoint() = runTest {
        val fixture = Fixture(this)
        val queue = fixture.beginFinitePreload()
        advanceUntilIdle()
        assertEquals(QueueType.YOUTUBE_PLAYLIST, fixture.snapshots.single().queueType)

        fixture.changeTimeline(emptyList())
        advanceUntilIdle()

        assertTrue(queue.timelineInstalled)
        assertEquals(FinitePlaylistPhase.INITIALIZING, queue.snapshot.phase)
        assertNull(queue.snapshot.continuation)
        assertEquals(listOf(listOf("preload")), fixture.written)
        assertEquals(1, fixture.snapshots.size)
        assertNull(fixture.teardownSnapshot())
    }

    @Test
    fun preloadFilteredToEmpty_afterFirstPageFailure_preservesThePlaylistCheckpoint() = runTest {
        val fixture = Fixture(this)
        val queue = fixture.beginFinitePreload()
        advanceUntilIdle()

        queue.fail()
        fixture.changeTimeline(emptyList())
        advanceUntilIdle()

        assertEquals(FinitePlaylistPhase.FAILED, queue.snapshot.phase)
        assertNull(queue.snapshot.continuation)
        assertEquals(listOf(listOf("preload")), fixture.written)
        assertEquals(QueueType.YOUTUBE_PLAYLIST, fixture.snapshots.single().queueType)
        assertNull(fixture.snapshotIfAccepted())
        assertNull(fixture.teardownSnapshot())
    }

    @Test
    fun manualRemovalOfLastPreloadAfterFirstPageFailure_acceptsAnEmptyCheckpointAndTeardown() = runTest {
        val fixture = Fixture(this)
        val queue = fixture.beginFinitePreload()
        advanceUntilIdle()
        val preloadSnapshot = fixture.snapshots.single()
        queue.fail()

        fixture.removeItemsByUser(emptyList())
        val teardown = requireNotNull(fixture.teardownSnapshot())
        assertTrue(teardown.items.isEmpty())
        assertEquals(QueueType.LIST, teardown.queueType)
        advanceUntilIdle()

        assertTrue(queue.timelineInstalled)
        assertEquals(FinitePlaylistPhase.FAILED, queue.snapshot.phase)
        assertNull(queue.snapshot.continuation)
        assertEquals(listOf(listOf("preload"), emptyList<String>()), fixture.written)
        assertFalse(fixture.commit(preloadSnapshot))
        assertFalse(fixture.commit(teardown))

        // A replacement must reset the explicit-removal acceptance too.
        fixture.beginFiniteReplacement().fail()
        advanceUntilIdle()
        assertEquals(listOf(listOf("preload"), emptyList<String>()), fixture.written)
        assertNull(fixture.teardownSnapshot())
    }

    @Test
    fun nonemptyManualRemoval_doesNotAcceptALaterFilteredEmptyQueue() = runTest {
        val fixture = Fixture(this)
        val queue = fixture.beginFinitePreload()
        fixture.changeTimeline(listOf("preload", "other"))
        advanceUntilIdle()
        queue.fail()

        fixture.removeItemsByUser(listOf("preload"))
        advanceUntilIdle()
        assertEquals(listOf("preload"), fixture.written.last())

        fixture.changeTimeline(emptyList())
        advanceUntilIdle()
        assertEquals(listOf("preload"), fixture.written.last())
        assertNull(fixture.teardownSnapshot())
    }

    @Test
    fun preloadFilteredToEmpty_afterConfirmedFinalPage_allowsAnEmptyCheckpoint() = runTest {
        val fixture = Fixture(this)
        val queue = fixture.beginFinitePreload()
        advanceUntilIdle()
        fixture.changeTimeline(emptyList())
        advanceUntilIdle()
        assertEquals(listOf(listOf("preload")), fixture.written)

        queue.accept(YouTubePlaylistQueue.Proposal(
            FinitePlaylistSnapshot(phase = FinitePlaylistPhase.EXHAUSTED), emptyList(),
        ))
        fixture.scheduler.request()
        advanceUntilIdle()

        assertTrue(queue.confirmedExhausted)
        assertNull(queue.snapshot.continuation)
        assertEquals(listOf(listOf("preload"), emptyList<String>()), fixture.written)
        assertEquals(QueueType.LIST, fixture.snapshots.last().queueType)
        assertTrue(fixture.commit(requireNotNull(fixture.teardownSnapshot())))
    }

    @Test
    fun preloadFilteredToEmpty_explicitClear_allowsAnEmptyCheckpoint() = runTest {
        val fixture = Fixture(this)
        fixture.beginFinitePreload()
        advanceUntilIdle()
        fixture.changeTimeline(emptyList())
        advanceUntilIdle()
        assertEquals(listOf(listOf("preload")), fixture.written)

        // Explicit clear changes the queue identity, even when filtering already emptied it.
        fixture.clearQueueIdentity()
        advanceUntilIdle()

        assertEquals(listOf(listOf("preload"), emptyList<String>()), fixture.written)
        assertEquals(QueueType.LIST, fixture.snapshots.last().queueType)
        assertTrue(fixture.commit(requireNotNull(fixture.teardownSnapshot())))
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
        fixture.installTimeline(emptyList())
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
        fixture.clearQueueIdentity()
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
        fixture.installTimeline(listOf("accepted-page"))
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
    fun manualAdditionBeforeAcceptance_doesNotLetAFilteredEmptyPageEraseTheContinuation() = runTest {
        val fixture = Fixture(this)
        val queue = fixture.beginFiniteReplacement(FinitePlaylistSnapshot(
            continuation = "next-page", phase = FinitePlaylistPhase.PAGEABLE,
        ))
        fixture.changeTimeline(listOf("manual"))
        advanceUntilIdle()
        assertEquals(listOf(listOf("manual")), fixture.written)

        // The loader later accepts its first page while the earlier user edit is still remembered.
        fixture.mutating = true
        fixture.installTimeline(listOf("manual", "accepted-page"))
        fixture.mutating = false
        advanceUntilIdle()
        assertEquals(listOf("manual", "accepted-page"), fixture.written.last())

        fixture.mutating = true
        fixture.changeTimeline(emptyList())
        fixture.mutating = false
        queue.fail()
        fixture.scheduler.request()
        advanceUntilIdle()

        assertEquals(listOf("manual", "accepted-page"), fixture.written.last())
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

    internal data class Snapshot(
        val checkpoint: QueueCheckpointToken,
        val items: List<String>,
        val queueType: QueueType,
        val queueData: QueueData?,
        val title: String?,
    )

    internal class Fixture(
        scope: CoroutineScope,
        private val persistence: QueuePersistenceState = QueuePersistenceState(),
    ) {
        var enabled = true
        var restoring = false
        var hydrating = false
        var mutating = false
        var items = listOf("seed")
        var queue: Queue = EmptyQueue
        var queueTitle: String? = null
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
            title = { queueTitle },
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

        fun beginFiniteReplacement(
            snapshot: FinitePlaylistSnapshot = FinitePlaylistSnapshot(),
            clearInitialTimeline: Boolean = true,
        ): YouTubePlaylistQueue {
            val finite = YouTubePlaylistQueue(WatchEndpoint(playlistId = "fixture"), snapshot = snapshot)
            finite.beginPlayback()
            beginReplacement(finite)
            if (clearInitialTimeline) {
                finite.initialTimelineCleared = true
                checkpointGate.mutateTimeline { changeTimeline(emptyList()) }
            }
            return finite
        }

        fun beginFinitePreload(): YouTubePlaylistQueue {
            val finite = beginFiniteReplacement()
            // MusicService installs the preload before accepting the first page proposal.
            installTimeline(listOf("preload"))
            return finite
        }

        fun beginReplacement(nextQueue: Queue) {
            checkpointGate.beginReplacement(nextQueue)
            queue = nextQueue
            queueTitle = null
        }

        fun installTimeline(next: List<String>) {
            checkpointGate.mutateTimeline {
                (queue as? YouTubePlaylistQueue)?.timelineInstalled = true
                changeTimeline(next)
            }
            checkpointGate.onReplacementInstalled(queue)
            scheduler.request()
        }

        fun finishReplacement(replacement: Queue = queue) {
            checkpointGate.onReplacementFinished(replacement)
            scheduler.request()
        }

        fun installExternalTimeline(next: List<String>, installedQueue: Queue = EmptyQueue, title: String? = null) {
            finishReplacement()
            checkpointGate.installExternalTimeline({ changeTimeline(next) }, {
                queue = installedQueue
                queueTitle = title
                queue
            }, scheduler::request)
        }

        fun addItemsByUser(next: List<String>) {
            changeTimeline(next)
            checkpointGate.onUserAddition()
            scheduler.request()
        }

        fun changeTimeline(next: List<String>) {
            items = next
            checkpointGate.onTimelineChanged(items.isEmpty())
            hasTimelineChanges = true
            scheduler.request()
        }

        fun removeItemsByUser(next: List<String>) {
            changeTimeline(next)
            checkpointGate.onUserRemoval()
        }

        fun clearQueueIdentity() {
            queue = EmptyQueue
            queueTitle = null
            checkpointGate.onUserClear()
            scheduler.request()
        }

        fun snapshotIfAccepted(): Snapshot? = if (checkpointGate.blocked()) null else snapshot()

        fun teardownSnapshot(): Snapshot? =
            if (TeardownQueuePersistence.shouldSave(enabled, items.size, hasTimelineChanges)) snapshotIfAccepted() else null

        fun snapshot(): Snapshot {
            val persisted = (if (items.isEmpty()) EmptyQueue else checkpointGate.persistenceQueue)
                .toPersistQueue(checkpointGate.persistenceTitle.takeIf { items.isNotEmpty() }, emptyList(), 0, 0L)
            return Snapshot(
                persistence.checkpointOrder.nextSnapshot(), items.toList(), persisted.queueType, persisted.queueData, persisted.title,
            ).also { snapshots += it }
        }

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

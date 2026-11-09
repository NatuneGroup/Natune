package moe.rukamori.archivetune.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.models.QueueData
import moe.rukamori.archivetune.playback.QueueCheckpointSchedulerTest.Fixture
import moe.rukamori.archivetune.playback.queues.EmptyQueue
import moe.rukamori.archivetune.playback.queues.FinitePlaylistPhase
import moe.rukamori.archivetune.playback.queues.FinitePlaylistSnapshot
import moe.rukamori.archivetune.playback.queues.PlaylistLoadOwner
import moe.rukamori.archivetune.playback.queues.PlaylistLoadState
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.playback.queues.YouTubePlaylistQueue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalQueueTimelineInstallationTest {
    @Test
    fun serviceDecision_sameOccurrenceReplayDuringStartupBackfill_keepsTheWholeCheckpoint() = runTest {
        val fixture = Fixture(this)
        val restored = YouTubeQueue(WatchEndpoint(playlistId = "RDrestored"))
        val full = listOf("prefix", "current", "upcoming", "suffix")
        fixture.installExternalTimeline(full, restored, "Restored title")
        advanceUntilIdle()
        fixture.hydrating = true
        val target = QueueCheckpointTestPlayer(listOf(item("current"), item("upcoming")))
        target.changed = { fixture.changeTimeline(target.items.map { it.mediaId }) }
        fixture.changeTimeline(listOf("current", "upcoming"))
        var generation = 7L
        val backfill = launch {
            try {
                delay(2_000L)
                target.player.addMediaItems(0, listOf(item("prefix")))
                target.player.addMediaItems(listOf(item("suffix")))
            } finally {
                fixture.hydrating = false
                fixture.scheduler.request()
            }
        }
        runCurrent()
        val session = fixture.serviceSession(target, cancelObsoleteWork = {
            generation++
            backfill.cancel()
            fixture.hydrating = false
        })

        session.setMediaItems(target.items.toList(), 1, 456L)
        runCurrent()

        assertEquals(7L, generation)
        assertTrue(backfill.isActive)
        assertTrue(fixture.hydrating)
        assertTrue(fixture.checkpointGate.blocked())
        assertSame(restored, fixture.queue)
        assertEquals(1, target.currentIndex)
        assertEquals(456L, target.position)
        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(listOf(full), fixture.written)
        advanceTimeBy(1L)
        advanceUntilIdle()

        assertFalse(backfill.isCancelled)
        assertFalse(fixture.hydrating)
        assertEquals(full, fixture.written.last())
        assertEquals("Restored title", fixture.snapshots.last().title)
        assertEquals("RDrestored", (fixture.snapshots.last().queueData as QueueData.YouTubeData).playlistId)
    }

    @Test
    fun serviceDecision_sameOccurrenceReplayDuringFinitePreload_keepsFirstPageAndRetryState() = runTest {
        val fixture = Fixture(this)
        val finite = fixture.beginFinitePreload()
        fixture.queueTitle = "Finite title"
        advanceUntilIdle()
        val target = QueueCheckpointTestPlayer(listOf(item("preload")))
        target.changed = { fixture.changeTimeline(target.items.map { it.mediaId }) }
        val owner = PlaylistLoadOwner()
        var generation = 11L
        val ticket = requireNotNull(owner.begin(generation, finite))
        val firstPage = CompletableDeferred<YouTubePlaylistQueue.Proposal>()
        val retry = {}
        var retryAction: (() -> Unit)? = retry
        var state = PlaylistLoadState(loading = true, showDialog = true)
        val loader = launch {
            try {
                val proposal = firstPage.await()
                if (ticket.acceptIfCurrent(generation, fixture.queue) {
                    finite.accept(proposal)
                    target.player.addMediaItems(listOf(item("first-page-tail")))
                    fixture.checkpointGate.onReplacementInstalled(finite)
                }) {
                    state = PlaylistLoadState()
                    retryAction = null
                }
            } finally {
                ticket.finish()
                fixture.scheduler.request()
            }
        }
        runCurrent()
        val session = fixture.serviceSession(target, cancelObsoleteWork = {
            generation++
            owner.cancel()
            loader.cancel()
            retryAction = null
            state = PlaylistLoadState()
            fixture.finishReplacement()
        })

        session.setMediaItem(target.items.single(), 789L)
        runCurrent()

        assertTrue(loader.isActive)
        assertEquals(11L, generation)
        assertTrue(ticket.isCurrent(generation, fixture.queue))
        assertSame(retry, retryAction)
        assertTrue(state.loading)
        assertTrue(state.showDialog)
        assertTrue(finite.initialTimelineCleared)
        assertTrue(finite.timelineInstalled)
        assertEquals(FinitePlaylistPhase.INITIALIZING, finite.snapshot.phase)
        assertSame(finite, fixture.checkpointGate.persistenceQueue)
        firstPage.complete(YouTubePlaylistQueue.Proposal(
            FinitePlaylistSnapshot(continuation = "next-page", phase = FinitePlaylistPhase.PAGEABLE), emptyList(),
        ))
        loader.join()
        advanceUntilIdle()

        assertFalse(loader.isCancelled)
        assertFalse(state.loading)
        assertEquals(listOf("preload", "first-page-tail"), fixture.written.last())
        assertEquals("next-page", (fixture.snapshots.last().queueData as QueueData.YouTubePlaylistData).snapshot.continuation)
        assertEquals(789L, target.position)
    }

    @Test
    fun serviceDecision_sameMediaIdsWithNewOccurrences_replacesAndCancelsObsoleteLoading() = runTest {
        val fixture = Fixture(this)
        fixture.beginFinitePreload()
        advanceUntilIdle()
        val target = QueueCheckpointTestPlayer(listOf(item("preload")))
        target.changed = { fixture.changeTimeline(target.items.map { it.mediaId }) }
        var cancellations = 0
        val waiting = CompletableDeferred<Unit>()
        val loader = launch { waiting.await() }
        runCurrent()

        fixture.serviceSession(target, cancelObsoleteWork = {
            cancellations++
            loader.cancel()
            fixture.finishReplacement()
        }).setMediaItems(listOf(item("preload")))
        loader.join()
        advanceUntilIdle()

        assertEquals(1, cancellations)
        assertTrue(loader.isCancelled)
        assertSame(EmptyQueue, fixture.queue)
        assertSame(EmptyQueue, fixture.checkpointGate.persistenceQueue)
    }

    @Test
    fun retentionRequiresSameInstalledIdentityAndOccurrenceProof_evenForSourceRefresh() {
        val installed = YouTubeQueue(WatchEndpoint(playlistId = "RDinstalled"))
        val other = YouTubeQueue(WatchEndpoint(playlistId = "RDinstalled"))
        assertFalse(externalTimelineReplacesQueue(installed, installed, true))
        assertTrue(externalTimelineReplacesQueue(installed, installed, false))
        assertTrue(externalTimelineReplacesQueue(other, installed, true))
        assertTrue(externalTimelineReplacesQueue(null, installed, true))
    }

    private fun Fixture.serviceSession(target: QueueCheckpointTestPlayer, cancelObsoleteWork: () -> Unit): Player =
        queueSessionTimelinePlayer(target.player, checkpointGate, { _, installed, title, replay, mutation ->
            installServiceQueueTimeline(checkpointGate, installed, replay, cancelObsoleteWork, mutation,
                acceptIdentity = {
                    queue = installed ?: EmptyQueue
                    queueTitle = title
                    queue
                },
                save = scheduler::request,
                evaluateLoading = {},
            )
        }, scheduler::request)

    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()
}

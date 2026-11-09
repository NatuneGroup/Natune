package moe.rukamori.archivetune.playback

import androidx.media3.common.MediaItem
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.models.QueueData
import moe.rukamori.archivetune.models.QueueType
import moe.rukamori.archivetune.playback.QueueCheckpointSchedulerTest.Fixture
import moe.rukamori.archivetune.playback.queues.EmptyQueue
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueCheckpointReplacementTest {
    @Test
    fun automaticFilteringAfterFailedFirstPageAndManualAdd_preservesThePlaylistCheckpoint() = runTest {
        val fixture = Fixture(this)
        val queue = fixture.beginFiniteReplacement()
        queue.fail()
        fixture.addItemsByUser(listOf("manual-video"))
        advanceUntilIdle()
        val manual = fixture.snapshots.single()
        assertEquals("fixture", (manual.queueData as QueueData.YouTubePlaylistData).playlistId)

        // Hide videos removes the manual item without invoking the user-removal hook.
        fixture.changeTimeline(emptyList())
        assertNull(fixture.teardownSnapshot())
        advanceUntilIdle()

        assertFalse(queue.timelineInstalled)
        assertTrue(queue.hasUnacceptedFiniteReplacement())
        assertEquals(listOf(listOf("manual-video")), fixture.written)
        assertEquals(1, fixture.snapshots.size)
        assertNull(fixture.snapshotIfAccepted())
        assertNull(fixture.teardownSnapshot())
    }

    @Test
    fun pendingRadioReplacement_checkpointsCurrentItemsWithThePreviousIdentityIncludingAtTeardown() = runTest {
        val fixture = Fixture(this)
        fixture.items = listOf("old-current", "old-upcoming")
        fixture.scheduler.request()
        advanceUntilIdle()

        // cancelRestoredQueueHydration requests before playQueue assigns the radio identity.
        fixture.scheduler.request()
        fixture.beginReplacement(YouTubeQueue(WatchEndpoint(playlistId = "RDnew-radio")))
        runCurrent()
        advanceTimeBy(250L)
        runCurrent()
        assertEquals(List(2) { listOf("old-current", "old-upcoming") }, fixture.written)
        assertEquals(QueueType.LIST, requireNotNull(fixture.teardownSnapshot()).queueType)

        // Automatic filtering of the old songs cannot install the pending replacement.
        fixture.changeTimeline(listOf("old-current"))
        advanceUntilIdle()
        assertEquals(listOf("old-current"), fixture.written.last())
        assertTrue(fixture.savePosition(100L))
        assertEquals(QueueType.LIST, requireNotNull(fixture.snapshotIfAccepted()).queueType)
        assertEquals(QueueType.LIST, requireNotNull(fixture.teardownSnapshot()).queueType)

        // Filtering all old songs also changes currentQueue to EmptyQueue automatically.
        fixture.queue = EmptyQueue
        fixture.changeTimeline(emptyList())
        advanceUntilIdle()
        assertEquals(emptyList<String>(), fixture.written.last())
        assertEquals(QueueType.LIST, requireNotNull(fixture.teardownSnapshot()).queueType)
    }

    @Test
    fun installedRadioReplacement_savesItsTimelineAndStillAcceptsAutomaticContinuationItems() = runTest {
        val fixture = Fixture(this)
        fixture.scheduler.request()
        advanceUntilIdle()
        val radio = YouTubeQueue(WatchEndpoint(playlistId = "RDinstalled", params = "radio-params"))
        fixture.beginReplacement(radio)
        fixture.installTimeline(listOf("radio-current", "radio-upcoming"))
        advanceUntilIdle()

        val installed = fixture.snapshots.last()
        assertEquals(listOf("radio-current", "radio-upcoming"), installed.items)
        assertEquals(QueueType.YOUTUBE, installed.queueType)
        assertEquals("RDinstalled", (installed.queueData as QueueData.YouTubeData).playlistId)
        assertEquals("radio-params", (installed.queueData as QueueData.YouTubeData).endpointParams)

        // Continuation and Automix additions are checkpointable once the timeline is installed.
        fixture.changeTimeline(listOf("radio-current", "radio-upcoming", "automatic-next-page"))
        advanceUntilIdle()
        assertEquals(listOf("radio-current", "radio-upcoming", "automatic-next-page"), fixture.written.last())
        assertTrue(fixture.savePosition(200L))
        assertEquals(QueueType.YOUTUBE, requireNotNull(fixture.teardownSnapshot()).queueType)
    }

    @Test
    fun radioPreloadPresence_doesNotAcceptTheReplacementUntilItIsInstalled() = runTest {
        val fixture = Fixture(this)
        fixture.scheduler.request()
        advanceUntilIdle()
        val radio = YouTubeQueue(
            WatchEndpoint(playlistId = "RDpreload"),
            preloadItem = MediaMetadata("preload", "Preload", emptyList(), 180),
        )
        fixture.beginReplacement(radio)
        fixture.scheduler.request()
        advanceUntilIdle()
        assertEquals(List(2) { listOf("seed") }, fixture.written)
        assertEquals(QueueType.LIST, requireNotNull(fixture.teardownSnapshot()).queueType)

        fixture.installTimeline(listOf("preload"))
        advanceUntilIdle()
        assertEquals(listOf("preload"), fixture.written.last())
        assertEquals(QueueType.YOUTUBE, fixture.snapshots.last().queueType)
        assertTrue(fixture.savePosition(300L))
    }

    @Test
    fun failedRadioInitialLoad_userAdditionAcceptsACheckpointAndTeardown() = runTest {
        val fixture = Fixture(this)
        fixture.scheduler.request()
        advanceUntilIdle()
        val radio = YouTubeQueue(WatchEndpoint(playlistId = "RDfailed"))
        fixture.beginReplacement(radio)
        val initial = CompletableDeferred<Queue.Status>()
        var failed = false
        val load = launch {
            try {
                val status = initial.await()
                fixture.installTimeline(status.items.map { it.mediaId })
            } catch (_: IOException) {
                failed = true
            } finally {
                fixture.finishReplacement(radio)
            }
        }
        runCurrent()
        initial.completeExceptionally(IOException("Fixture initial request failed"))
        load.join()
        fixture.scheduler.request()
        advanceUntilIdle()
        assertTrue(failed)
        assertEquals(List(2) { listOf("seed") }, fixture.written)
        assertEquals(QueueType.LIST, requireNotNull(fixture.teardownSnapshot()).queueType)

        fixture.addItemsByUser(listOf("seed", "manual-after-failure"))
        val teardown = requireNotNull(fixture.teardownSnapshot())
        assertEquals(listOf("seed", "manual-after-failure"), teardown.items)
        assertEquals(QueueType.LIST, teardown.queueType)
        assertNull(teardown.queueData)
        advanceUntilIdle()
        assertEquals(listOf("seed", "manual-after-failure"), fixture.written.last())
        assertTrue(fixture.savePosition(400L))
    }

    @Test
    fun pendingRadioReplacement_userRemovalAcceptsTheEditedTimeline() = runTest {
        val fixture = Fixture(this)
        fixture.items = listOf("current", "remove-me")
        fixture.beginReplacement(YouTubeQueue(WatchEndpoint(playlistId = "RDmanual-removal")))
        fixture.removeItemsByUser(listOf("current"))
        advanceUntilIdle()

        assertEquals(listOf(listOf("current")), fixture.written)
        assertEquals(QueueType.LIST, fixture.snapshots.single().queueType)
        assertTrue(fixture.savePosition(500L))
        assertEquals(listOf("current"), requireNotNull(fixture.teardownSnapshot()).items)
    }

    @Test
    fun pendingRadioReplacement_clearIdentitySavesEvenWithoutATimelineEvent() = runTest {
        val fixture = Fixture(this)
        fixture.beginReplacement(YouTubeQueue(WatchEndpoint(playlistId = "RDclear")))
        fixture.scheduler.request()
        advanceUntilIdle()
        assertEquals(listOf(listOf("seed")), fixture.written)

        // Clear queue keeps the current song; an identity-only clear still requests a save.
        fixture.clearQueueIdentity()
        advanceUntilIdle()
        assertEquals(List(2) { listOf("seed") }, fixture.written)
        assertEquals(QueueType.LIST, fixture.snapshots.last().queueType)
        assertEquals(QueueType.LIST, requireNotNull(fixture.teardownSnapshot()).queueType)
    }

    @Test
    fun pendingRadioReplacement_stopAndClearAcceptsAnEmptyCheckpoint() = runTest {
        val fixture = Fixture(this)
        fixture.scheduler.request()
        advanceUntilIdle()
        fixture.beginReplacement(YouTubeQueue(WatchEndpoint(playlistId = "RDstop")))
        fixture.clearQueueIdentity()
        fixture.changeTimeline(emptyList())
        val teardown = requireNotNull(fixture.teardownSnapshot())
        assertTrue(teardown.items.isEmpty())
        advanceUntilIdle()

        assertEquals(listOf(listOf("seed"), emptyList<String>()), fixture.written)
        assertEquals(QueueType.LIST, fixture.snapshots.last().queueType)
    }

    @Test
    fun synchronousListQueueReplacement_savesNormallyAfterItsInitialInstallation() = runTest {
        val fixture = Fixture(this)
        val list = ListQueue(
            items = listOf(MediaItem.Builder().setMediaId("local-song").build()),
            finite = true,
        )
        fixture.beginReplacement(list)
        val initial = list.getInitialStatus()
        fixture.installTimeline(initial.items.map { it.mediaId })
        advanceUntilIdle()

        assertEquals(listOf(listOf("local-song")), fixture.written)
        assertEquals(QueueType.LIST, fixture.snapshots.single().queueType)
        assertTrue(fixture.savePosition(600L))
        assertEquals(listOf("local-song"), requireNotNull(fixture.teardownSnapshot()).items)
    }

    @Test
    fun startupRadioHydration_doesNotRequireAReplacementInstallation() = runTest {
        val fixture = Fixture(this)
        fixture.hydrating = true
        // Startup restores an existing identity without going through playQueue.
        fixture.queue = YouTubeQueue(WatchEndpoint(playlistId = "RDrestored"))
        fixture.changeTimeline(listOf("restored-window"))
        advanceUntilIdle()
        assertTrue(fixture.written.isEmpty())

        fixture.changeTimeline(listOf("prefix", "restored-window", "suffix"))
        fixture.checkpointGate.onReplacementInstalled(fixture.queue)
        fixture.hydrating = false
        fixture.scheduler.request()
        advanceUntilIdle()
        assertEquals(listOf(listOf("prefix", "restored-window", "suffix")), fixture.written)
        assertEquals("RDrestored", (fixture.snapshots.single().queueData as QueueData.YouTubeData).playlistId)
    }

    @Test
    fun supersededInstallation_doesNotAcceptTheNewerReplacement() = runTest {
        val fixture = Fixture(this)
        val older = YouTubeQueue(WatchEndpoint(playlistId = "RDolder"))
        fixture.beginReplacement(older)
        fixture.beginReplacement(YouTubeQueue(WatchEndpoint(playlistId = "RDnewer")))
        fixture.checkpointGate.onReplacementInstalled(older)
        fixture.scheduler.request()
        advanceUntilIdle()

        assertEquals(listOf(listOf("seed")), fixture.written)
        assertEquals(QueueType.LIST, requireNotNull(fixture.teardownSnapshot()).queueType)
        fixture.installTimeline(listOf("newer-radio"))
        advanceUntilIdle()
        assertEquals(listOf(listOf("seed"), listOf("newer-radio")), fixture.written)
        assertEquals("RDnewer", (fixture.snapshots.last().queueData as QueueData.YouTubeData).playlistId)
    }
}

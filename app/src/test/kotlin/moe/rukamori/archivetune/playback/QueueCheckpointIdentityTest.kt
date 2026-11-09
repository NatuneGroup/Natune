package moe.rukamori.archivetune.playback

import androidx.media3.common.MediaItem
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
import moe.rukamori.archivetune.playback.queues.FinitePlaylistPhase
import moe.rukamori.archivetune.playback.queues.FinitePlaylistSnapshot
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.playback.queues.YouTubePlaylistQueue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueCheckpointIdentityTest {
    @Test
    fun pendingReplacement_writesCurrentItemsPositionAndTeardownWithThePreviousIdentity() = runTest {
        for (replacement in listOf<Queue>(
            YouTubeQueue(WatchEndpoint(playlistId = "RDreplacement")),
            YouTubePlaylistQueue(WatchEndpoint(playlistId = "replacement-playlist")),
        )) {
            val fixture = Fixture(this)
            val previous = YouTubeQueue(WatchEndpoint(playlistId = "RDprevious", params = "previous-params"))
            fixture.installExternalTimeline(listOf("current", "upcoming"), previous, "Previous title")
            advanceUntilIdle()

            fixture.beginReplacement(replacement)
            fixture.scheduler.request()
            runCurrent()
            advanceTimeBy(250L)
            runCurrent()

            assertFalse(fixture.checkpointGate.blocked())
            assertSame(previous, fixture.checkpointGate.persistenceQueue)
            val checkpoint = fixture.snapshots.last()
            assertEquals(listOf("current", "upcoming"), checkpoint.items)
            assertEquals("Previous title", checkpoint.title)
            assertEquals("RDprevious", (checkpoint.queueData as QueueData.YouTubeData).playlistId)
            assertEquals("previous-params", (checkpoint.queueData as QueueData.YouTubeData).endpointParams)
            assertTrue(fixture.savePosition(321L))
            assertEquals(checkpoint.queueData, requireNotNull(fixture.teardownSnapshot()).queueData)

            // Filtering the retained timeline changes items, never the replacement's identity.
            fixture.changeTimeline(listOf("current"))
            advanceUntilIdle()
            assertEquals(listOf("current"), fixture.written.last())
            assertSame(previous, fixture.checkpointGate.persistenceQueue)
        }
    }

    @Test
    fun failedOrEmptyRadioThenExternalInstallation_acceptsTheExternalIdentityAndKeepsSaving() = runTest {
        for (failed in listOf(false, true)) {
            for (external in listOf<Queue>(EmptyQueue, YouTubeQueue(WatchEndpoint(playlistId = "RDexternal")))) {
                val fixture = Fixture(this)
                val previous = YouTubeQueue(WatchEndpoint(playlistId = "RDprevious"))
                fixture.installExternalTimeline(listOf("previous-track"), previous)
                advanceUntilIdle()
                val replacement = YouTubeQueue(WatchEndpoint(playlistId = "RDfailed-or-empty"))
                fixture.beginReplacement(replacement)
                val result = CompletableDeferred<Queue.Status>()
                val loader = launch {
                    try {
                        val status = result.await()
                        if (status.items.isNotEmpty()) fixture.installTimeline(status.items.map { it.mediaId })
                    } catch (_: IOException) {
                        // The radio loader leaves the previous timeline playing.
                    } finally {
                        fixture.finishReplacement(replacement)
                    }
                }
                runCurrent()
                if (failed) result.completeExceptionally(IOException("Radio failed"))
                else result.complete(Queue.Status(null, emptyList(), 0))
                loader.join()
                advanceUntilIdle()
                assertSame(previous, fixture.checkpointGate.persistenceQueue)
                assertFalse(fixture.checkpointGate.blocked())

                fixture.installExternalTimeline(listOf("external-track"), external, "External title")
                advanceUntilIdle()
                val installed = fixture.snapshots.last()
                assertEquals(listOf("external-track"), fixture.written.last())
                assertSame(external, fixture.checkpointGate.persistenceQueue)
                assertEquals("External title", installed.title)
                if (external === EmptyQueue) {
                    assertEquals(QueueType.LIST, installed.queueType)
                    assertNull(installed.queueData)
                } else {
                    assertEquals("RDexternal", (installed.queueData as QueueData.YouTubeData).playlistId)
                }
                assertTrue(fixture.savePosition(456L))
                assertEquals(installed.queueData, requireNotNull(fixture.teardownSnapshot()).queueData)
            }
        }
    }

    @Test
    fun cancellationDuringSuspendedPreloadPreparation_keepsAddRemoveAndEmptyRemovalSaveable() = runTest {
        val fixture = Fixture(this)
        val previous = YouTubeQueue(WatchEndpoint(playlistId = "RDprevious"))
        fixture.installExternalTimeline(listOf("current", "upcoming"), previous, "Previous title")
        advanceUntilIdle()
        val replacement = fixture.beginFiniteReplacement(clearInitialTimeline = false)
        val prepared = CompletableDeferred<Unit>()
        val loader = launch {
            try {
                prepared.await() // The real preload filter suspends while reading blocked artists.
                replacement.initialTimelineCleared = true
                fixture.checkpointGate.mutateTimeline { fixture.changeTimeline(emptyList()) }
            } catch (error: CancellationException) {
                replacement.fail()
                throw error
            } finally {
                fixture.finishReplacement(replacement)
            }
        }
        runCurrent()
        loader.cancel()
        loader.join()
        advanceUntilIdle()

        assertFalse(replacement.initialTimelineCleared)
        assertFalse(replacement.timelineInstalled)
        assertEquals(FinitePlaylistPhase.FAILED, replacement.snapshot.phase)
        assertFalse(fixture.checkpointGate.blocked())
        assertSame(previous, fixture.checkpointGate.persistenceQueue)

        fixture.addItemsByUser(listOf("current", "upcoming", "manual"))
        advanceUntilIdle()
        assertEquals(listOf("current", "upcoming", "manual"), fixture.written.last())
        assertEquals("RDprevious", (fixture.snapshots.last().queueData as QueueData.YouTubeData).playlistId)
        assertEquals("Previous title", fixture.snapshots.last().title)
        fixture.removeItemsByUser(listOf("current"))
        advanceUntilIdle()
        assertEquals(listOf("current"), fixture.written.last())
        assertSame(previous, fixture.checkpointGate.persistenceQueue)
        assertTrue(fixture.savePosition(789L))

        fixture.removeItemsByUser(emptyList())
        advanceUntilIdle()
        assertEquals(emptyList<String>(), fixture.written.last())
        assertEquals(QueueType.LIST, fixture.snapshots.last().queueType)
        assertSame(EmptyQueue, fixture.checkpointGate.persistenceQueue)
        assertTrue(requireNotNull(fixture.teardownSnapshot()).items.isEmpty())
        assertFalse(replacement.initialTimelineCleared)
        assertFalse(replacement.timelineInstalled)
    }

    @Test
    fun thrownExternalMutation_retainsIdentityAndReleasesItsTransientGuard() = runTest {
        val fixture = Fixture(this)
        val previous = YouTubeQueue(WatchEndpoint(playlistId = "RDprevious"))
        fixture.installExternalTimeline(listOf("current"), previous)
        advanceUntilIdle()
        fixture.beginReplacement(YouTubeQueue(WatchEndpoint(playlistId = "RDpending")))
        fixture.finishReplacement()
        var accepted = false
        try {
            fixture.checkpointGate.installExternalTimeline(
                install = { throw IOException("Installation failed") },
                acceptIdentity = { accepted = true; EmptyQueue },
                save = fixture.scheduler::request,
            )
        } catch (_: IOException) {
            // The shared transaction still schedules the retained timeline's checkpoint.
        }
        advanceUntilIdle()

        assertFalse(accepted)
        assertFalse(fixture.checkpointGate.blocked())
        assertSame(previous, fixture.checkpointGate.persistenceQueue)
        assertEquals(listOf("current"), fixture.written.last())
        assertTrue(fixture.savePosition(987L))
    }

    @Test
    fun emptyUserRemovalThenFailedRadio_keepsTheEmptyListIdentitySaveable() = runTest {
        val fixture = Fixture(this)
        fixture.beginFinitePreload().fail()
        fixture.queueTitle = "Previous finite title"
        advanceUntilIdle()
        fixture.removeItemsByUser(emptyList())
        advanceUntilIdle()
        assertSame(EmptyQueue, fixture.checkpointGate.persistenceQueue)

        fixture.beginReplacement(YouTubeQueue(WatchEndpoint(playlistId = "RDfailed")))
        fixture.finishReplacement()
        advanceUntilIdle()
        assertFalse(fixture.checkpointGate.blocked())
        assertEquals(emptyList<String>(), fixture.written.last())
        fixture.addItemsByUser(listOf("manual"))
        advanceUntilIdle()
        assertEquals(QueueType.LIST, fixture.snapshots.last().queueType)
        assertNull(fixture.snapshots.last().title)
    }

    @Test
    fun loaderInsertionAfterAcceptedEmptyRemoval_doesNotAcceptLaterFilteringToEmpty() = runTest {
        val fixture = Fixture(this)
        val queue = fixture.beginFinitePreload()
        advanceUntilIdle()
        fixture.removeItemsByUser(emptyList())
        advanceUntilIdle()
        assertSame(EmptyQueue, fixture.checkpointGate.persistenceQueue)

        queue.accept(YouTubePlaylistQueue.Proposal(
            FinitePlaylistSnapshot(continuation = "next-page", phase = FinitePlaylistPhase.PAGEABLE), emptyList(),
        ))
        fixture.mutating = true
        fixture.installTimeline(listOf("loaded-page"))
        fixture.mutating = false
        advanceUntilIdle()
        assertEquals(listOf("loaded-page"), fixture.written.last())

        fixture.changeTimeline(emptyList())
        advanceUntilIdle()
        assertEquals(listOf("loaded-page"), fixture.written.last())
        assertNull(fixture.teardownSnapshot())
    }

    @Test
    fun filteringThePreviousUnresolvedFiniteQueue_cannotUseAnUninstalledExhaustedReplacementsPhase() = runTest {
        val fixture = Fixture(this)
        val previous = fixture.beginFiniteReplacement(FinitePlaylistSnapshot(
            continuation = "previous-continuation", phase = FinitePlaylistPhase.PAGEABLE,
        ))
        fixture.installTimeline(listOf("previous-track"))
        advanceUntilIdle()
        val replacement = YouTubePlaylistQueue(
            WatchEndpoint(playlistId = "complete-replacement"),
            snapshot = FinitePlaylistSnapshot(phase = FinitePlaylistPhase.EXHAUSTED),
        )
        fixture.beginReplacement(replacement)
        fixture.changeTimeline(emptyList()) // Automatic filtering, not an owned installation.
        advanceUntilIdle()

        assertSame(previous, fixture.checkpointGate.persistenceQueue)
        assertEquals(listOf(listOf("previous-track")), fixture.written)
        assertNull(fixture.teardownSnapshot())

        fixture.installTimeline(emptyList()) // The complete replacement now actually installs.
        advanceUntilIdle()
        assertSame(replacement, fixture.checkpointGate.persistenceQueue)
        assertEquals(emptyList<String>(), fixture.written.last())
        assertFalse(fixture.checkpointGate.blocked())
    }

    @Test
    fun pendingFiniteReplacement_usesThePreviousOccurrenceMappingAndTitle() = runTest {
        val fixture = Fixture(this)
        val previous = fixture.beginFiniteReplacement(FinitePlaylistSnapshot(
            continuation = "previous-continuation", phase = FinitePlaylistPhase.PAGEABLE,
        ))
        fixture.installTimeline(listOf("same-video"))
        fixture.queueTitle = "Previous finite title"
        val item = MediaItem.Builder().setMediaId("same-video").build()
        previous.rememberOccurrence(item, "previous-occurrence")
        val replacement = fixture.beginFiniteReplacement(clearInitialTimeline = false)
        replacement.rememberOccurrence(item, "replacement-occurrence")
        val player = QueueCheckpointTestPlayer(listOf(item))
        val metadata = MediaMetadata("same-video", "Same video", emptyList(), 180)

        val checkpoint = fixture.checkpointGate.persistQueue(player.player, listOf(metadata))
        val data = checkpoint.queueData as QueueData.YouTubePlaylistData
        assertEquals(listOf(metadata), checkpoint.items)
        assertEquals("Previous finite title", checkpoint.title)
        assertEquals("previous-continuation", data.snapshot.continuation)
        assertEquals(listOf("previous-occurrence"), data.timelineOccurrences)
        assertSame(previous, fixture.checkpointGate.persistenceQueue)
        advanceUntilIdle()
    }
}

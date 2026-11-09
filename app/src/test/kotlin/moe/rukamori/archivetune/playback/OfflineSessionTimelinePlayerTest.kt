package moe.rukamori.archivetune.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.models.QueueType
import moe.rukamori.archivetune.playback.QueueCheckpointSchedulerTest.Fixture
import moe.rukamori.archivetune.playback.queues.EmptyQueue
import moe.rukamori.archivetune.playback.queues.PlaylistLoadOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSessionTimelinePlayerTest {
    @Test
    fun everyOfflineRecoverySetter_installsThroughTheServiceDecisionAndKeepsUnavailableEntries() = runTest {
        val setters = listOf(
            Setter(listOf("unavailable", "cached"), 1, 0L) { player, items -> player.setMediaItems(items) },
            Setter(listOf("unavailable", "cached"), 1, 0L) { player, items -> player.setMediaItems(items, true) },
            Setter(listOf("unavailable", "cached"), 1, 123L) { player, items -> player.setMediaItems(items, 1, 123L) },
            Setter(listOf("cached"), 0, C.TIME_UNSET) { player, items -> player.setMediaItem(items[1]) },
            Setter(listOf("cached"), 0, 123L) { player, items -> player.setMediaItem(items[1], 123L) },
            Setter(listOf("cached"), 0, C.TIME_UNSET) { player, items -> player.setMediaItem(items[1], true) },
        )
        for (setter in setters) {
            val fixture = Fixture(this)
            val previous = fixture.beginFinitePreload()
            advanceUntilIdle()
            val target = QueueCheckpointTestPlayer(listOf(item("preload")))
            target.playbackState = Player.STATE_IDLE
            target.playerError = sourceError()
            target.changed = { fixture.changeTimeline(target.items.map { it.mediaId }) }
            target.beforeInstallation = {
                assertSame(previous, fixture.checkpointGate.persistenceQueue)
                assertTrue(fixture.checkpointGate.timelineMutationInProgress)
            }
            var cancellations = 0
            var recoveries = 0
            val session = fixture.offlineSession(target,
                cancelObsoleteWork = { cancellations++; fixture.finishReplacement() },
                onRecover = { recoveries++ },
            )

            setter.set(session, listOf(item("unavailable"), item("cached")))
            advanceUntilIdle()

            assertEquals(1, cancellations)
            assertEquals(1, recoveries)
            assertEquals(1, target.prepares)
            assertEquals(setter.items, target.items.map { it.mediaId })
            assertEquals(setter.index, target.currentIndex)
            assertEquals(setter.position, target.position)
            assertSame(EmptyQueue, fixture.queue)
            assertSame(EmptyQueue, fixture.checkpointGate.persistenceQueue)
            assertEquals(setter.items, fixture.written.last())
            assertEquals(QueueType.LIST, fixture.snapshots.last().queueType)
        }
    }

    @Test
    fun offlineSameOccurrenceReplay_keepsHydrationFiniteOwnershipRetryAndTheExactDuplicate() = runTest {
        for (hydrating in listOf(false, true)) {
            val fixture = Fixture(this)
            val finite = fixture.beginFinitePreload()
            fixture.queueTitle = "Finite title"
            fixture.installTimeline(listOf("cached", "unavailable", "cached"))
            advanceUntilIdle()
            fixture.hydrating = hydrating
            val target = QueueCheckpointTestPlayer(listOf(item("cached"), item("unavailable"), item("cached")))
            target.playbackState = Player.STATE_IDLE
            target.playerError = sourceError()
            target.changed = { fixture.changeTimeline(target.items.map { it.mediaId }) }
            val owner = PlaylistLoadOwner()
            var generation = 9L
            val ticket = requireNotNull(owner.begin(generation, finite))
            val retry = {}
            var retryAction: (() -> Unit)? = retry
            var cancellations = 0
            val tapped = target.items[2]
            val session = fixture.offlineSession(target, cancelObsoleteWork = {
                cancellations++
                generation++
                owner.cancel()
                fixture.hydrating = false
                retryAction = null
            })

            // Offline selection filters eligible rows, but recovery forwards the original occurrence list.
            session.setMediaItems(target.items.toList(), 2, 456L)

            assertEquals(0, cancellations)
            assertEquals(9L, generation)
            assertTrue(ticket.isCurrent(generation, fixture.queue))
            assertSame(retry, retryAction)
            assertEquals(hydrating, fixture.hydrating)
            assertSame(finite, fixture.queue)
            assertSame(finite, fixture.checkpointGate.persistenceQueue)
            assertEquals(2, target.currentIndex)
            assertSame(tapped, target.items[target.currentIndex])
            assertEquals(456L, target.position)
            assertEquals(1, target.prepares)
            assertEquals(listOf("cached", "unavailable", "cached"), target.items.map { it.mediaId })
            assertEquals(hydrating, fixture.checkpointGate.blocked())
            fixture.hydrating = false
            fixture.scheduler.request()
            advanceUntilIdle()
            assertEquals("Finite title", fixture.snapshots.last().title)
            ticket.finish()
        }
    }

    @Test
    fun unavailableExplicitOfflineTap_neverInstallsOrDisturbsTheSavedFiniteCheckpoint() = runTest {
        val fixture = Fixture(this)
        val finite = fixture.beginFinitePreload()
        advanceUntilIdle()
        val target = QueueCheckpointTestPlayer(listOf(item("preload")))
        target.playbackState = Player.STATE_IDLE
        target.playerError = sourceError()
        var installations = 0
        var recoveries = 0
        val session = fixture.offlineSession(target,
            cancelObsoleteWork = { installations++ }, onRecover = { recoveries++ },
        )

        session.setMediaItems(listOf(item("unavailable"), item("cached")), 0, 123L)
        advanceUntilIdle()

        assertEquals(0, installations)
        assertEquals(0, recoveries)
        assertEquals(0, target.prepares)
        assertSame(finite, fixture.queue)
        assertSame(finite, fixture.checkpointGate.persistenceQueue)
        assertEquals(listOf("preload"), target.items.map { it.mediaId })
        assertEquals(listOf(listOf("preload")), fixture.written)
        assertFalse(fixture.checkpointGate.timelineMutationInProgress)
    }

    private fun Fixture.offlineSession(
        target: QueueCheckpointTestPlayer,
        cancelObsoleteWork: () -> Unit,
        onRecover: () -> Unit = {},
    ): Player = ErrorRecoveringPlayer(
        queueSessionTimelinePlayer(target.player, checkpointGate, { installerTarget, installed, title, replay, mutation ->
            assertSame(target.player, installerTarget)
            installServiceQueueTimeline(checkpointGate, installed, replay, cancelObsoleteWork, mutation,
                acceptIdentity = {
                    queue = installed ?: EmptyQueue
                    queueTitle = title
                    queue
                },
                save = scheduler::request,
                evaluateLoading = {},
            )
        }, scheduler::request),
        AutoOfflineSongs(isOffline = { true }, isFullyCachedId = { it == "cached" }),
        onRecover,
    )

    private data class Setter(
        val items: List<String>, val index: Int, val position: Long,
        val set: (Player, List<MediaItem>) -> Unit,
    )

    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()
    private fun sourceError() = PlaybackException("Offline source", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
}

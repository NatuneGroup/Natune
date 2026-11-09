package moe.rukamori.archivetune.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.models.QueueData
import moe.rukamori.archivetune.models.QueueType
import moe.rukamori.archivetune.playback.QueueCheckpointSchedulerTest.Fixture
import moe.rukamori.archivetune.playback.queues.EmptyQueue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTimelinePlayerTest {
    @Test
    fun everySessionSetter_acceptsTheExternalIdentityAfterItsActualMutation() = runTest {
        val setters: List<(Player, List<MediaItem>) -> Unit> = listOf(
            { player, items -> player.setMediaItems(items) },
            { player, items -> player.setMediaItems(items, true) },
            { player, items -> player.setMediaItems(items, 0, 123L) },
            { player, items -> player.setMediaItem(items.single()) },
            { player, items -> player.setMediaItem(items.single(), 123L) },
            { player, items -> player.setMediaItem(items.single(), true) },
        )
        for (set in setters) {
            val fixture = Fixture(this)
            val previous = YouTubeQueue(WatchEndpoint(playlistId = "RDprevious"))
            fixture.installExternalTimeline(listOf("old-track"), previous)
            advanceUntilIdle()
            fixture.beginReplacement(YouTubeQueue(WatchEndpoint(playlistId = "RDfailed-radio")))
            fixture.finishReplacement()
            val target = QueueCheckpointTestPlayer(listOf(item("old-track")))
            var observedInstallation = false
            target.beforeInstallation = {
                observedInstallation = true
                assertSame(previous, fixture.checkpointGate.persistenceQueue)
                assertTrue(fixture.checkpointGate.blocked()) // Only during this synchronous transaction.
            }
            val session = fixture.sessionPlayer(target)

            set(session, listOf(item("external-track")))
            advanceUntilIdle()

            assertTrue(observedInstallation)
            assertFalse(fixture.checkpointGate.blocked())
            assertSame(EmptyQueue, fixture.checkpointGate.persistenceQueue)
            assertEquals(listOf("external-track"), fixture.written.last())
            assertEquals(QueueType.LIST, fixture.snapshots.last().queueType)
            assertTrue(fixture.savePosition(321L))
            assertEquals(QueueType.LIST, requireNotNull(fixture.teardownSnapshot()).queueType)
        }
    }

    @Test
    fun queueTapReplayingTheSameOccurrences_keepsThePreviousQueueIdentityAndTitle() = runTest {
        val fixture = Fixture(this)
        val previous = YouTubeQueue(WatchEndpoint(playlistId = "RDprevious"))
        fixture.installExternalTimeline(listOf("current", "upcoming"), previous, "Previous title")
        advanceUntilIdle()
        fixture.beginReplacement(YouTubeQueue(WatchEndpoint(playlistId = "RDpending")))
        val target = QueueCheckpointTestPlayer(listOf(item("current"), item("upcoming")))
        val session = fixture.sessionPlayer(target)

        session.setMediaItems(target.items.toList(), 1, 456L)
        advanceUntilIdle()

        assertSame(previous, fixture.queue)
        assertSame(previous, fixture.checkpointGate.persistenceQueue)
        assertEquals("Previous title", fixture.snapshots.last().title)
        assertEquals("RDprevious", (fixture.snapshots.last().queueData as QueueData.YouTubeData).playlistId)
        assertEquals(1, target.currentIndex)
        assertEquals(456L, target.position)
    }

    @Test
    fun sessionAddAndRemoveAfterCancelledPreparation_keepsTheRetainedIdentityAndAcceptsEmptyRemoval() = runTest {
        val fixture = Fixture(this)
        val previous = YouTubeQueue(WatchEndpoint(playlistId = "RDprevious"))
        fixture.installExternalTimeline(listOf("current"), previous)
        advanceUntilIdle()
        val cancelled = fixture.beginFiniteReplacement(clearInitialTimeline = false)
        cancelled.fail()
        fixture.finishReplacement(cancelled)
        val target = QueueCheckpointTestPlayer(listOf(item("current")))
        val session = fixture.sessionPlayer(target)

        session.addMediaItems(listOf(item("manual")))
        advanceUntilIdle()
        assertEquals(listOf("current", "manual"), fixture.written.last())
        assertSame(previous, fixture.checkpointGate.persistenceQueue)
        session.removeMediaItems(1, 2)
        advanceUntilIdle()
        assertEquals(listOf("current"), fixture.written.last())
        assertSame(previous, fixture.checkpointGate.persistenceQueue)
        session.removeMediaItem(0)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), fixture.written.last())
        assertFalse(fixture.checkpointGate.blocked())
        assertFalse(cancelled.initialTimelineCleared)
        assertFalse(cancelled.timelineInstalled)
        assertTrue(requireNotNull(fixture.teardownSnapshot()).items.isEmpty())
    }

    @Test
    fun staleSessionAfterPlayerReplacement_cannotAcceptAnUnresolvedEmptyTimeline() = runTest {
        val fixture = Fixture(this)
        val finite = fixture.beginFinitePreload()
        advanceUntilIdle()
        val oldTarget = QueueCheckpointTestPlayer(listOf(item("preload")))
        var activePlayer = oldTarget.player
        val staleSession = fixture.sessionPlayer(oldTarget) { activePlayer }
        activePlayer = QueueCheckpointTestPlayer(emptyList()).player
        fixture.changeTimeline(emptyList()) // Filtering the active player's unresolved finite queue.

        staleSession.setMediaItems(listOf(item("stale-replacement")))
        assertEquals(listOf("preload"), oldTarget.items.map { it.mediaId })
        staleSession.removeMediaItem(0)
        advanceUntilIdle()

        assertSame(finite, fixture.checkpointGate.persistenceQueue)
        assertTrue(fixture.checkpointGate.blocked())
        assertEquals(listOf(listOf("preload")), fixture.written)
        assertNull(fixture.teardownSnapshot())
    }

    @Test
    fun sessionClear_acceptsAnEmptyTimelineEvenWithAnUnresolvedFiniteReplacement() = runTest {
        val fixture = Fixture(this)
        fixture.beginFinitePreload().fail()
        advanceUntilIdle()
        val target = QueueCheckpointTestPlayer(listOf(item("preload")))

        fixture.sessionPlayer(target).clearMediaItems()
        advanceUntilIdle()

        assertSame(EmptyQueue, fixture.queue)
        assertFalse(fixture.checkpointGate.blocked())
        assertEquals(emptyList<String>(), fixture.written.last())
        assertEquals(QueueType.LIST, requireNotNull(fixture.teardownSnapshot()).queueType)
    }

    private fun Fixture.sessionPlayer(target: QueueCheckpointTestPlayer, currentPlayer: () -> Player = { target.player }): Player {
        target.changed = { if (target.player === currentPlayer()) changeTimeline(target.items.map { it.mediaId }) }
        return queueSessionTimelinePlayer(target.player, checkpointGate, { installerTarget, installed, title, mutation ->
            assertSame(target.player, installerTarget)
            finishReplacement()
            checkpointGate.installExternalTimeline(mutation, {
                queue = installed ?: EmptyQueue
                queueTitle = title
                queue
            }, scheduler::request)
        }, scheduler::request, currentPlayer)
    }

    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()
}

/** Deterministic player-thread timeline fixture; no audio engine or Android service is simulated. */
internal class QueueCheckpointTestPlayer(initial: List<MediaItem>) {
    val items = initial.toMutableList()
    var currentIndex = 0
    var position = 0L
    var beforeInstallation: () -> Unit = {}
    var changed: () -> Unit = {}
    private val timeline = object : Timeline() {
        override fun getWindowCount(): Int = items.size
        override fun getWindow(index: Int, window: Timeline.Window, projectionUs: Long): Timeline.Window = window
        override fun getPeriodCount(): Int = items.size
        override fun getPeriod(index: Int, period: Timeline.Period, setIds: Boolean): Timeline.Period = period
        override fun getIndexOfPeriod(uid: Any): Int = C.INDEX_UNSET
        override fun getUidOfPeriod(index: Int): Any = items[index]
    }

    @Suppress("UNCHECKED_CAST")
    val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
        when (method.name) {
            "getMediaItemCount" -> items.size
            "getMediaItemAt" -> items[args!![0] as Int]
            "getCurrentMediaItemIndex" -> currentIndex
            "getCurrentPosition" -> position
            "getCurrentTimeline" -> timeline
            "setMediaItems", "setMediaItem", "clearMediaItems" -> {
                beforeInstallation()
                val installationArgs = args ?: emptyArray()
                val next = when (method.name) {
                    "clearMediaItems" -> emptyList()
                    "setMediaItem" -> listOf(installationArgs[0] as MediaItem)
                    else -> installationArgs[0] as List<MediaItem>
                }
                items.clear()
                items.addAll(next)
                currentIndex = if (installationArgs.size == 3) installationArgs[1] as Int else 0
                position = when {
                    installationArgs.size == 3 -> installationArgs[2] as Long
                    installationArgs.getOrNull(1) is Long -> installationArgs[1] as Long
                    else -> 0L
                }
                changed()
                null
            }
            "addMediaItems", "addMediaItem" -> {
                val value = args!!.last()
                val next = if (value is MediaItem) listOf(value) else value as List<MediaItem>
                val index = if (args.size == 2) args[0] as Int else items.size
                items.addAll(index, next)
                changed()
                null
            }
            "removeMediaItem", "removeMediaItems" -> {
                val from = args!![0] as Int
                val to = if (args.size == 2) args[1] as Int else from + 1
                items.subList(from, to).clear()
                currentIndex = if (items.isEmpty()) C.INDEX_UNSET else currentIndex.coerceIn(items.indices)
                changed()
                null
            }
            "toString" -> "QueueCheckpointTestPlayer"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            else -> throw AssertionError("Unexpected Player.${method.name}")
        }
    } as Player
}

package moe.rukamori.archivetune.playback

import androidx.media3.common.MediaItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.models.QueueData
import moe.rukamori.archivetune.models.QueueType
import moe.rukamori.archivetune.playback.QueueCheckpointSchedulerTest.Fixture
import moe.rukamori.archivetune.playback.queues.EmptyQueue
import moe.rukamori.archivetune.playback.queues.FinitePlaylistPhase
import moe.rukamori.archivetune.playback.queues.FinitePlaylistSnapshot
import moe.rukamori.archivetune.playback.queues.YouTubeAlbumRadio
import moe.rukamori.archivetune.playback.queues.YouTubePlaylistQueue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueSourceDetachmentTest {
    @Test
    fun filteringAlbumRadioToEmpty_thenAddingUnrelatedSong_cannotRestoreTheAlbumContinuation() = runTest {
        val fixture = Fixture(this)
        val radio = YouTubeAlbumRadio("old-album", 2, "old-album-continuation", true)
        fixture.installExternalTimeline(listOf("album-track"), radio, "Old album")
        advanceUntilIdle()
        assertEquals(QueueType.YOUTUBE_ALBUM_RADIO, fixture.snapshots.last().queueType)

        fixture.changeTimeline(emptyList()) // Automatic filtering, without user-removal acceptance.
        fixture.detachSource()
        advanceUntilIdle()
        assertSame(EmptyQueue, fixture.checkpointGate.persistenceQueue)
        assertFalse(fixture.checkpointGate.blocked())
        fixture.addItemsByUser(listOf("unrelated")) // Auto-loading remains disabled by the caller.
        advanceUntilIdle()

        assertEquals(QueueType.LIST, fixture.snapshots.last().queueType)
        assertNull(fixture.snapshots.last().queueData)
        assertNull(fixture.snapshots.last().title)
        val metadata = MediaMetadata("unrelated", "Unrelated", emptyList(), 180)
        val target = QueueCheckpointTestPlayer(listOf(MediaItem.Builder().setMediaId(metadata.id).build()))
        val persisted = fixture.checkpointGate.persistQueue(target.player, listOf(metadata))
        assertEquals(QueueType.LIST, persisted.queueType)
        assertNull(persisted.queueData)
        assertTrue(fixture.savePosition(321L))
        assertEquals(QueueType.LIST, requireNotNull(fixture.teardownSnapshot()).queueType)
    }

    @Test
    fun disablingAutoloadDetachesTheNonemptySource_andSavesWithoutAnotherTimelineEvent() = runTest {
        val fixture = Fixture(this)
        val radio = YouTubeQueue(WatchEndpoint(playlistId = "RDold-radio"))
        fixture.installExternalTimeline(listOf("current"), radio, "Old radio")
        advanceUntilIdle()
        val before = fixture.written.size

        fixture.detachSource() // The current track is retained; there is no removal callback.
        advanceUntilIdle()

        assertEquals(before + 1, fixture.written.size)
        assertEquals(listOf("current"), fixture.written.last())
        assertSame(EmptyQueue, fixture.queue)
        assertSame(EmptyQueue, fixture.checkpointGate.persistenceQueue)
        assertEquals(QueueType.LIST, fixture.snapshots.last().queueType)
        assertNull(fixture.snapshots.last().queueData)
        assertNull(fixture.snapshots.last().title)
        fixture.addItemsByUser(listOf("current", "manual"))
        advanceUntilIdle()
        assertNull(fixture.snapshots.last().queueData)
    }

    @Test
    fun automaticEmptySourceReset_preservesEveryUnresolvedFiniteCheckpoint() = runTest {
        for (phase in FinitePlaylistPhase.entries) {
            val fixture = Fixture(this)
            val finite = fixture.beginFinitePreload()
            finite.accept(YouTubePlaylistQueue.Proposal(
                FinitePlaylistSnapshot(continuation = "saved-continuation", phase = phase), emptyList(),
            ))
            fixture.scheduler.request()
            advanceUntilIdle()
            val saved = fixture.snapshots.last()

            fixture.changeTimeline(emptyList())
            fixture.detachSource()
            advanceUntilIdle()

            assertSame(finite, fixture.checkpointGate.persistenceQueue)
            assertTrue(fixture.checkpointGate.blocked())
            assertEquals(listOf("preload"), fixture.written.last())
            assertEquals(saved, fixture.snapshots.last())
            assertNull(fixture.teardownSnapshot())
            assertEquals("saved-continuation", finite.snapshot.continuation)

            // Once a source really has been dropped, a new manual timeline has no old source.
            fixture.addItemsByUser(listOf("unrelated"))
            advanceUntilIdle()
            assertSame(EmptyQueue, fixture.checkpointGate.persistenceQueue)
            assertEquals(QueueType.LIST, fixture.snapshots.last().queueType)
            assertNull(fixture.snapshots.last().queueData)
        }
    }

    @Test
    fun automaticFilteringWithTheFiniteSourceRetained_keepsManualItemsInTheFiniteCheckpoint() = runTest {
        val fixture = Fixture(this)
        val finite = fixture.beginFinitePreload()
        finite.fail()
        advanceUntilIdle()
        fixture.changeTimeline(emptyList())
        fixture.checkpointGate.onSourceDetached() // Defensive gate check before any source reset.
        advanceUntilIdle()

        assertTrue(fixture.checkpointGate.blocked())
        assertSame(finite, fixture.queue)
        assertEquals(listOf("preload"), fixture.written.last())
        fixture.addItemsByUser(listOf("manual"))
        advanceUntilIdle()
        assertSame(finite, fixture.checkpointGate.persistenceQueue)
        assertEquals("fixture", (fixture.snapshots.last().queueData as QueueData.YouTubePlaylistData).playlistId)
        fixture.changeTimeline(emptyList())
        advanceUntilIdle()
        assertEquals(listOf("manual"), fixture.written.last())
        assertNull(fixture.teardownSnapshot())
    }

    @Test
    fun confirmedExhaustionAndExplicitClear_canDetachAnEmptyFiniteSource() = runTest {
        for (explicit in listOf(false, true)) {
            val fixture = Fixture(this)
            val finite = fixture.beginFinitePreload()
            if (explicit) finite.fail()
            else finite.accept(YouTubePlaylistQueue.Proposal(
                FinitePlaylistSnapshot(phase = FinitePlaylistPhase.EXHAUSTED), emptyList(),
            ))
            advanceUntilIdle()
            fixture.changeTimeline(emptyList())
            fixture.detachSource(acceptEmptyTimeline = explicit)
            advanceUntilIdle()

            assertSame(EmptyQueue, fixture.checkpointGate.persistenceQueue)
            assertFalse(fixture.checkpointGate.blocked())
            assertEquals(emptyList<String>(), fixture.written.last())
            assertEquals(QueueType.LIST, requireNotNull(fixture.teardownSnapshot()).queueType)
        }
    }

    private fun Fixture.detachSource(acceptEmptyTimeline: Boolean = false) {
        queue = EmptyQueue
        queueTitle = null
        checkpointGate.onSourceDetached(acceptEmptyTimeline)
        scheduler.request()
    }
}

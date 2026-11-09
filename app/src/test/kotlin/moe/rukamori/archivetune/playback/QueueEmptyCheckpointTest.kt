package moe.rukamori.archivetune.playback

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.extensions.toPersistQueue
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.models.PersistQueue
import moe.rukamori.archivetune.models.PlaybackPersistenceCodec
import moe.rukamori.archivetune.models.QueueData
import moe.rukamori.archivetune.models.QueueType
import moe.rukamori.archivetune.playback.queues.EmptyQueue
import moe.rukamori.archivetune.playback.queues.FinitePlaylistEntry
import moe.rukamori.archivetune.playback.queues.FinitePlaylistPhase
import moe.rukamori.archivetune.playback.queues.FinitePlaylistSnapshot
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.playback.queues.YouTubePlaylistQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class QueueEmptyCheckpointTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun filteredRestore_explicitClearWithoutTimelineEvent_writesEmptyListOnBothSavePaths() = runTest {
        for (teardown in listOf(false, true)) {
            val fixture = Fixture(this, temporaryFolder.newFile())
            fixture.scheduler.request()
            advanceUntilIdle()
            assertTrue(fixture.gate.blocked())
            assertEquals(0, fixture.writes)
            assertNull(fixture.saveForTeardown())
            fixture.assertOldCheckpoint()

            fixture.clearQueueWithUndo()
            assertFalse(fixture.hasTimelineChanges)
            assertTrue(fixture.gate.hasAcceptedEmptyCheckpoint)
            assertFalse(fixture.gate.blocked())
            if (teardown) requireNotNull(fixture.saveForTeardown()).join()
            else advanceUntilIdle()

            assertEquals(1, fixture.writes)
            fixture.assertEmptyCheckpoint()
            advanceUntilIdle()
        }
    }

    @Test
    fun filteredRestore_successfulExternalEmptyWithoutTimelineEvent_writesOnBothSavePaths() = runTest {
        for (teardown in listOf(false, true)) {
            val fixture = Fixture(this, temporaryFolder.newFile())
            fixture.gate.installExternalTimeline(
                install = {}, // Setting an already-empty player need not emit a timeline event.
                acceptIdentity = { fixture.queue = EmptyQueue; fixture.queue },
                save = fixture.scheduler::request,
            )
            assertFalse(fixture.hasTimelineChanges)
            if (teardown) requireNotNull(fixture.saveForTeardown()).join()
            else advanceUntilIdle()

            assertEquals(1, fixture.writes)
            fixture.assertEmptyCheckpoint()
            advanceUntilIdle()
        }
    }

    @Test
    fun automaticEmptyDetachmentWithoutTimelineEvent_preservesTheFilteredRestore() = runTest {
        for (phase in listOf(FinitePlaylistPhase.PAGEABLE, FinitePlaylistPhase.EXHAUSTED)) {
            val fixture = Fixture(this, temporaryFolder.newFile(), phase)
            fixture.queue = EmptyQueue
            fixture.gate.onSourceDetached()
            fixture.scheduler.request()
            advanceUntilIdle()

            assertFalse(fixture.hasTimelineChanges)
            assertFalse(fixture.gate.hasAcceptedEmptyCheckpoint)
            assertEquals(0, fixture.writes)
            assertNull(fixture.saveForTeardown())
            fixture.assertOldCheckpoint()
        }
    }

    @Test
    fun acceptedClear_stillHonorsPersistenceRestoreAndUnpersistableItemGuards() = runTest {
        val fixture = Fixture(this, temporaryFolder.newFile())
        fixture.clearQueueWithUndo()
        fixture.enabled = false
        advanceUntilIdle()
        assertNull(fixture.saveForTeardown())
        assertEquals(0, fixture.writes)
        fixture.assertOldCheckpoint()

        assertFalse(fixture.gate.canSnapshotEmpty(false, restoreCompleted = false))
        val nonemptyGate = QueueCheckpointGate({ EmptyQueue }, { false }, { 1 })
        nonemptyGate.onUserClear()
        assertFalse(nonemptyGate.canSnapshotEmpty(false, restoreCompleted = true))
        val restoringGate = QueueCheckpointGate({ EmptyQueue }, { true }, { 0 })
        restoringGate.onUserClear()
        assertTrue(restoringGate.blocked())
    }

    @Test
    fun acceptedClear_cannotAuthorizeALaterReplacementFilteredToEmpty() = runTest {
        val fixture = Fixture(this, temporaryFolder.newFile())
        fixture.clearQueueWithUndo()
        val replacement = YouTubePlaylistQueue(WatchEndpoint(playlistId = "replacement"))
        fixture.gate.beginReplacement(replacement)
        fixture.queue = replacement
        fixture.scheduler.request()
        advanceUntilIdle()

        assertFalse(fixture.gate.hasAcceptedEmptyCheckpoint)
        assertTrue(fixture.gate.blocked())
        assertNull(fixture.saveForTeardown())
        assertEquals(0, fixture.writes)
        fixture.assertOldCheckpoint()
    }

    private class Fixture(
        private val scope: CoroutineScope,
        private val disk: File,
        phase: FinitePlaylistPhase = FinitePlaylistPhase.PAGEABLE,
    ) {
        private val target = QueueCheckpointTestPlayer(emptyList())
        private val persistence = QueuePersistenceState()
        private var restoring = true
        var queue: Queue = EmptyQueue
        var enabled = true
        var hasTimelineChanges = false
            private set
        var writes = 0
            private set
        val gate = QueueCheckpointGate({ queue }, { restoring }, { target.items.size })
        val scheduler = QueueCheckpointScheduler(
            scope = { scope },
            generation = { persistence.generation.get() },
            enabled = { enabled },
            blocked = gate::blocked,
            snapshot = ::snapshot,
            write = { commit(it) },
        )

        init {
            val oldItem = MediaMetadata("old-track", "Old track", emptyList(), 180)
            val savedSource = YouTubePlaylistQueue(
                WatchEndpoint(playlistId = "old-playlist"),
                snapshot = FinitePlaylistSnapshot(
                    title = "Old playlist",
                    entries = listOf(FinitePlaylistEntry(oldItem, "old-occurrence", 0)),
                    continuation = "old-continuation".takeUnless { phase == FinitePlaylistPhase.EXHAUSTED },
                    phase = phase,
                ),
            )
            val oldCheckpoint = savedSource.toPersistQueue("Old playlist", listOf(oldItem), 0, 42L)
            PlaybackPersistenceCodec.write(disk.outputStream(), oldCheckpoint)
            target.changed = {
                hasTimelineChanges = true
                gate.onTimelineChanged(target.items.isEmpty())
                scheduler.request()
            }
            // Match cold restore's filtered-to-zero early return: bind identity, install no timeline.
            queue = savedSource
            savedSource.timelineInstalled = true
            gate.onReplacementInstalled(savedSource)
            restoring = false
        }

        fun clearQueueWithUndo() {
            queue = EmptyQueue
            gate.onSourceDetached(acceptEmptyTimeline = true)
            // There are no windows to remove; MusicService still requests a checkpoint.
            scheduler.request()
        }

        fun saveForTeardown(): Job? {
            if (!TeardownQueuePersistence.shouldSave(enabled, target.items.size, hasTimelineChanges, gate.hasAcceptedEmptyCheckpoint)) return null
            val snapshot = snapshot() ?: return null
            return persistence.teardownWrites.launch(scope) { commit(snapshot) }
        }

        private fun snapshot(): Pair<QueueCheckpointToken, PersistQueue>? {
            if (gate.blocked() || !gate.canSnapshotEmpty(hasTimelineChanges, restoreCompleted = true)) return null
            return persistence.checkpointOrder.nextSnapshot() to gate.persistQueue(target.player, emptyList())
        }

        private fun commit(snapshot: Pair<QueueCheckpointToken, PersistQueue>) {
            persistence.checkpointOrder.writeIfCurrent(snapshot.first) {
                PlaybackPersistenceCodec.write(disk.outputStream(), snapshot.second)
                writes++
            }
        }

        fun assertOldCheckpoint() {
            val saved = PlaybackPersistenceCodec.read(disk.inputStream()) as PersistQueue
            assertEquals(QueueType.YOUTUBE_PLAYLIST.javaClass, saved.queueType.javaClass)
            assertEquals(listOf("old-track"), saved.items.map { it.id })
            val data = saved.queueData as QueueData.YouTubePlaylistData
            assertEquals("old-playlist", data.playlistId)
            assertEquals("old-continuation".takeUnless { data.snapshot.phase == FinitePlaylistPhase.EXHAUSTED }, data.snapshot.continuation)
        }

        fun assertEmptyCheckpoint() {
            val saved = PlaybackPersistenceCodec.read(disk.inputStream()) as PersistQueue
            assertEquals(QueueType.LIST.javaClass, saved.queueType.javaClass)
            assertTrue(saved.items.isEmpty())
            assertNull(saved.queueData)
            assertNull(saved.title)
            assertEquals(emptyList<Int>(), saved.shuffleOrder)
            assertEquals(emptyList<Int?>(), saved.manualQueueKinds)
        }
    }
}

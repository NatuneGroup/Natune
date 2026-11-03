package moe.rukamori.archivetune.shared.data.repository

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import moe.rukamori.archivetune.models.*
import moe.rukamori.archivetune.shared.data.persistence.*
import moe.rukamori.archivetune.shared.domain.playback.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.queue.*

/** Test-only owner/port. No synthetic engine observation or playback-clock advancement. */
class DelegatingQueueControllerTest {
    private class Owner(scope: CoroutineScope) : QueueOwnerPort {
        val commands = mutableListOf<PlaybackCommand>()
        private val port = object : PlaybackPort {
            override val snapshots = MutableStateFlow<PlaybackSnapshot?>(null)
            override val failures = MutableSharedFlow<PlaybackFailure>()
            override fun submit(command: PlaybackCommand) { commands += command }
            override fun close() = Unit
        }
        override val player = MusicPlayerController(port, scope)
        override val state = MutableStateFlow(QueueState(12, emptyList(), null, emptyList(), FinitePlaylistPhase.FAILED))
        private val mutex = Mutex()
        private var inside = false
        var loaded: QueueLoadRequest? = null
        var restored: LegacyQueueResume? = null
        val operations = mutableListOf<String>()
        private val metadata = MediaMetadata("same", "Full metadata", listOf(MediaMetadata.Artist("artist", "Artist")), 180,
            setVideoId = "set-a", spotifyTrackId = "catalog-id", explicit = true, isPodcast = true, suggestedBy = "Fixture")
        val bytes = ByteArrayOutputStream().also {
            PlaybackPersistenceCodec.write(it, PersistQueue("Fixture",
                listOf(metadata, metadata.copy(setVideoId = "set-b")), 1, 333, QueueType.LIST, null, listOf(1, 0), listOf(null, 1)))
        }.toByteArray()
        override suspend fun <T> withQueueAccess(operation: suspend () -> T): T = mutex.withLock {
            check(!inside); inside = true
            try { operation() } finally { inside = false }
        }
        private fun record(name: String) { check(inside); operations += name }
        override suspend fun loadCollection(request: QueueLoadRequest) { record("load"); loaded = request }
        override suspend fun capture(): LegacyQueueResume {
            record("capture")
            return LegacyQueueResume(state.value.revision, LegacyJavaQueueReader().read(bytes))
        }
        override suspend fun restore(value: LegacyQueueResume) { record("restore"); restored = value }
        override suspend fun replace(
            entries: List<ContentOccurrence>,
            startOccurrenceId: String,
            playWhenReady: Boolean,
            staysFinite: Boolean,
        ) { record("replace") }
        override suspend fun insert(entries: List<ContentOccurrence>, kind: UserQueuedKind): Int { record(kind.name); return entries.size }
        override suspend fun remove(occurrenceIds: Set<String>) { record("remove") }
        override suspend fun move(occurrenceId: String, beforeOccurrenceId: String?) { record("move") }
        override suspend fun select(occurrenceId: String, positionMs: Long) { record("select") }
    }
    @Test fun samePlayerAndStateDelegateEveryOperationUnderTheExistingOwnerBoundary() = runTest {
        val owner = Owner(backgroundScope)
        val root = Files.createTempDirectory("m7-queue-facade")
        val facade = DelegatingQueueController(owner, JvmQueueCheckpointAdapter(JvmLosslessQueueStore(root)))
        try {
            assertSame(owner.player, facade.player)
            assertSame(owner.state, facade.state)
            val load = QueueLoadRequest(ContentRequest(ContentProvider.YOUTUBE, ContentSurface.PLAYLIST,
                parent = ContentRef("youtube", ContentKind.PLAYLIST, "PLfinite")), QueueCollectionKind.FINITE,
                QueueStart(ContentRef("youtube", ContentKind.SONG, "same"), "occurrence-b", "set-b", 44), positionMs = 12345)
            facade.load(load)
            assertSame(load, owner.loaded)
            facade.replace(emptyList(), "occurrence", false)
            facade.insert(emptyList(), UserQueuedKind.PLAY_NEXT)
            facade.insert(emptyList(), UserQueuedKind.ADD_TO_QUEUE)
            facade.remove(setOf("occurrence"))
            facade.move("occurrence", null)
            facade.select("occurrence", 777)
            assertEquals(listOf("load", "replace", "PLAY_NEXT", "ADD_TO_QUEUE", "remove", "move", "select"), owner.operations)
            facade.player.pause()
            assertEquals(PlaybackCommand.Pause, owner.commands.single())
        } finally {
            owner.player.close()
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
    @Test fun diskCheckpointResumeAndPositionOverrideUseFullOriginalDocumentNotContentItemProjection() = runTest {
        val owner = Owner(backgroundScope)
        val root = Files.createTempDirectory("m7-queue-resume")
        val store = JvmLosslessQueueStore(root)
        val facade = DelegatingQueueController(owner, JvmQueueCheckpointAdapter(store))
        try {
            assertFalse(facade.resume())
            assertTrue(facade.checkpoint())
            assertContentEquals(owner.bytes, assertNotNull(store.read()).document.originalBytes())
            assertTrue(store.updatePosition(12, 1, 999))
            assertTrue(facade.resume())
            val restored = assertNotNull(owner.restored)
            assertEquals(999, restored.positionOverrideMs)
            assertContentEquals(owner.bytes, restored.document.originalBytes())
            // Stale owner revision may not overwrite a newer structural checkpoint.
            owner.state.value = owner.state.value.copy(revision = 11)
            assertFalse(facade.checkpoint())
            assertEquals(12, store.read()?.revision)
        } finally {
            owner.player.close()
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

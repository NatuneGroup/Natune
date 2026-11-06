package moe.rukamori.archivetune.desktop.storage

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.data.persistence.RoomQueueCheckpointStore
import moe.rukamori.archivetune.shared.data.persistence.openPortableStateDatabase
import moe.rukamori.archivetune.shared.domain.persistence.QueueCheckpoint
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistPhase
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueState

class DesktopStorageRelocationTest {
    private lateinit var root: Path
    private lateinit var source: Path
    private lateinit var pointer: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("natune-storage-relocation")
        source = Files.createDirectories(root.resolve("desktop"))
        pointer = root.resolve("config").resolve("desktop_location")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun destination(): Path = root.resolve("media").resolve("desktop")

    private fun prepareDestinationParent() {
        Files.createDirectories(destination().parent)
    }

    private fun checkpoint(): QueueCheckpoint {
        val song = ContentItem(ContentRef("youtube", ContentKind.SONG, "queue-song"), "Queue song", durationMs = 30_000)
        return QueueCheckpoint(
            QueueState(1, listOf(ContentOccurrence("occurrence", song)), "occurrence", listOf("occurrence"),
                FinitePlaylistPhase.PAGEABLE),
            4_321,
        )
    }

    @Test
    fun stagingRecordsTheMoveWithoutCopyingLiveData() {
        prepareDestinationParent()
        Files.writeString(source.resolve("settings.preferences"), "settings")
        Files.createDirectories(source.resolve("downloads"))
        Files.writeString(source.resolve("downloads/song.bin"), "audio")
        Files.writeString(source.resolve("instance.lock"), "lock")

        val migration = DesktopStorageRelocation.stage(source, destination())

        assertEquals(source.toRealPath(), migration.source)
        assertEquals(destination().toAbsolutePath().normalize(), migration.destination)
        assertEquals(migration, DesktopStorageRelocation.pending(source))
        assertTrue(Files.isRegularFile(source.resolve(DesktopStorageRelocation.MARKER_FILE)))
        assertFalse(Files.exists(destination()))
        assertFalse(Files.exists(pointer))
        assertEquals("settings", Files.readString(source.resolve("settings.preferences")))
        assertEquals("audio", Files.readString(source.resolve("downloads/song.bin")))
        assertEquals("lock", Files.readString(source.resolve("instance.lock")))
    }

    @Test
    fun aSecondPickReplacesTheEarlierRequest() {
        prepareDestinationParent()
        val earlier = DesktopStorageRelocation.stage(source, destination())
        Files.createDirectories(root.resolve("other"))
        val later = DesktopStorageRelocation.stage(source, root.resolve("other").resolve("desktop"))

        val pending = DesktopStorageRelocation.pending(source)
        assertEquals(later, pending)
        assertTrue(earlier.id != later.id)
    }

    @Test
    fun stagingRejectsUnsafeDestinations() {
        prepareDestinationParent()
        val occupied = Files.createDirectories(root.resolve("media/occupied/desktop"))
        Files.writeString(occupied.resolve("foreign"), "x")
        assertFailsWith<IllegalArgumentException> { DesktopStorageRelocation.stage(source, occupied) }

        val nested = Files.createDirectories(source.resolve("nested"))
        assertFailsWith<IllegalArgumentException> { DesktopStorageRelocation.stage(source, nested.resolve("desktop")) }

        assertFailsWith<IllegalArgumentException> { DesktopStorageRelocation.stage(source, root) }
        assertFailsWith<IllegalArgumentException> { DesktopStorageRelocation.stage(source, source) }

        val file = Files.writeString(root.resolve("media/plain-file"), "x")
        assertFailsWith<IllegalArgumentException> { DesktopStorageRelocation.stage(source, file) }
        assertFalse(Files.exists(source.resolve(DesktopStorageRelocation.MARKER_FILE)))
    }

    @Test
    fun aClosedLiveDatabaseMovesAndReopensWithItsQueue() = runTest {
        prepareDestinationParent()
        val checkpoint = checkpoint()
        openPortableStateDatabase(source.resolve("portable-state.db").toString()).let { database ->
            try { assertTrue(RoomQueueCheckpointStore(database.checkpoints()).write(checkpoint, null)) }
            finally { database.close() }
        }
        Files.writeString(source.resolve("settings.preferences"), "settings")
        Files.createDirectories(source.resolve("downloads"))
        Files.write(source.resolve("downloads/song.bin"), byteArrayOf(1, 2, 3, 4))
        Files.writeString(source.resolve("instance.lock"), "lock")
        Files.writeString(source.resolve("instance.endpoint"), "endpoint")
        Files.createDirectories(source.resolve("profile/account"))
        Files.writeString(source.resolve("profile/account/.owner.lock"), "owner")

        DesktopStorageRelocation.stage(source, destination())
        assertEquals(destination(), DesktopStorageRelocation.applyPendingForStartup(source, pointer))

        assertEquals(destination().toString(), Files.readString(pointer).trim())
        assertFalse(Files.exists(source.resolve(DesktopStorageRelocation.MARKER_FILE)))
        assertFalse(Files.exists(destination().resolve(DesktopStorageRelocation.MARKER_FILE)))
        assertEquals("settings", Files.readString(destination().resolve("settings.preferences")))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(destination().resolve("downloads/song.bin")))
        assertFalse(Files.exists(destination().resolve("instance.lock")))
        assertFalse(Files.exists(destination().resolve("instance.endpoint")))
        assertFalse(Files.exists(destination().resolve("profile/account/.owner.lock")))
        assertTrue(Files.exists(source.resolve("instance.lock")))
        assertTrue(Files.exists(source.resolve("instance.endpoint")))
        assertTrue(Files.exists(source.resolve("portable-state.db")))
        openPortableStateDatabase(destination().resolve("portable-state.db").toString()).let { database ->
            try { assertEquals(checkpoint, RoomQueueCheckpointStore(database.checkpoints()).read()) }
            finally { database.close() }
        }
    }

    @Test
    fun applyingDefersWhileTheSourceInstanceLockIsHeld() {
        prepareDestinationParent()
        Files.writeString(source.resolve("settings.preferences"), "settings")
        DesktopStorageRelocation.stage(source, destination())

        FileChannel.open(source.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                assertEquals(source, DesktopStorageRelocation.applyPendingForStartup(source, pointer))
            }
        }

        assertFalse(Files.exists(pointer))
        assertFalse(Files.exists(destination()))
        assertEquals("settings", Files.readString(source.resolve("settings.preferences")))
        assertEquals(destination(), DesktopStorageRelocation.applyPendingForStartup(source, pointer))
        assertEquals(destination().toString(), Files.readString(pointer).trim())
    }

    @Test
    fun aCompleteCopyWithoutPointerIsFinishedWithoutCopyingAgain() {
        prepareDestinationParent()
        Files.writeString(source.resolve("settings.preferences"), "original")
        Files.createDirectories(source.resolve("downloads"))
        Files.writeString(source.resolve("downloads/song.bin"), "audio")
        val migration = DesktopStorageRelocation.stage(source, destination())

        // The crash window between the rename and the pointer write: the receipt is in place, the pointer is not.
        Files.createDirectories(destination())
        Files.writeString(destination().resolve("settings.preferences"), "already copied")
        Files.write(destination().resolve(DesktopStorageRelocation.MARKER_FILE),
            DesktopStorageRelocation.encodeMarker(migration))

        assertEquals(destination(), DesktopStorageRelocation.applyPendingForStartup(source, pointer))
        assertEquals(destination().toString(), Files.readString(pointer).trim())
        assertEquals("already copied", Files.readString(destination().resolve("settings.preferences")))
        assertFalse(Files.exists(destination().resolve("downloads/song.bin")))
        assertFalse(Files.exists(source.resolve(DesktopStorageRelocation.MARKER_FILE)))
        assertFalse(Files.exists(destination().resolve(DesktopStorageRelocation.MARKER_FILE)))
    }

    @Test
    fun aPointerThatAlreadyNamesTheDestinationDropsTheMarker() {
        prepareDestinationParent()
        DesktopStorageRelocation.stage(source, destination())
        Files.createDirectories(destination())
        Files.writeString(destination().resolve("settings.preferences"), "copied")
        Files.createDirectories(pointer.parent)
        Files.writeString(pointer, destination().toString() + "\n")

        assertEquals(destination(), DesktopStorageRelocation.applyPendingForStartup(source, pointer))
        assertFalse(Files.exists(source.resolve(DesktopStorageRelocation.MARKER_FILE)))
        assertEquals("copied", Files.readString(destination().resolve("settings.preferences")))
    }

    @Test
    fun aMalformedMarkerIsDiscarded() {
        Files.writeString(source.resolve(DesktopStorageRelocation.MARKER_FILE), "{not json")

        assertEquals(source, DesktopStorageRelocation.applyPendingForStartup(source, pointer))

        assertFalse(Files.exists(source.resolve(DesktopStorageRelocation.MARKER_FILE)))
        assertFalse(Files.exists(pointer))
    }

    @Test
    fun theSnapshotSkipsControlFilesAndTheTransientSharedMemoryIndex() {
        prepareDestinationParent()
        Files.writeString(source.resolve("portable-state.db"), "database")
        Files.writeString(source.resolve("portable-state.db-wal"), "wal")
        Files.writeString(source.resolve("portable-state.db-shm"), "shm")
        Files.writeString(source.resolve("instance.sock"), "socket")
        Files.writeString(source.resolve("settings.preferences"), "settings")

        DesktopStorageRelocation.stage(source, destination())
        DesktopStorageRelocation.applyPendingForStartup(source, pointer)

        assertEquals("database", Files.readString(destination().resolve("portable-state.db")))
        assertEquals("wal", Files.readString(destination().resolve("portable-state.db-wal")))
        assertFalse(Files.exists(destination().resolve("portable-state.db-shm")))
        assertFalse(Files.exists(destination().resolve("instance.sock")))
        assertTrue(Files.exists(source.resolve("portable-state.db-shm")))
    }

    @Test
    fun anInvalidDestinationDiscardsTheRequestInsteadOfBlockingEveryStart() {
        prepareDestinationParent()
        Files.writeString(source.resolve("settings.preferences"), "settings")
        DesktopStorageRelocation.stage(source, destination())
        Files.createDirectories(destination())
        Files.writeString(destination().resolve("foreign"), "not ours")

        assertEquals(source, DesktopStorageRelocation.applyPendingForStartup(source, pointer))

        assertFalse(Files.exists(source.resolve(DesktopStorageRelocation.MARKER_FILE)))
        assertFalse(Files.exists(pointer))
        assertEquals("settings", Files.readString(source.resolve("settings.preferences")))
    }

    @Test
    fun thePointerWinsOnlyWhenItNamesAnExistingDirectory() {
        val fallback = Files.createDirectories(root.resolve("fallback"))
        prepareDestinationParent()
        Files.createDirectories(destination())
        assertEquals(fallback, DesktopStorageRelocation.resolveStorageDirectory(fallback, pointer))

        Files.createDirectories(pointer.parent)
        Files.writeString(pointer, "  " + destination() + "  \n")
        assertEquals(destination(), DesktopStorageRelocation.resolveStorageDirectory(fallback, pointer))

        Files.writeString(pointer, root.resolve("missing").toString())
        assertEquals(fallback, DesktopStorageRelocation.resolveStorageDirectory(fallback, pointer))
    }
}

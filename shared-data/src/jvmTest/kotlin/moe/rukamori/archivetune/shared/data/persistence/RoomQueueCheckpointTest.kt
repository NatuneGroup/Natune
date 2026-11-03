package moe.rukamori.archivetune.shared.data.persistence

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.persistence.QueueCheckpoint
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistPhase

class RoomQueueCheckpointTest {
    private fun checkpoint(revision: Long = 1): QueueCheckpoint {
        val song = ContentItem(ContentRef("youtube", ContentKind.SONG, "duplicate"), "日本語 🎵", durationMs = 30_000)
        return QueueCheckpoint(QueueState(revision,
            listOf(ContentOccurrence("a", song, "set-a"), ContentOccurrence("b", song, "set-b")),
            "b", listOf("b", "a"), FinitePlaylistPhase.PAGEABLE, shuffleEnabled = true), 1234)
    }
    @Test fun duplicateOccurrenceAndPositionSurviveRealDatabaseReopen() = runTest {
        val dir = Files.createTempDirectory("archivetune-queue-test")
        val file = dir.resolve("portable-state.db")
        try {
            openPortableStateDatabase(file.toString()).let { db ->
                try { assertTrue(RoomQueueCheckpointStore(db.checkpoints()).write(checkpoint(), null)) }
                finally { db.close() }
            }
            openPortableStateDatabase(file.toString()).let { db ->
                try { assertEquals(checkpoint(), RoomQueueCheckpointStore(db.checkpoints()).read()) }
                finally { db.close() }
            }
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun staleQueueAndPositionCannotOverwriteNewOwnerRevision() = runTest {
        val db = openPortableStateDatabase(":memory:")
        try {
            val store = RoomQueueCheckpointStore(db.checkpoints())
            assertTrue(store.write(checkpoint(), null))
            assertFalse(store.write(checkpoint(2), null))
            assertTrue(store.write(checkpoint(2), 1))
            assertFalse(store.write(checkpoint(3), 1))
            assertFalse(store.updatePosition(1, "b", 9999))
            assertFalse(store.updatePosition(2, "a", 9999))
            assertTrue(store.updatePosition(2, "b", 9876))
            assertEquals(9876L, store.read()!!.positionMs)
        } finally { db.close() }
    }
    @Test fun concurrentCompareAndSwapAllowsExactlyOneWriter() = runTest {
        val db = openPortableStateDatabase(":memory:")
        try {
            val store = RoomQueueCheckpointStore(db.checkpoints())
            assertTrue(store.write(checkpoint(), null))
            val results = coroutineScope {
                List(8) { async(Dispatchers.Default) { store.write(checkpoint(2), 1) } }.awaitAll()
            }
            assertEquals(1, results.count { it })
        } finally { db.close() }
    }
    @Test fun restoreHydratesMissingDurationsInBatchesWhileReadKeepsTheStoredQueue() = runTest {
        val db = openPortableStateDatabase(":memory:")
        try {
            val saved = List(1_000) { ContentItem(ContentRef("youtube", ContentKind.SONG, "s$it"), "t$it", durationMs = 1_000L + it) }
            saved.forEach { db.library().putItem(libraryItemRow(it, liked = false, addedSeq = 0, likedSeq = 0)) }
            // Same media id as a queued entry under another provider: that duration belongs to a different song.
            val tidal = ContentItem(ContentRef("tidal", ContentKind.SONG, "other"), "x", durationMs = 5)
            db.library().putItem(libraryItemRow(tidal, liked = false, addedSeq = 0, likedSeq = 0))
            val entries = saved.map { ContentOccurrence("o-${it.ref.id}", it.copy(durationMs = null)) } +
                ContentOccurrence("foreign", ContentItem(ContentRef("youtube", ContentKind.SONG, "other"), "f")) +
                ContentOccurrence("unknown", ContentItem(ContentRef("youtube", ContentKind.SONG, "missing"), "u")) +
                ContentOccurrence("kept", ContentItem(ContentRef("youtube", ContentKind.SONG, "s0"), "k", durationMs = 42))
            val ids = entries.map { it.occurrenceId }
            val checkpoint = QueueCheckpoint(QueueState(1, entries, ids.first(), ids), 0)
            val store = RoomQueueCheckpointStore(db.checkpoints(), db.library())
            assertTrue(store.write(checkpoint, null))
            assertEquals(checkpoint, store.read())
            val restored = assertNotNull(store.readForRestore()).queue.entries
                .associate { it.occurrenceId to it.item.durationMs }
            assertEquals(1_000L, restored["o-s0"])
            assertEquals(1_999L, restored["o-s999"])
            assertNull(restored["foreign"])
            assertNull(restored["unknown"])
            assertEquals(42L, restored["kept"])
        } finally { db.close() }
    }
    @Test fun jsonRejectsBadVersionAndDoesNotCoalesceDuplicates() {
        val encoded = QueueCheckpointJson.encode(checkpoint())
        assertEquals(checkpoint(), QueueCheckpointJson.decode(encoded))
        assertFailsWith<IllegalArgumentException> {
            QueueCheckpointJson.decode(encoded.replace("\"version\":1", "\"version\":99"))
        }
    }
    @Test fun corruptCheckpointIsNotSilentlyOverwrittenAndAndroidDatabaseNameIsRejected() = runTest {
        val db = openPortableStateDatabase(":memory:")
        try {
            val corrupt = QueueCheckpointRow(revision = 1, json = """{"version":99}""")
            db.checkpoints().put(corrupt)
            val store = RoomQueueCheckpointStore(db.checkpoints())
            assertFailsWith<IllegalArgumentException> { store.read() }
            assertFailsWith<IllegalArgumentException> { store.write(checkpoint(2), 1) }
            assertEquals(corrupt, db.checkpoints().read())
        } finally { db.close() }
        assertFailsWith<IllegalArgumentException> {
            openPortableStateDatabase(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "SONG.DB").toString())
        }
    }
}

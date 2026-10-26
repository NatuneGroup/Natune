/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.*
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import moe.rukamori.archivetune.shared.domain.persistence.QueueCheckpoint
import moe.rukamori.archivetune.shared.domain.persistence.QueueCheckpointStore

/**
 * New portable state file, NEVER opened over Android's schema-36 song.db. Version 2 adds the tag catalog and the
 * playlist assignments, moved from the v1 JSON column by [PortableStateMigrations.V1_TO_V2]. Version 3 adds each
 * playlist's own song order and each entry's creation sequence, by [PortableStateMigrations.V2_TO_V3]. Version 4 adds
 * the local listening events that history, statistics and PLAY_TIME order read, by [PortableStateMigrations.V3_TO_V4].
 * Version 5 adds the blocked artists, the artist and album identities of each listen, and the sequences that order
 * library songs, by [PortableStateMigrations.V4_TO_V5]. Version 6 adds the index of stored songs, the downloads and
 * the streamed cache entries that the downloaded and cache lists read, by [PortableStateMigrations.V5_TO_V6].
 * Version 7 adds each download's byte count and the complete album track lists, by
 * [PortableStateMigrations.V6_TO_V7].
 */
@Database(
    entities = [QueueCheckpointRow::class, LibraryItemRow::class, PortablePlaylistRow::class,
        PortablePlaylistEntry::class, PortableTagRow::class, PortablePlaylistTagRow::class,
        PortableListenEventRow::class, PortableListenEntityRow::class, PortableArtistBlockRow::class,
        PortableStoredSongRow::class, PortableAlbumCompleteRow::class, PortableAlbumTrackRow::class],
    version = 7,
    exportSchema = true,
)
@ConstructedBy(PortableStateDatabaseConstructor::class)
abstract class PortableStateDatabase : RoomDatabase() {
    abstract fun checkpoints(): QueueCheckpointDao
    abstract fun library(): PortableLibraryDao
    abstract fun listens(): ListeningEventDao
    abstract fun artistBlocks(): ArtistBlockDao
    abstract fun storedSongs(): PortableStoredSongDao
    abstract fun albumTracks(): PortableAlbumTrackDao
}

expect object PortableStateDatabaseConstructor : RoomDatabaseConstructor<PortableStateDatabase> {
    override fun initialize(): PortableStateDatabase
}

fun RoomDatabase.Builder<PortableStateDatabase>.buildPortableStateDatabase(): PortableStateDatabase =
    addMigrations(
        PortableStateMigrations.V1_TO_V2,
        PortableStateMigrations.V2_TO_V3,
        PortableStateMigrations.V3_TO_V4,
        PortableStateMigrations.V4_TO_V5,
        PortableStateMigrations.V5_TO_V6,
        PortableStateMigrations.V6_TO_V7,
    )
        .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.Default).build()

@Entity(tableName = "queue_checkpoint")
data class QueueCheckpointRow(@PrimaryKey val id: Int = 0, val revision: Long, val json: String)

@Dao
abstract class QueueCheckpointDao {
    @Query("SELECT * FROM queue_checkpoint WHERE id = 0")
    abstract suspend fun read(): QueueCheckpointRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(row: QueueCheckpointRow)

    @Transaction
    open suspend fun compareAndSwap(checkpoint: QueueCheckpoint, expectedRevision: Long?): Boolean {
        val current = read()
        current?.let { require(QueueCheckpointJson.decode(it.json).queue.revision == it.revision) }
        if (current?.revision != expectedRevision) return false
        if (current != null && checkpoint.queue.revision <= current.revision) return false
        put(QueueCheckpointRow(revision = checkpoint.queue.revision, json = QueueCheckpointJson.encode(checkpoint)))
        return true
    }

    @Transaction
    open suspend fun updatePosition(expectedRevision: Long, occurrenceId: String, positionMs: Long): Boolean {
        require(positionMs >= 0)
        val row = read() ?: return false
        if (row.revision != expectedRevision) return false
        val checkpoint = QueueCheckpointJson.decode(row.json)
        require(checkpoint.queue.revision == row.revision)
        if (checkpoint.queue.currentOccurrenceId != occurrenceId) return false
        put(row.copy(json = QueueCheckpointJson.encode(checkpoint.copy(positionMs = positionMs))))
        return true
    }
}

class RoomQueueCheckpointStore(private val dao: QueueCheckpointDao) : QueueCheckpointStore {
    override suspend fun read(): QueueCheckpoint? = dao.read()?.let {
        QueueCheckpointJson.decode(it.json).also { checkpoint -> require(checkpoint.queue.revision == it.revision) }
    }
    override suspend fun write(checkpoint: QueueCheckpoint, expectedRevision: Long?): Boolean =
        dao.compareAndSwap(checkpoint, expectedRevision)
    override suspend fun updatePosition(expectedRevision: Long, occurrenceId: String, positionMs: Long): Boolean =
        dao.updatePosition(expectedRevision, occurrenceId, positionMs)
}

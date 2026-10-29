package moe.rukamori.archivetune.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.shared.domain.playback.PodcastProgress
import moe.rukamori.archivetune.shared.domain.playback.PodcastProgressStore

@Entity(tableName = "podcast_progress")
data class PodcastProgressEntity(
    @PrimaryKey val episodeId: String,
    val positionMs: Long,
    val durationMs: Long?,
    val finished: Boolean,
    val updatedAtMs: Long,
) {
    fun progress() = PodcastProgress(episodeId, positionMs, durationMs, finished, updatedAtMs)
}

@Dao
interface PodcastProgressDao {
    @Query("SELECT * FROM podcast_progress WHERE episodeId = :episodeId")
    suspend fun read(episodeId: String): PodcastProgressEntity?

    @Query("SELECT * FROM podcast_progress WHERE episodeId = :episodeId")
    fun observe(episodeId: String): Flow<PodcastProgressEntity?>

    @Query("SELECT * FROM podcast_progress")
    fun observeAll(): Flow<List<PodcastProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: PodcastProgressEntity)
}

class AndroidPodcastProgressStore(private val dao: PodcastProgressDao) : PodcastProgressStore {
    override suspend fun read(episodeId: String): PodcastProgress? = dao.read(episodeId)?.progress()
    override suspend fun write(progress: PodcastProgress) {
        dao.put(PodcastProgressEntity(progress.episodeId, progress.positionMs, progress.durationMs, progress.finished, progress.updatedAtMs))
    }
    override fun observeAll(): Flow<List<PodcastProgress>> = dao.observeAll().map { rows -> rows.map { it.progress() } }
}

/** Kept self-contained so parallel feature lanes can renumber this step at integration. */
object PodcastProgressMigration : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `podcast_progress` (`episodeId` TEXT NOT NULL, " +
                "`positionMs` INTEGER NOT NULL, `durationMs` INTEGER, `finished` INTEGER NOT NULL, " +
                "`updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`episodeId`))",
        )
    }
}

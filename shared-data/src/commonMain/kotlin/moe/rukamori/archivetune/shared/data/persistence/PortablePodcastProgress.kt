package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.shared.domain.playback.PodcastProgress
import moe.rukamori.archivetune.shared.domain.playback.PodcastProgressStore

/**
 * Where playback of one podcast episode stopped. [episodeId] is provider-qualified, so an id from one provider never
 * resumes another. A finished episode keeps its row with position zero, so a replay starts from the top.
 */
@Entity(tableName = "portable_podcast_progress")
data class PortablePodcastProgressRow(
    @PrimaryKey val episodeId: String,
    val positionMs: Long,
    val durationMs: Long?,
    val finished: Boolean,
    val updatedAtMs: Long,
)

@Dao
abstract class PortablePodcastProgressDao {
    @Query("SELECT * FROM portable_podcast_progress WHERE episodeId = :episodeId")
    abstract suspend fun read(episodeId: String): PortablePodcastProgressRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(row: PortablePodcastProgressRow)

    @Query("SELECT * FROM portable_podcast_progress")
    abstract fun observeAll(): Flow<List<PortablePodcastProgressRow>>
}

/** Reads and writes episode progress. Callers serialize writes, so the newest row is the one that persists. */
class RoomPodcastProgressStore(private val dao: PortablePodcastProgressDao) : PodcastProgressStore {
    override suspend fun read(episodeId: String): PodcastProgress? = dao.read(episodeId)?.progress()

    override suspend fun write(progress: PodcastProgress) {
        dao.put(PortablePodcastProgressRow(progress.episodeId, progress.positionMs, progress.durationMs, progress.finished, progress.updatedAtMs))
    }

    override fun observeAll(): Flow<List<PodcastProgress>> = dao.observeAll().map { rows -> rows.map { it.progress() } }
}

private fun PortablePodcastProgressRow.progress() = PodcastProgress(episodeId, positionMs, durationMs, finished, updatedAtMs)

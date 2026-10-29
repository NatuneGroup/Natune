package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

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
}

/** Reads and writes episode progress. Callers serialize writes, so the newest row is the one that persists. */
class RoomPodcastProgressStore(private val dao: PortablePodcastProgressDao) {
    suspend fun read(episodeId: String): PortablePodcastProgressRow? = dao.read(episodeId)

    suspend fun write(row: PortablePodcastProgressRow) {
        dao.put(row)
    }
}

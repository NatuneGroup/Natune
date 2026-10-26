package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One past search text, in the order it was last used. The baseline's `search_history` table keeps the same shape:
 * an autoincrement key orders the list newest first, and the unique `query` index holds one row per text, so a repeat
 * search replaces its row and moves to the top.
 */
@Entity(tableName = "portable_search_history", indices = [Index(value = ["query"], unique = true)])
data class PortableSearchHistoryRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
)

@Dao
abstract class PortableSearchHistoryDao {
    /** Prefix-matched history, newest first, as the search screen lists it. */
    @Query("SELECT * FROM portable_search_history WHERE `query` LIKE :prefix || '%' ORDER BY id DESC")
    abstract fun observe(prefix: String): Flow<List<PortableSearchHistoryRow>>

    /**
     * Records a search. REPLACE gives a repeated text a new row, so it counts once and resurfaces at the top, which
     * matches how the unique index made the baseline behave.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(row: PortableSearchHistoryRow): Long

    @Delete
    abstract suspend fun delete(row: PortableSearchHistoryRow)

    @Query("DELETE FROM portable_search_history")
    abstract suspend fun clear()
}

/**
 * The `set_video_id` table of the baseline: the playlist entry id a synced YouTube playlist gives a video, kept per
 * video id so a later sync matches the entries it already placed. A null id means the entry was removed remotely.
 */
@Entity(tableName = "portable_set_video_id")
data class PortableSetVideoIdRow(
    @PrimaryKey val videoId: String,
    val setVideoId: String?,
)

@Dao
abstract class PortableSetVideoIdDao {
    @Query("SELECT * FROM portable_set_video_id WHERE videoId = :videoId")
    abstract suspend fun find(videoId: String): PortableSetVideoIdRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(row: PortableSetVideoIdRow)

    @Query("DELETE FROM portable_set_video_id")
    abstract suspend fun clear()
}

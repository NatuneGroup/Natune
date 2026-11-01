package moe.rukamori.archivetune.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Per-song lyrics sync corrections, kept as their own interface so [DatabaseDao] only mixes them
 * in. Room resolves the annotated members on [DatabaseDao] through this superinterface.
 */
@Dao
interface LyricsOffsetDao {
    @Query("SELECT lyricsOffset FROM song WHERE id = :songId")
    fun lyricsOffset(songId: String): Flow<Int?>

    @Query("UPDATE song SET lyricsOffset = :offset WHERE id = :songId")
    fun setLyricsOffset(songId: String, offset: Int)
}

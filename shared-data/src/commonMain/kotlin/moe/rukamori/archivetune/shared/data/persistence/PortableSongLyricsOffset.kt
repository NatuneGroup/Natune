package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import moe.rukamori.archivetune.shared.domain.lyrics.MAX_LYRICS_SYNC_OFFSET_MS
import moe.rukamori.archivetune.shared.domain.lyrics.SongLyricsOffsetRepository
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/** No foreign key: leaving a provider, a queue or a library must not drop a correction the user dialled in. */
@Entity(tableName = "portable_song_lyrics_offset", primaryKeys = ["provider", "songId"])
data class PortableSongLyricsOffsetRow(
    val provider: String,
    val songId: String,
    val offsetMs: Long,
)

@Dao
abstract class PortableSongLyricsOffsetDao {
    @Query("SELECT offsetMs FROM portable_song_lyrics_offset WHERE provider = :provider AND songId = :songId")
    abstract fun observe(provider: String, songId: String): Flow<Long?>

    @Query("SELECT offsetMs FROM portable_song_lyrics_offset WHERE provider = :provider AND songId = :songId")
    abstract suspend fun offset(provider: String, songId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(row: PortableSongLyricsOffsetRow)

    @Query("DELETE FROM portable_song_lyrics_offset WHERE provider = :provider AND songId = :songId")
    abstract suspend fun delete(provider: String, songId: String)
}

class RoomSongLyricsOffsetRepository(private val dao: PortableSongLyricsOffsetDao) : SongLyricsOffsetRepository {
    // Non-song queue items (episodes, videos) carry no offset; the flow stays quiet rather than throwing.
    override fun observeOffset(song: ContentRef): Flow<Long?> =
        if (song.kind == ContentKind.SONG) dao.observe(song.provider, song.id) else flowOf(null)

    override suspend fun offset(song: ContentRef): Long? =
        if (song.kind == ContentKind.SONG) dao.offset(song.provider, song.id) else null

    override suspend fun setOffset(song: ContentRef, offsetMs: Long?) {
        require(song.kind == ContentKind.SONG)
        if (offsetMs == null) {
            dao.delete(song.provider, song.id)
        } else {
            require(offsetMs in -MAX_LYRICS_SYNC_OFFSET_MS..MAX_LYRICS_SYNC_OFFSET_MS)
            dao.put(PortableSongLyricsOffsetRow(song.provider, song.id, offsetMs))
        }
    }
}

package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.songmetadata.SongMetadataOverride
import moe.rukamori.archivetune.shared.domain.songmetadata.SongMetadataRepository

/** No foreign key: removing/re-scanning a local file or refreshing a provider must not remove its edit. */
@Entity(tableName = "portable_song_metadata_override", primaryKeys = ["provider", "songId"])
data class PortableSongMetadataOverrideRow(
    val provider: String,
    val songId: String,
    val title: String?,
    val artistNamesJson: String?,
    val updatedAtMs: Long,
)

@Dao
abstract class PortableSongMetadataOverrideDao {
    @Query("SELECT * FROM portable_song_metadata_override")
    abstract fun observe(): Flow<List<PortableSongMetadataOverrideRow>>

    @Query("SELECT * FROM portable_song_metadata_override")
    abstract suspend fun rows(): List<PortableSongMetadataOverrideRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(row: PortableSongMetadataOverrideRow)

    @Query("DELETE FROM portable_song_metadata_override WHERE provider = :provider AND songId = :songId")
    abstract suspend fun delete(provider: String, songId: String)

    @Transaction
    open suspend fun set(provider: String, songId: String, row: PortableSongMetadataOverrideRow?) {
        require(row == null || row.provider == provider && row.songId == songId)
        if (row == null) delete(provider, songId) else put(row)
    }
}

class RoomSongMetadataRepository(private val dao: PortableSongMetadataOverrideDao) : SongMetadataRepository {
    override fun observeOverrides(): Flow<Map<ContentRef, SongMetadataOverride>> = dao.observe().map(::decode)
    override suspend fun overrides(): Map<ContentRef, SongMetadataOverride> = decode(dao.rows())

    override suspend fun setOverride(song: ContentRef, metadata: SongMetadataOverride?) {
        require(song.kind == ContentKind.SONG)
        require(metadata?.title == null || metadata.title.isNotBlank())
        require(metadata?.artistNames == null || metadata.artistNames.isNotEmpty() && metadata.artistNames.all(String::isNotBlank))
        val row = metadata?.takeIf { it.title != null || it.artistNames != null }?.let {
            PortableSongMetadataOverrideRow(
                song.provider, song.id, it.title,
                it.artistNames?.let { names -> JsonArray(names.map(::JsonPrimitive)).toString() }, it.updatedAtMs,
            )
        }
        dao.set(song.provider, song.id, row)
    }

    private fun decode(rows: List<PortableSongMetadataOverrideRow>): Map<ContentRef, SongMetadataOverride> =
        rows.associate { row ->
            ContentRef(row.provider, ContentKind.SONG, row.songId) to SongMetadataOverride(
                row.title,
                row.artistNamesJson?.let { Json.parseToJsonElement(it).jsonArray.map { name -> name.jsonPrimitive.content } },
                row.updatedAtMs,
            )
        }
}

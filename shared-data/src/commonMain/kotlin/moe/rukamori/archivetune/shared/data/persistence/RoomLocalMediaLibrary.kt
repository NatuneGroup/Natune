package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import moe.rukamori.archivetune.shared.domain.localmedia.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.LocalScanExtractionVersionKey
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore

/** One scanned local file's scan state; the library row for it lives in `library_item` under provider `local`. */
@Entity(tableName = "portable_local_track")
data class PortableLocalTrackRow(
    @PrimaryKey val id: String,
    val path: String,
    val modifiedEpochMs: Long,
    val sizeBytes: Long,
    val title: String,
    val artistsJson: String,
    val albumTitle: String?,
    val albumArtistName: String?,
    val albumYear: Int?,
    val durationMs: Long?,
    val year: Int?,
    val trackNumber: Int?,
    val artworkUri: String?,
    val lyricsText: String?,
)

@Dao
abstract class PortableLocalTrackDao {
    @Query("SELECT * FROM portable_local_track")
    abstract suspend fun tracks(): List<PortableLocalTrackRow>

    @Query("SELECT * FROM library_item WHERE provider = 'local' AND kind = 'SONG' ORDER BY title, mediaId")
    abstract fun observeLocalSongRows(): Flow<List<LibraryItemRow>>

    @Query("SELECT * FROM library_item WHERE provider = 'local' AND kind = 'SONG' AND mediaId = :id")
    abstract suspend fun localItem(id: String): LibraryItemRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putTrack(row: PortableLocalTrackRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putItem(row: LibraryItemRow)

    @Query("DELETE FROM portable_local_track WHERE id = :id")
    abstract suspend fun deleteTrack(id: String)

    @Query("DELETE FROM library_item WHERE provider = 'local' AND kind = 'SONG' AND mediaId = :id")
    abstract suspend fun deleteItem(id: String)

    /**
     * The scan join in one transaction: every surviving row upserts, every vanished file's row and item delete
     * together, and a row that predates a scan keeps its liked and sequence columns exactly like the retained
     * join over the songs table.
     */
    @Transaction
    open suspend fun commitScan(records: List<PortableLocalTrackRow>, removedIds: List<String>,
        itemJsonOf: (PortableLocalTrackRow) -> LibraryItemRow): LocalScanSummary {
        var upserted = 0
        for (record in records) {
            putTrack(record)
            val row = itemJsonOf(record)
            val prior = localItem(record.id)
            putItem(if (prior != null) row.copy(liked = prior.liked, addedSeq = prior.addedSeq,
                likedSeq = prior.likedSeq) else row)
            upserted++
        }
        for (id in removedIds) {
            deleteTrack(id); deleteItem(id)
        }
        return LocalScanSummary(records.size, 0, removedIds.size, upserted)
    }
}

/**
 * The local-media library over the portable state database. Scanned files become `local`-provider song rows in
 * the same `library_item` table remote songs use, so every library surface lists them the way the Android
 * MusicDatabase did. [settings] persists the extraction version the scanner compares.
 */
class RoomLocalMediaLibrary(
    private val dao: PortableLocalTrackDao,
    private val settings: SettingsStore,
) : LocalMediaLibrary {
    override suspend fun extractionVersion(): Int =
        settings.snapshots.value?.get(LocalScanExtractionVersionKey) ?: 0

    override suspend fun previousScan(): List<LocalTrackRecord> = dao.tracks().map { it.toRecord() }

    override suspend fun commitScan(
        records: List<LocalTrackRecord>, removedIds: List<String>, extractionVersion: Int,
    ): LocalScanSummary {
        val summary = dao.commitScan(records.map { it.toRow() }, removedIds) { row ->
            libraryItemRow(row.toItem(), liked = false, addedSeq = 0L, likedSeq = 0L)
        }
        settings.edit { this[LocalScanExtractionVersionKey] = extractionVersion }
        return summary
    }

    override fun observeLocalSongs(): Flow<List<ContentItem>> =
        dao.observeLocalSongRows().map { rows -> rows.map { ContentJson.decode(Json.parseToJsonElement(it.json).jsonObject).item } }
}

private fun PortableLocalTrackRow.toItem(): ContentItem {
    val artists = Json.parseToJsonElement(artistsJson).jsonArray.map {
        ContentCredit(it.jsonPrimitive.content)
    }
    return ContentItem(
        ref = ContentRef("local", ContentKind.SONG, id),
        title = title,
        subtitle = artists.joinToString(", ") { it.name },
        artworkUri = artworkUri,
        durationMs = durationMs,
        album = albumTitle,
        artists = artists,
        albumId = albumTitle?.let { LocalStableIds.albumId(it, albumArtistName?.let(LocalStableIds::artistId)) },
    )
}

private fun PortableLocalTrackRow.toRecord(): LocalTrackRecord {
    val artists = Json.parseToJsonElement(artistsJson).jsonArray.map {
        val name = it.jsonPrimitive.content
        LocalArtistRecord(LocalStableIds.artistId(name), name)
    }
    val album = albumTitle?.let {
        val albumArtist = albumArtistName?.let { name -> LocalArtistRecord(LocalStableIds.artistId(name), name) }
        LocalAlbumRecord(LocalStableIds.albumId(it, albumArtist?.id), it, albumArtist, albumYear)
    }
    return LocalTrackRecord(id, path, title, artists, album, durationMs, modifiedEpochMs, sizeBytes,
        year, trackNumber, artworkUri, lyricsText)
}

private fun LocalTrackRecord.toRow() = PortableLocalTrackRow(
    id = id, path = path, modifiedEpochMs = modifiedEpochMs, sizeBytes = sizeBytes, title = title,
    artistsJson = buildJsonArray { artists.forEach { add(JsonPrimitive(it.name)) } }.toString(),
    albumTitle = album?.title, albumArtistName = album?.artist?.name, albumYear = album?.year,
    durationMs = durationMs, year = year, trackNumber = trackNumber,
    artworkUri = artworkUri, lyricsText = lyricsText,
)

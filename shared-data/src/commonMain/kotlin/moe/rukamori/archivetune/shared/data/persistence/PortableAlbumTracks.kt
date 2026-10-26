/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/**
 * The complete track list of one album, as a whole walk of the provider read it. [trackCount] is the number of
 * [PortableAlbumTrackRow]s the album holds, and the album is judged full only when the two agree. An album with no row
 * here has an unknown track list, so it is never full.
 */
@Entity(tableName = "portable_album_complete", primaryKeys = ["albumProvider", "albumId"])
data class PortableAlbumCompleteRow(
    val albumProvider: String,
    val albumId: String,
    val trackCount: Int,
)

/**
 * One track of a complete album, in the order the album lists it. [position] keeps a track that the album repeats
 * as two distinct rows.
 */
@Entity(tableName = "portable_album_track", primaryKeys = ["albumProvider", "albumId", "position"])
data class PortableAlbumTrackRow(
    val albumProvider: String,
    val albumId: String,
    val position: Int,
    val provider: String,
    val mediaId: String,
)

@Dao
abstract class PortableAlbumTrackDao {
    @Query("SELECT * FROM portable_album_complete")
    abstract fun observeComplete(): Flow<List<PortableAlbumCompleteRow>>

    @Query("SELECT * FROM portable_album_track ORDER BY albumProvider, albumId, position")
    abstract fun observeTracks(): Flow<List<PortableAlbumTrackRow>>

    @Query("DELETE FROM portable_album_track WHERE albumProvider = :albumProvider AND albumId = :albumId")
    abstract suspend fun deleteTracks(albumProvider: String, albumId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putTracks(rows: List<PortableAlbumTrackRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putComplete(row: PortableAlbumCompleteRow)

    /**
     * Replaces the recorded list of one album in a single transaction, so an album is never seen with the members of
     * two different walks, and a later whole walk supersedes an earlier one.
     */
    @Transaction
    open suspend fun replaceComplete(row: PortableAlbumCompleteRow, tracks: List<PortableAlbumTrackRow>) {
        deleteTracks(row.albumProvider, row.albumId)
        putTracks(tracks)
        putComplete(row)
    }
}

/**
 * The write that records the complete track list of an album. The caller passes only a list that a whole walk
 * completed, so a partial page never becomes a recorded album, and a later whole walk replaces an earlier one.
 */
interface AlbumCompletionIndex {
    /** Records [tracks], the album's songs in its own order, as its complete list. The list must not be empty. */
    suspend fun recordComplete(album: ContentRef, tracks: List<ContentRef>)
}

/** The Room-backed [AlbumCompletionIndex]. The record is written in one transaction. */
class RoomAlbumCompletionIndex(private val dao: PortableAlbumTrackDao) : AlbumCompletionIndex {
    override suspend fun recordComplete(album: ContentRef, tracks: List<ContentRef>) {
        require(album.kind == ContentKind.ALBUM) { "Only albums have a complete track list" }
        require(tracks.isNotEmpty() && tracks.all { it.kind == ContentKind.SONG }) { "A complete album lists songs" }
        dao.replaceComplete(
            PortableAlbumCompleteRow(album.provider, album.id, tracks.size),
            tracks.mapIndexed { position, track ->
                PortableAlbumTrackRow(album.provider, album.id, position, track.provider, track.id)
            },
        )
    }
}

/**
 * The albums that are full: each has a recorded complete list, the list holds as many tracks as its count says, and
 * every track of it is among [downloaded]. An album with no recorded list, or a count that disagrees with its list, is
 * unknown, and an unknown album is not full.
 */
internal fun fullyDownloadedAlbums(
    downloaded: Set<ContentRef>,
    albums: List<PortableAlbumCompleteRow>,
    tracks: List<PortableAlbumTrackRow>,
): Set<ContentRef> {
    val membersByAlbum = tracks.groupBy { ContentRef(it.albumProvider, ContentKind.ALBUM, it.albumId) }
    return albums.mapNotNullTo(HashSet<ContentRef>()) { row ->
        val album = ContentRef(row.albumProvider, ContentKind.ALBUM, row.albumId)
        val members = membersByAlbum[album].orEmpty()
        val whole = members.isNotEmpty() && members.size == row.trackCount &&
            members.all { ContentRef(it.provider, ContentKind.SONG, it.mediaId) in downloaded }
        album.takeIf { whole }
    }
}

package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

/**
 * How a stored song's audio reached this device. The names are written to the table, so they are part of its format.
 * A download stays until the user removes it. A cache entry is kept only while the player's cache holds the song.
 */
internal object StoredSongOrigin {
    const val DOWNLOAD = "DOWNLOAD"
    const val CACHE = "CACHE"
}

/**
 * One song whose audio this device stores. [origin] is a [StoredSongOrigin] name and [source] is the audio source
 * the bytes came from. [provider] and [mediaId] name the song, since two providers can name one media id.
 * [quality] is the download quality, and it is null for a cache entry, because the player records none.
 * [bytes] is the size a download committed, which the startup reconciliation checks against its file. A cache entry
 * has none, and neither has a download written before sizes were kept, until reconciliation adopts or drops it.
 * [storedAtMs] is when the audio was first committed: the download's completion, or the first time the cache held
 * the whole song. The cache list orders by it. [json] is the occurrence without its artwork, since a portable index
 * never keeps an artwork address that a provider could have signed.
 */
@Entity(tableName = "portable_stored_song", primaryKeys = ["origin", "provider", "mediaId"])
data class PortableStoredSongRow(
    val origin: String,
    val provider: String,
    val mediaId: String,
    val source: String,
    val quality: String?,
    val storedAtMs: Long,
    val json: String,
    val bytes: Long?,
)

/** The earlier download of a song, when it was a different file. The caller removes that file. */
data class StoredDownload(val source: AudioSourceType, val quality: String)

/**
 * A download the index lists. [provider] owns [mediaId]. [bytes] is the size it committed. It is null for a row
 * written before sizes were kept, whose file has no size to check against until reconciliation adopts it.
 */
data class ListedDownload(
    val provider: String,
    val mediaId: String,
    val source: AudioSourceType,
    val quality: String,
    val bytes: Long?,
)

@Dao
abstract class PortableStoredSongDao {
    /** Every stored song of [origin] in a fixed key order. The lists sort the complete set themselves. */
    @Query("SELECT * FROM portable_stored_song WHERE origin = :origin ORDER BY provider, mediaId")
    abstract fun observe(origin: String): Flow<List<PortableStoredSongRow>>

    /** Every stored song of [origin], read once. The startup reconciliation checks each one against its file. */
    @Query("SELECT * FROM portable_stored_song WHERE origin = :origin")
    abstract suspend fun rows(origin: String): List<PortableStoredSongRow>

    /** The row of one song under [origin], keyed by its provider and media id. */
    @Query(
        "SELECT * FROM portable_stored_song WHERE origin = :origin AND provider = :provider AND mediaId = :mediaId",
    )
    abstract suspend fun find(origin: String, provider: String, mediaId: String): PortableStoredSongRow?

    /** The row of one download. Its source and quality are part of the key, so another quality is another row. */
    @Query(
        "SELECT * FROM portable_stored_song WHERE origin = :origin AND provider = :provider " +
            "AND mediaId = :mediaId AND source = :source AND quality = :quality LIMIT 1",
    )
    abstract suspend fun downloadAt(
        origin: String,
        provider: String,
        mediaId: String,
        source: String,
        quality: String,
    ): PortableStoredSongRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(row: PortableStoredSongRow)

    /** A cache entry keeps the date it first became complete, so a later report for the same song never moves it. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun putIfAbsent(row: PortableStoredSongRow)

    /** Removes a download only when it came from [source] at [quality], so a download in another quality stays. */
    @Query(
        "DELETE FROM portable_stored_song WHERE origin = :origin AND provider = :provider " +
            "AND mediaId = :mediaId AND source = :source AND quality = :quality",
    )
    abstract suspend fun deleteDownload(
        origin: String,
        provider: String,
        mediaId: String,
        source: String,
        quality: String,
    ): Int

    /** Sets the byte count of a download row that has none, which is a row written before sizes were kept. */
    @Query(
        "UPDATE portable_stored_song SET bytes = :bytes WHERE origin = :origin AND provider = :provider " +
            "AND mediaId = :mediaId AND source = :source AND quality = :quality AND bytes IS NULL",
    )
    abstract suspend fun setDownloadBytes(
        origin: String,
        provider: String,
        mediaId: String,
        source: String,
        quality: String,
        bytes: Long,
    ): Int

    @Query("DELETE FROM portable_stored_song WHERE origin = :origin AND provider = :provider AND mediaId = :mediaId")
    abstract suspend fun delete(origin: String, provider: String, mediaId: String): Int
}

/**
 * The writes that record the audio this device stores. A download is listed before its bytes move into place. A
 * commit that does not finish restores the listing it displaced. No listed row names a file that is not there.
 * A cache entry is listed once the cache holds the whole song. The reads let the download repository check each
 * listed file against its committed size before publishing it.
 */
interface StoredSongIndex {
    /**
     * Lists a download of [song] while [placeFile] moves its bytes into place. The row is written first. When
     * [placeFile] fails or is cancelled, the row that was listed before is restored, so the earlier download stands.
     * The result is the earlier download of the same song when that one was another file. The caller removes that
     * file once [placeFile] has succeeded, since no row refers to it any more.
     */
    suspend fun commitDownload(
        song: ContentItem,
        source: AudioSourceType,
        quality: String,
        bytes: Long,
        completedAtMs: Long,
        placeFile: suspend () -> Unit,
    ): StoredDownload?

    /** Drops the download of [mediaId] from [provider] only when it came from [source] at [quality]. */
    suspend fun downloadRemoved(provider: String, mediaId: String, source: AudioSourceType, quality: String)

    /** The listed download of [mediaId] from [provider], [source] and [quality], or null when none is listed. */
    suspend fun listedDownload(
        provider: String,
        mediaId: String,
        source: AudioSourceType,
        quality: String,
    ): ListedDownload?

    /**
     * Every listed download, for the startup reconciliation. A row that names no source or quality names no file, so
     * it is dropped here rather than returned.
     */
    suspend fun listedDownloads(): List<ListedDownload>

    /** Records the byte count of a listed download that had none, once its earlier file has been adopted. */
    suspend fun adoptDownloadBytes(
        provider: String,
        mediaId: String,
        source: AudioSourceType,
        quality: String,
        bytes: Long,
    )

    /** Records a streamed song that the cache holds completely. A song already recorded keeps its first date. */
    suspend fun cached(song: ContentItem, source: AudioSourceType, firstCompleteAtMs: Long)

    /** Drops a streamed song that the cache has evicted. */
    suspend fun cacheRemoved(ref: ContentRef)
}

/**
 * The Room-backed [StoredSongIndex]. Each write is one statement. A failed commit restores what it displaced.
 */
class RoomStoredSongIndex(private val dao: PortableStoredSongDao) : StoredSongIndex {
    override suspend fun commitDownload(
        song: ContentItem,
        source: AudioSourceType,
        quality: String,
        bytes: Long,
        completedAtMs: Long,
        placeFile: suspend () -> Unit,
    ): StoredDownload? {
        require(quality.isNotBlank() && bytes > 0 && completedAtMs >= 0)
        val row = storedRow(StoredSongOrigin.DOWNLOAD, song, source, quality, bytes, completedAtMs)
        // Downloads are serialized by the repository, so no other write changes this row between read and write.
        val displaced = dao.find(StoredSongOrigin.DOWNLOAD, row.provider, row.mediaId)
        try {
            dao.put(row)
            placeFile()
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    restoreDownload(displaced, row.provider, row.mediaId)
                } catch (rollback: Throwable) {
                    failure.addSuppressed(rollback)
                }
            }
            throw failure
        }
        return displaced?.let(::toListed)
            ?.takeIf { it.source != source || it.quality != quality }
            ?.let { StoredDownload(it.source, it.quality) }
    }

    override suspend fun downloadRemoved(
        provider: String,
        mediaId: String,
        source: AudioSourceType,
        quality: String,
    ) {
        require(provider.isNotBlank() && mediaId.isNotBlank() && quality.isNotBlank())
        dao.deleteDownload(StoredSongOrigin.DOWNLOAD, provider, mediaId, source.name, quality)
    }

    override suspend fun listedDownload(
        provider: String,
        mediaId: String,
        source: AudioSourceType,
        quality: String,
    ): ListedDownload? {
        require(provider.isNotBlank() && mediaId.isNotBlank() && quality.isNotBlank())
        return dao.downloadAt(StoredSongOrigin.DOWNLOAD, provider, mediaId, source.name, quality)?.let(::toListed)
    }

    override suspend fun listedDownloads(): List<ListedDownload> =
        dao.rows(StoredSongOrigin.DOWNLOAD).mapNotNull { row ->
            toListed(row) ?: run {
                // Nothing can be checked for a row that names no file, so the row is dropped rather than listed.
                dao.delete(StoredSongOrigin.DOWNLOAD, row.provider, row.mediaId)
                null
            }
        }

    override suspend fun adoptDownloadBytes(
        provider: String,
        mediaId: String,
        source: AudioSourceType,
        quality: String,
        bytes: Long,
    ) {
        require(provider.isNotBlank() && mediaId.isNotBlank() && quality.isNotBlank() && bytes > 0)
        val updated = dao.setDownloadBytes(StoredSongOrigin.DOWNLOAD, provider, mediaId, source.name, quality, bytes)
        check(updated == 1) { "A download row awaiting adoption was not found" }
    }

    override suspend fun cached(song: ContentItem, source: AudioSourceType, firstCompleteAtMs: Long) {
        require(firstCompleteAtMs >= 0)
        dao.putIfAbsent(
            storedRow(StoredSongOrigin.CACHE, song, source, quality = null, bytes = null, atMs = firstCompleteAtMs),
        )
    }

    override suspend fun cacheRemoved(ref: ContentRef) {
        require(ref.kind == ContentKind.SONG)
        dao.delete(StoredSongOrigin.CACHE, ref.provider, ref.id)
    }

    /** Puts back the row that a commit displaced, or drops the staged row when no row was listed before it. */
    private suspend fun restoreDownload(displaced: PortableStoredSongRow?, provider: String, mediaId: String) {
        if (displaced == null) {
            dao.delete(StoredSongOrigin.DOWNLOAD, provider, mediaId)
        } else {
            dao.put(displaced)
        }
    }
}

/** The listed form of [row], or null when the row names no known source or no quality, so it names no file. */
private fun toListed(row: PortableStoredSongRow): ListedDownload? {
    val source = try {
        AudioSourceType.valueOf(row.source)
    } catch (unknown: IllegalArgumentException) {
        return null
    }
    val quality = row.quality?.takeIf(String::isNotBlank) ?: return null
    return ListedDownload(row.provider, row.mediaId, source, quality, row.bytes)
}

/**
 * The stored row of [song]. The artwork address is dropped, so no index row carries a URL that a provider could have
 * signed. The occurrence form is the one the library writes, so a stored song reads back as the same item.
 */
private fun storedRow(
    origin: String,
    song: ContentItem,
    source: AudioSourceType,
    quality: String?,
    bytes: Long?,
    atMs: Long,
): PortableStoredSongRow {
    require(song.ref.kind == ContentKind.SONG) { "Only songs are stored" }
    val occurrence = ContentOccurrence(song.ref.id, song.copy(artworkUri = null))
    return PortableStoredSongRow(
        origin,
        song.ref.provider,
        song.ref.id,
        source.name,
        quality,
        atMs,
        ContentJson.encode(occurrence).toString(),
        bytes,
    )
}

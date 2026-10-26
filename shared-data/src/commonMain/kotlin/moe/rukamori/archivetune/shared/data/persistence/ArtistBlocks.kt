package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.shared.domain.repository.ArtistBlockRepository
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/**
 * One blocked artist, keyed by the provider's artist id. [blockedAtMs] is when the block was first set, and
 * [artistName] keeps the name a block list can show.
 */
@Entity(tableName = "portable_artist_block", primaryKeys = ["provider", "artistId"])
data class PortableArtistBlockRow(
    val provider: String,
    val artistId: String,
    val artistName: String,
    val blockedAtMs: Long,
)

@Dao
abstract class ArtistBlockDao {
    @Query("SELECT * FROM portable_artist_block WHERE provider = :provider AND artistId = :artistId")
    abstract fun observe(provider: String, artistId: String): Flow<List<PortableArtistBlockRow>>

    @Query("SELECT * FROM portable_artist_block ORDER BY artistName, provider, artistId")
    abstract fun observeAll(): Flow<List<PortableArtistBlockRow>>

    /** Ignored when the artist is already blocked, so a repeated block keeps the time it was first set. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(row: PortableArtistBlockRow): Long

    @Query("DELETE FROM portable_artist_block WHERE provider = :provider AND artistId = :artistId")
    abstract suspend fun delete(provider: String, artistId: String): Int
}

/**
 * The portable block list. [nowMs] is the host clock, so a block time follows the device as the baseline's did. Every
 * ref must be an artist, because only an artist with a provider id can be blocked.
 */
class RoomArtistBlockRepository(
    private val dao: ArtistBlockDao,
    private val nowMs: () -> Long,
) : ArtistBlockRepository {
    override fun observeBlocked(artist: ContentRef): Flow<Boolean> {
        require(artist.kind == ContentKind.ARTIST)
        return dao.observe(artist.provider, artist.id).map { it.isNotEmpty() }.distinctUntilChanged()
    }

    override fun observeBlockedArtists(): Flow<Set<ContentRef>> =
        dao.observeAll().map { rows ->
            rows.mapTo(LinkedHashSet()) { ContentRef(it.provider, ContentKind.ARTIST, it.artistId) }
        }

    override suspend fun setBlocked(artist: ContentRef, name: String, blocked: Boolean) {
        require(artist.kind == ContentKind.ARTIST && name.isNotBlank())
        if (blocked) {
            dao.insertIfAbsent(PortableArtistBlockRow(artist.provider, artist.id, name, nowMs()))
        } else {
            dao.delete(artist.provider, artist.id)
        }
    }
}

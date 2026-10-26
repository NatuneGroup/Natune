package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import moe.rukamori.archivetune.shared.domain.playlist.PlaylistSongOrder
import moe.rukamori.archivetune.shared.domain.repository.*
import kotlin.random.Random

/**
 * One completed listen. [occurrenceJson] keeps the whole occurrence for the history page. The grouping columns let the
 * database aggregate without parsing JSON. [artistName] is the artist text the provider reported, which the older
 * readers still use. The artist and album identities live in [PortableListenEntityRow], because one listen can credit
 * several artists.
 */
@Entity(
    tableName = "portable_listen_event",
    indices = [Index("timestampMs"), Index("songKey"), Index("artistName"), Index("albumName")],
)
data class PortableListenEventRow(
    @PrimaryKey val id: String,
    val occurrenceJson: String,
    val songKey: String,
    val artistName: String,
    val albumName: String?,
    val timestampMs: Long,
    val playTimeMs: Long,
)

/**
 * One artist or album credited to a listen. [kind] is [KIND_ARTIST] or [KIND_ALBUM]. [position] orders the artists
 * of one listen. [entityId] is null when the provider did not identify the credit. Such a row still counts in the
 * statistics, under its name, but it is never navigable or blockable.
 */
@Entity(
    tableName = "portable_listen_entity",
    primaryKeys = ["eventId", "kind", "position"],
    indices = [Index(value = ["kind", "provider", "entityId"])],
)
data class PortableListenEntityRow(
    val eventId: String,
    val kind: String,
    val position: Int,
    val provider: String,
    val entityId: String?,
    val name: String,
)

/** The [PortableListenEntityRow.kind] of an artist credit. */
internal const val KIND_ARTIST = "ARTIST"

/** The [PortableListenEntityRow.kind] of an album. */
internal const val KIND_ALBUM = "ALBUM"

/**
 * The artist and album rows of one listen, from the occurrence it stores. The provider's credits are used when it gave
 * any. A listen without credits falls back to the artist text, as the older store did. Blank names are dropped, and a
 * credit repeated by identity counts once, so one listen never counts twice for the same artist.
 */
internal fun listenEntities(eventId: String, occurrence: ContentOccurrence): List<PortableListenEntityRow> {
    val item = occurrence.item
    val provider = item.ref.provider
    val credits = item.artists.ifEmpty {
        listOfNotNull(item.subtitle.takeIf(String::isNotBlank)?.let { ContentCredit(it) })
    }
    val artists = credits
        .distinctBy { credit -> credit.id?.let { "id:$it" } ?: "name:${credit.name}" }
        .mapIndexed { index, credit ->
            PortableListenEntityRow(eventId, KIND_ARTIST, index, provider, credit.id, credit.name)
        }
    val album = item.album?.takeIf(String::isNotBlank)?.let { name ->
        PortableListenEntityRow(eventId, KIND_ALBUM, 0, provider, item.albumId, name)
    }
    return artists + listOfNotNull(album)
}

/** The start and the play time of one listen, which the hour and weekday buckets read. */
data class ListenTime(val timestampMs: Long, val playTimeMs: Long)

/**
 * One group of song listens. [sampleJson] is the newest occurrence in the group. It supplies the title and artwork the
 * statistics show, and the provider the group was played from.
 */
data class ListeningGroupRow(val groupKey: String, val playCount: Long, val playTimeMs: Long, val sampleJson: String)

/**
 * One artist or album group. [entityId] is null for a group the provider did not identify, and [name] is the text the
 * group shows.
 */
data class ListenEntityGroupRow(
    val provider: String,
    val entityId: String?,
    val name: String,
    val playCount: Long,
    val playTimeMs: Long,
)

/** The all-time play time of one song, which PLAY_TIME playlist order reads. */
data class SongPlayTotal(val songKey: String, val totalMs: Long)

@Dao
abstract class ListeningEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(row: PortableListenEventRow)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertEntities(rows: List<PortableListenEntityRow>)

    /** A listen and its artist and album rows, committed together so no listen is stored without its identities. */
    @Transaction
    open suspend fun insertListen(row: PortableListenEventRow, entities: List<PortableListenEntityRow>) {
        insert(row)
        if (entities.isNotEmpty()) insertEntities(entities)
    }

    /** Writes the final play time of a listen that the threshold insert started. Returns the rows changed. */
    @Query("UPDATE portable_listen_event SET playTimeMs = :playTimeMs WHERE id = :id")
    abstract suspend fun updatePlayTime(id: String, playTimeMs: Long): Int

    @Query("SELECT * FROM portable_listen_event ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    abstract suspend fun newest(limit: Int): List<PortableListenEventRow>

    @Query(
        "SELECT * FROM portable_listen_event WHERE timestampMs < :beforeMs " +
            "OR (timestampMs = :beforeMs AND id < :beforeId) ORDER BY timestampMs DESC, id DESC LIMIT :limit",
    )
    abstract suspend fun before(beforeMs: Long, beforeId: String, limit: Int): List<PortableListenEventRow>

    @Query("DELETE FROM portable_listen_event WHERE id IN (:ids)")
    abstract suspend fun deleteIds(ids: List<String>)

    @Query("DELETE FROM portable_listen_event")
    abstract suspend fun deleteAll()

    @Query("DELETE FROM portable_listen_entity WHERE eventId IN (:eventIds)")
    abstract suspend fun deleteEntitiesOf(eventIds: List<String>)

    @Query("DELETE FROM portable_listen_entity")
    abstract suspend fun deleteAllEntities()

    /** Removes listens together with their identity rows, so no identity row outlives the listen it describes. */
    @Transaction
    open suspend fun removeListens(ids: List<String>) {
        deleteEntitiesOf(ids)
        deleteIds(ids)
    }

    /** Removes every listen and identity row in one transaction. */
    @Transaction
    open suspend fun clearListens() {
        deleteAllEntities()
        deleteAll()
    }

    @Query(
        "SELECT timestampMs, playTimeMs FROM portable_listen_event " +
            "WHERE timestampMs > :fromMs AND timestampMs <= :untilMs",
    )
    abstract suspend fun timesInWindow(fromMs: Long, untilMs: Long): List<ListenTime>

    @Query(SONG_GROUPS_IN_WINDOW)
    abstract suspend fun songGroups(fromMs: Long, untilMs: Long): List<ListeningGroupRow>

    @Query(ARTIST_GROUPS_IN_WINDOW)
    abstract suspend fun artistGroups(fromMs: Long, untilMs: Long): List<ListenEntityGroupRow>

    @Query(ALBUM_GROUPS_IN_WINDOW)
    abstract suspend fun albumGroups(fromMs: Long, untilMs: Long): List<ListenEntityGroupRow>

    @Query("SELECT songKey, SUM(playTimeMs) AS totalMs FROM portable_listen_event GROUP BY songKey")
    abstract fun observeSongPlayTotals(): Flow<List<SongPlayTotal>>
}

/*
 * The window queries keep the baseline's `(from, until]` bounds. Each sample subquery reads the newest occurrence of
 * its group over all time, so a group's title and provider stay current even when its newest listen is older than the
 * window. A listen with a blocked artist leaves the song, artist and album groups, as the baseline's filters did.
 */
private const val SONG_GROUPS_IN_WINDOW =
    "SELECT e.songKey AS groupKey, COUNT(*) AS playCount, SUM(e.playTimeMs) AS playTimeMs, " +
        "(SELECT l.occurrenceJson FROM portable_listen_event AS l WHERE l.songKey = e.songKey " +
        "ORDER BY l.timestampMs DESC, l.id DESC LIMIT 1) AS sampleJson " +
        "FROM portable_listen_event AS e WHERE e.timestampMs > :fromMs AND e.timestampMs <= :untilMs " +
        "AND NOT EXISTS (SELECT 1 FROM portable_listen_entity AS a INNER JOIN portable_artist_block AS b " +
        "ON b.provider = a.provider AND b.artistId = a.entityId WHERE a.eventId = e.id AND a.kind = 'ARTIST') " +
        "GROUP BY e.songKey ORDER BY playTimeMs DESC, groupKey ASC"

/*
 * Artists and albums group by provider identity. A row the provider did not identify groups by its name instead, so
 * it still counts without an id that a caller could navigate to or block.
 */
private const val ARTIST_GROUPS_IN_WINDOW =
    "SELECT k.provider AS provider, k.entityId AS entityId, MAX(k.name) AS name, COUNT(*) AS playCount, " +
        "SUM(e.playTimeMs) AS playTimeMs FROM portable_listen_entity AS k " +
        "INNER JOIN portable_listen_event AS e ON e.id = k.eventId " +
        "WHERE k.kind = 'ARTIST' AND e.timestampMs > :fromMs AND e.timestampMs <= :untilMs " +
        "AND NOT EXISTS (SELECT 1 FROM portable_artist_block AS b " +
        "WHERE b.provider = k.provider AND b.artistId = k.entityId) " +
        "GROUP BY k.provider, k.entityId, CASE WHEN k.entityId IS NULL THEN k.name ELSE '' END"

private const val ALBUM_GROUPS_IN_WINDOW =
    "SELECT k.provider AS provider, k.entityId AS entityId, MAX(k.name) AS name, COUNT(*) AS playCount, " +
        "SUM(e.playTimeMs) AS playTimeMs FROM portable_listen_entity AS k " +
        "INNER JOIN portable_listen_event AS e ON e.id = k.eventId " +
        "WHERE k.kind = 'ALBUM' AND e.timestampMs > :fromMs AND e.timestampMs <= :untilMs " +
        "AND NOT EXISTS (SELECT 1 FROM portable_listen_entity AS a INNER JOIN portable_artist_block AS b " +
        "ON b.provider = a.provider AND b.artistId = a.entityId WHERE a.eventId = e.id AND a.kind = 'ARTIST') " +
        "GROUP BY k.provider, k.entityId, CASE WHEN k.entityId IS NULL THEN k.name ELSE '' END"

/**
 * The local listening history and its statistics, kept in the portable state file. The host records a listen when the
 * play threshold is reached, as the baseline did, and writes its final play time when the session ends. A YouTube
 * history page needs an account-scoped browse source this port does not have yet, so it fails with
 * [HistoryOriginUnavailableException] rather than answering with an empty list.
 *
 * [offsetMsAt] gives the device zone's offset at an instant, so the hour and weekday buckets follow the user's clock
 * across daylight-saving changes. The host supplies it, because common code has no zone database.
 */
class RoomListeningHistory(
    private val dao: ListeningEventDao,
    private val offsetMsAt: (epochMs: Long) -> Long,
    private val newId: () -> String = ::randomListenEventId,
) : HistoryRepository, StatisticsRepository {
    /**
     * Records a listen at [timestampMs] with [playTimeMs] so far, and returns its id. The artist and album identities
     * come from the occurrence's credits. The caller passes the id to [finishListen] when the session ends.
     */
    suspend fun startListen(occurrence: ContentOccurrence, timestampMs: Long, playTimeMs: Long): String {
        require(playTimeMs >= 0)
        val id = newId()
        val item = occurrence.item
        dao.insertListen(
            PortableListenEventRow(
                id = id,
                occurrenceJson = ContentJson.encode(occurrence).toString(),
                songKey = PlaylistSongOrder.playKey(item.ref),
                artistName = item.subtitle,
                albumName = item.album,
                timestampMs = timestampMs,
                playTimeMs = playTimeMs,
            ),
            listenEntities(id, occurrence),
        )
        return id
    }

    /** Writes the final play time of a listen. Returns false when that listen was removed in the meantime. */
    suspend fun finishListen(eventId: String, playTimeMs: Long): Boolean {
        require(playTimeMs >= 0)
        return dao.updatePlayTime(eventId, playTimeMs) == 1
    }

    override suspend fun page(origin: HistoryOrigin, continuation: String?): ListeningHistoryPage {
        requireLocal(origin)
        val rows =
            if (continuation == null) {
                dao.newest(PAGE_SIZE + 1)
            } else {
                val beforeMs = continuation.substringBefore('|').toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid history continuation")
                val beforeId = continuation.substringAfter('|')
                require(beforeId.isNotEmpty()) { "Invalid history continuation" }
                dao.before(beforeMs, beforeId, PAGE_SIZE + 1)
            }
        val shown = rows.take(PAGE_SIZE)
        val next = if (rows.size > PAGE_SIZE) shown.last().let { "${it.timestampMs}|${it.id}" } else null
        val events = shown.map { ListeningEvent(it.id, decode(it.occurrenceJson), it.timestampMs, it.playTimeMs) }
        return ListeningHistoryPage(events, next)
    }

    override suspend fun remove(origin: HistoryOrigin, eventIds: Set<String>) {
        requireLocal(origin)
        eventIds.chunked(DELETE_BATCH).forEach { dao.removeListens(it) }
    }

    override suspend fun load(window: ListeningWindow): ListeningStatistics {
        val hourPlays = LongArray(HOURS)
        val hourTimeMs = LongArray(HOURS)
        val weekdayPlays = LongArray(WEEKDAYS)
        val weekdayTimeMs = LongArray(WEEKDAYS)
        var plays = 0L
        var playTimeMs = 0L
        for (listen in dao.timesInWindow(window.fromMs, window.untilMs)) {
            val local = listen.timestampMs + offsetMsAt(listen.timestampMs)
            val hour = local.floorDiv(HOUR_MS).mod(HOURS.toLong()).toInt()
            // 1970-01-01 was a Thursday, ISO day 4. Sunday maps to 0, as the baseline's DayOfWeek.value % 7 did.
            val isoDay = (local.floorDiv(DAY_MS) + 3L).mod(7L) + 1L
            val weekday = (isoDay % 7L).toInt()
            hourPlays[hour]++
            hourTimeMs[hour] += listen.playTimeMs
            weekdayPlays[weekday]++
            weekdayTimeMs[weekday] += listen.playTimeMs
            plays++
            playTimeMs += listen.playTimeMs
        }
        return ListeningStatistics(
            songs = dao.songGroups(window.fromMs, window.untilMs).ranked { decode(it.sampleJson).item },
            artists = dao.artistGroups(window.fromMs, window.untilMs).rankedEntities(ContentKind.ARTIST),
            albums = dao.albumGroups(window.fromMs, window.untilMs).rankedEntities(ContentKind.ALBUM),
            byHour = List(HOURS) { ListeningSlot(it, hourPlays[it], hourTimeMs[it]) },
            byWeekday = List(WEEKDAYS) { ListeningSlot(it, weekdayPlays[it], weekdayTimeMs[it]) },
            playCount = plays,
            playTimeMs = playTimeMs,
        )
    }

    override suspend fun reset() = dao.clearListens()

    /** Most plays first, then most time, then the group key, so equal groups keep a stable order. */
    private fun List<ListeningGroupRow>.ranked(item: (ListeningGroupRow) -> ContentItem): List<ListeningRank> =
        sortedWith(
            compareByDescending<ListeningGroupRow> { it.playCount }
                .thenByDescending { it.playTimeMs }
                .thenBy { it.groupKey },
        ).map { ListeningRank(item(it), it.playCount, it.playTimeMs) }

    /**
     * Artist and album groups, most plays first. An identified group links to its provider entity. An unidentified one
     * carries its name where the id would be and is marked not navigable, so no caller can take that name for an id.
     */
    private fun List<ListenEntityGroupRow>.rankedEntities(kind: ContentKind): List<ListeningRank> =
        sortedWith(
            compareByDescending<ListenEntityGroupRow> { it.playCount }
                .thenByDescending { it.playTimeMs }
                .thenBy { it.provider }
                .thenBy { it.entityId.orEmpty() }
                .thenBy { it.name },
        ).map { group ->
            val entityId = group.entityId
            ListeningRank(
                item = ContentItem(ContentRef(group.provider, kind, entityId ?: group.name), group.name),
                playCount = group.playCount,
                playTimeMs = group.playTimeMs,
                navigable = entityId != null,
            )
        }

    private fun requireLocal(origin: HistoryOrigin) {
        if (origin != HistoryOrigin.LOCAL) throw HistoryOriginUnavailableException(origin)
    }

    private fun decode(raw: String): ContentOccurrence = ContentJson.decode(Json.parseToJsonElement(raw).jsonObject)
}

/**
 * Thrown for the history of an origin that has no source in this port yet. It is an explicit failure, so a caller
 * cannot mistake a missing account source for an empty history.
 */
class HistoryOriginUnavailableException(val origin: HistoryOrigin) :
    IllegalStateException("${origin.name} listening history has no account-scoped source yet")

private const val PAGE_SIZE = 50
private const val DELETE_BATCH = 500
private const val HOURS = 24
private const val WEEKDAYS = 7
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L

/** A random key for one listen. History orders by time first, so the key only has to be unique. */
private fun randomListenEventId(): String =
    Random.nextBytes(12).joinToString(separator = "") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

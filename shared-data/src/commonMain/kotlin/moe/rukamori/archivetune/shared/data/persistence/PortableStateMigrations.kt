package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.rukamori.archivetune.shared.domain.playlisttags.PlaylistTagNames

/**
 * The hand-written step from portable state v1 to v2. It moves the JSON tag arrays on `portable_playlist` into the tag
 * catalog and the assignment table, and gives every playlist a creation sequence for the newest-first picker.
 *
 * The legacy `tags` column stays. Room validates the declared table shape, and rebuilding `portable_playlist` would have
 * to drop the parent table that `portable_playlist_entry` references. After this step nothing reads or writes it.
 * Values are bound rather than concatenated, because tag names are user text.
 */
internal object PortableStateMigrations {
    val V1_TO_V2: Migration = object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            connection.exec("ALTER TABLE `portable_playlist` ADD COLUMN `createdSeq` INTEGER")
            connection.exec("UPDATE `portable_playlist` SET `createdSeq` = `rowid`")
            connection.exec(
                "CREATE TABLE IF NOT EXISTS `portable_tag` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`color` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            connection.exec(
                "CREATE TABLE IF NOT EXISTS `portable_playlist_tag` (`playlistId` TEXT NOT NULL, " +
                    "`tagId` TEXT NOT NULL, PRIMARY KEY(`playlistId`, `tagId`), " +
                    "FOREIGN KEY(`playlistId`) REFERENCES `portable_playlist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(`tagId`) REFERENCES `portable_tag`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            connection.exec(
                "CREATE INDEX IF NOT EXISTS `index_portable_playlist_tag_tagId` ON `portable_playlist_tag` (`tagId`)",
            )
            moveLegacyTags(connection)
        }
    }

    /**
     * The step from v2 to v3. Each playlist gets its own song order, which defaults to the custom order as the baseline
     * did. Each entry gets the sequence CREATE_DATE sorts by. v2 never recorded when a song was added, so an existing
     * entry takes its custom position as its sequence, the closest record the file holds. Defaults match the entity
     * declarations so Room's shape check accepts the upgraded table.
     */
    val V2_TO_V3: Migration = object : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) {
            connection.exec("ALTER TABLE `portable_playlist` ADD COLUMN `songSortType` TEXT NOT NULL DEFAULT 'CUSTOM'")
            connection.exec("ALTER TABLE `portable_playlist` ADD COLUMN `songSortDescending` INTEGER NOT NULL DEFAULT 1")
            connection.exec("ALTER TABLE `portable_playlist_entry` ADD COLUMN `addedSeq` INTEGER NOT NULL DEFAULT 0")
            connection.exec("UPDATE `portable_playlist_entry` SET `addedSeq` = `position`")
        }
    }

    /**
     * The step from v3 to v4. It creates the listening event table and the indexes that its grouping reads. The table
     * is new, so no existing row changes. The statements match the declarations on [PortableListenEventRow], because
     * Room's shape check compares them. Every column is declared NOT NULL except the album, which may be absent.
     */
    val V3_TO_V4: Migration = object : Migration(3, 4) {
        override fun migrate(connection: SQLiteConnection) {
            connection.exec(
                "CREATE TABLE IF NOT EXISTS `portable_listen_event` (`id` TEXT NOT NULL, " +
                    "`occurrenceJson` TEXT NOT NULL, `songKey` TEXT NOT NULL, `artistName` TEXT NOT NULL, " +
                    "`albumName` TEXT, `timestampMs` INTEGER NOT NULL, `playTimeMs` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            for (column in listOf("timestampMs", "songKey", "artistName", "albumName")) {
                connection.exec(
                    "CREATE INDEX IF NOT EXISTS `index_portable_listen_event_$column` " +
                        "ON `portable_listen_event` (`$column`)",
                )
            }
        }
    }

    /**
     * The step from v4 to v5. It creates the blocked-artist table and the table of artist and album identities, and it
     * gives each library song the add and like sequences that its CREATE_DATE order reads. Then every stored listen
     * gets the identities its occurrence carries. A listen stored before this step has no credits, so it takes the
     * artist text it was stored with, exactly as a new listen without credits does. Sequences start at each row's
     * rowid, the closest record of the order the file holds.
     */
    val V4_TO_V5: Migration = object : Migration(4, 5) {
        override fun migrate(connection: SQLiteConnection) {
            connection.exec(
                "CREATE TABLE IF NOT EXISTS `portable_artist_block` (`provider` TEXT NOT NULL, " +
                    "`artistId` TEXT NOT NULL, `artistName` TEXT NOT NULL, `blockedAtMs` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`provider`, `artistId`))",
            )
            connection.exec(
                "CREATE TABLE IF NOT EXISTS `portable_listen_entity` (`eventId` TEXT NOT NULL, " +
                    "`kind` TEXT NOT NULL, `position` INTEGER NOT NULL, `provider` TEXT NOT NULL, " +
                    "`entityId` TEXT, `name` TEXT NOT NULL, PRIMARY KEY(`eventId`, `kind`, `position`))",
            )
            connection.exec(
                "CREATE INDEX IF NOT EXISTS `index_portable_listen_entity_kind_provider_entityId` " +
                    "ON `portable_listen_entity` (`kind`, `provider`, `entityId`)",
            )
            connection.exec("ALTER TABLE `library_item` ADD COLUMN `addedSeq` INTEGER NOT NULL DEFAULT 0")
            connection.exec("ALTER TABLE `library_item` ADD COLUMN `likedSeq` INTEGER NOT NULL DEFAULT 0")
            connection.exec("UPDATE `library_item` SET `addedSeq` = `rowid`")
            connection.exec("UPDATE `library_item` SET `likedSeq` = `rowid` WHERE `liked` = 1")
            backfillListenEntities(connection)
        }
    }

    /**
     * The step from v5 to v6. It creates the index of stored songs, which holds the user downloads and the cache
     * entries. The table is new, so no existing row changes. The statement matches the declaration on
     * [PortableStoredSongRow], because Room's shape check compares them. Only the quality may be absent.
     */
    val V5_TO_V6: Migration = object : Migration(5, 6) {
        override fun migrate(connection: SQLiteConnection) {
            connection.exec(
                "CREATE TABLE IF NOT EXISTS `portable_stored_song` (`origin` TEXT NOT NULL, " +
                    "`provider` TEXT NOT NULL, `mediaId` TEXT NOT NULL, `source` TEXT NOT NULL, `quality` TEXT, " +
                    "`storedAtMs` INTEGER NOT NULL, `json` TEXT NOT NULL, " +
                    "PRIMARY KEY(`origin`, `provider`, `mediaId`))",
            )
        }
    }

    /**
     * The additive step from portable state v6 to v7. Each download now records the byte count it committed, which
     * the startup reconciliation checks against its file. The column is added without a value, so a download written
     * before this step is not proven whole and is dropped at reconciliation. The album tables are new, so no existing
     * row changes. Each statement matches its declaration on [PortableStoredSongRow] or on the album rows, because
     * Room's shape check compares them.
     */
    val V6_TO_V7: Migration = object : Migration(6, 7) {
        override fun migrate(connection: SQLiteConnection) {
            connection.exec("ALTER TABLE `portable_stored_song` ADD COLUMN `bytes` INTEGER")
            connection.exec(
                "CREATE TABLE IF NOT EXISTS `portable_album_complete` (`albumProvider` TEXT NOT NULL, " +
                    "`albumId` TEXT NOT NULL, `trackCount` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`albumProvider`, `albumId`))",
            )
            connection.exec(
                "CREATE TABLE IF NOT EXISTS `portable_album_track` (`albumProvider` TEXT NOT NULL, " +
                    "`albumId` TEXT NOT NULL, `position` INTEGER NOT NULL, `provider` TEXT NOT NULL, " +
                    "`mediaId` TEXT NOT NULL, PRIMARY KEY(`albumProvider`, `albumId`, `position`))",
            )
        }
    }

    /**
     * The step from v7 to v8. It creates the persisted download queue and the local-media scan table, the search
     * history and video-id lookup tables, and the hidden flag on playlists; every column matches
     * [PortableDownloadTaskRow], [PortableLocalTrackRow], [PortableSearchHistoryRow] or [PortablePlaylistRow] so the
     * shape check passes and the owners resume interrupted rows or repeat scans on first open.
     */
    val V7_TO_V8: Migration = object : Migration(7, 8) {
        override fun migrate(connection: SQLiteConnection) {
            connection.exec(
                "CREATE TABLE IF NOT EXISTS `portable_download_task` (`id` TEXT NOT NULL, " +
                    "`provider` TEXT NOT NULL, `kind` TEXT NOT NULL, `mediaId` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `source` TEXT, `quality` TEXT NOT NULL, `phase` TEXT NOT NULL, " +
                    "`bytesDownloaded` INTEGER NOT NULL, `totalBytes` INTEGER, `resumeBytes` INTEGER NOT NULL, " +
                    "`failReason` TEXT, `updateTimeMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            connection.exec(
                "CREATE TABLE IF NOT EXISTS `portable_local_track` (`id` TEXT NOT NULL, " +
                    "`path` TEXT NOT NULL, `modifiedEpochMs` INTEGER NOT NULL, `sizeBytes` INTEGER NOT NULL, " +
                    "`title` TEXT NOT NULL, `artistsJson` TEXT NOT NULL, `albumTitle` TEXT, " +
                    "`albumArtistName` TEXT, `albumYear` INTEGER, `durationMs` INTEGER, `year` INTEGER, " +
                    "`trackNumber` INTEGER, `artworkUri` TEXT, `lyricsText` TEXT, PRIMARY KEY(`id`))",
            )
            // The entity declares no index and Room compares indices, so none is created here. Scans read every
            // row through tracks(), so no lookup depends on one.
            connection.exec(
                "CREATE TABLE IF NOT EXISTS `portable_search_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT " +
                    "NOT NULL, `query` TEXT NOT NULL)",
            )
            connection.exec(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_portable_search_history_query` " +
                    "ON `portable_search_history` (`query`)",
            )
            connection.exec(
                "CREATE TABLE IF NOT EXISTS `portable_set_video_id` (`videoId` TEXT NOT NULL, " +
                    "`setVideoId` TEXT, PRIMARY KEY(`videoId`))",
            )
            // Hidden playlists keep their flag across an Android import; no portable surface reads it yet.
            connection.exec("ALTER TABLE `portable_playlist` ADD COLUMN `isHidden` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Writes the artist and album rows of every stored listen. Each occurrence is decoded the way the history reads it,
     * and [listenEntities] derives the rows, so an upgraded listen counts exactly as a new one does. A malformed
     * occurrence throws and aborts the upgrade, which keeps the old file intact, as the tag step does.
     */
    private fun backfillListenEntities(connection: SQLiteConnection) {
        val rows = ArrayList<PortableListenEntityRow>()
        val read = connection.prepare("SELECT `id`, `occurrenceJson` FROM `portable_listen_event`")
        try {
            while (read.step()) {
                val occurrence = ContentJson.decode(Json.parseToJsonElement(read.getText(1)).jsonObject)
                rows += listenEntities(read.getText(0), occurrence)
            }
        } finally {
            read.close()
        }
        val insert = connection.prepare(
            "INSERT INTO `portable_listen_entity` (`eventId`, `kind`, `position`, `provider`, `entityId`, `name`) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
        )
        try {
            for (row in rows) {
                insert.reset()
                insert.bindText(1, row.eventId)
                insert.bindText(2, row.kind)
                insert.bindLong(3, row.position.toLong())
                insert.bindText(4, row.provider)
                val entityId = row.entityId
                if (entityId == null) insert.bindNull(5) else insert.bindText(5, entityId)
                insert.bindText(6, row.name)
                insert.step()
            }
        } finally {
            insert.close()
        }
    }

    private fun SQLiteConnection.exec(sql: String) {
        val statement = prepare(sql)
        try {
            statement.step()
        } finally {
            statement.close()
        }
    }

    /**
     * Reads every playlist's JSON array, then writes one catalog row per distinct name and one assignment per pair.
     * A malformed array throws, which aborts the upgrade and keeps the old file intact, as the old reader would have.
     */
    private fun moveLegacyTags(connection: SQLiteConnection) {
        val playlists = ArrayList<Pair<String, Set<String>>>()
        val read = connection.prepare("SELECT `id`, `tags` FROM `portable_playlist`")
        try {
            while (read.step()) {
                val names = Json.parseToJsonElement(read.getText(1)).jsonArray
                    .mapNotNull { PlaylistTagNames.normalize(it.jsonPrimitive.content) }
                    .toSet()
                playlists += read.getText(0) to names
            }
        } finally {
            read.close()
        }
        val tagIdByName = HashMap<String, String>()
        val insertTag = connection.prepare("INSERT INTO `portable_tag` (`id`, `name`, `color`) VALUES (?, ?, ?)")
        try {
            for (name in playlists.flatMap { it.second }.distinct().sorted()) {
                // Legacy catalog ids cannot collide with the random ids the repository issues later.
                val id = "legacy:$name"
                insertTag.reset()
                insertTag.bindText(1, id)
                insertTag.bindText(2, name)
                insertTag.bindText(3, PORTABLE_TAG_DEFAULT_COLOR)
                insertTag.step()
                tagIdByName[name] = id
            }
        } finally {
            insertTag.close()
        }
        val assign = connection.prepare(
            "INSERT OR IGNORE INTO `portable_playlist_tag` (`playlistId`, `tagId`) VALUES (?, ?)",
        )
        try {
            for ((playlistId, names) in playlists) {
                for (name in names) {
                    assign.reset()
                    assign.bindText(1, playlistId)
                    assign.bindText(2, requireNotNull(tagIdByName[name]))
                    assign.step()
                }
            }
        } finally {
            assign.close()
        }
        connection.exec("UPDATE `portable_playlist` SET `tags` = '[]'")
    }
}

package moe.rukamori.archivetune.shared.data.persistence

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.rukamori.archivetune.shared.domain.playback.PodcastProgress
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.songmetadata.SongMetadataOverride

/**
 * Rebuilds a database file from a committed schema export, seeds it, and opens it through Room so the
 * retained migrations run for real. The export's createSql, index and room_master_table statements are exactly what
 * a build of that version produced, so the file is indistinguishable from one a user carried over.
 */
class PortableStateMigrationTest {
    @Test fun versionSevenFileMigratesToLatestKeepingRows() = runBlocking<Unit> {
        val file = Files.createTempFile("archivetune-v7", ".db")
        rebuildFromExport(7, file, listOf(
            "INSERT INTO portable_playlist (id, title, tags, createdSeq, songSortType, songSortDescending) " +
                "VALUES ('keep-me', 'Seeded', '[]', 1, 'CUSTOM', 1)",
        ))

        val database = openPortableStateDatabase(file.toString())
        try {
            database.openNow()
        } finally {
            database.close()
        }

        val migrated = BundledSQLiteDriver().open(file.toString())
        try {
            migrated.prepare("PRAGMA user_version").use {
                assertTrue(it.step())
                assertEquals(PORTABLE_STATE_SCHEMA_VERSION.toLong(), it.getLong(0))
            }
            for (table in listOf(
                "portable_search_history", "portable_set_video_id", "portable_podcast_progress",
                "portable_song_metadata_override", "portable_song_lyrics_offset",
            )) {
                migrated.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use {
                    it.bindText(1, table)
                    assertTrue(it.step(), "$table was not created by the migration")
                }
            }
            migrated.prepare("PRAGMA table_info(`portable_playlist`)").use {
                var hidden = false
                while (it.step()) if (it.getText(1) == "isHidden") hidden = true
                assertTrue(hidden, "portable_playlist did not gain isHidden")
            }
            migrated.prepare("SELECT id, isHidden FROM portable_playlist").use {
                assertTrue(it.step())
                assertEquals("keep-me", it.getText(0))
                assertEquals(0L, it.getLong(1))
                assertFalse(it.step())
            }
        } finally {
            migrated.close()
            Files.deleteIfExists(file)
        }
    }

    @Test fun versionEightMigrationKeepsDuplicatePlaylistRowsAndProgressSurvivesReopeningAndBackup() = runBlocking<Unit> {
        val file = Files.createTempFile("archivetune-v8", ".db")
        val backup = Files.createTempDirectory("archivetune-progress-snapshot").resolve("portable-state.db")
        val connection = BundledSQLiteDriver().open(file.toString())
        try {
            val spec = schema(8)
            spec.getValue("entities").jsonArray.forEach { entity ->
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                connection.exec(entity.jsonObject.getValue("createSql").jsonPrimitive.content, table)
                entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                    connection.exec(index.jsonObject.getValue("createSql").jsonPrimitive.content, table)
                }
            }
            spec.getValue("setupQueries").jsonArray.forEach { query ->
                connection.prepare(query.jsonPrimitive.content).use { it.step() }
            }
            connection.prepare("PRAGMA user_version = 8").use { it.step() }
            connection.prepare(
                "INSERT INTO portable_playlist (id, title, tags, createdSeq, songSortType, songSortDescending, isHidden) " +
                    "VALUES ('keep-me', 'Seeded', '[]', 1, 'CUSTOM', 1, 0)",
            ).use { it.step() }
            for (position in 0..1) connection.prepare(
                "INSERT INTO portable_playlist_entry (playlistId, occurrenceId, position, json, addedSeq) " +
                    "VALUES ('keep-me', 'occ-$position', $position, '{}', $position)",
            ).use { it.step() }
        } finally { connection.close() }

        val expected = moe.rukamori.archivetune.shared.domain.playback.PodcastProgress(
            "youtube:episode", 12_000L, null, false, 1L,
        )
        val database = openPortableStateDatabase(file.toString())
        try {
            database.openNow()
            val store = RoomPodcastProgressStore(database.podcastProgress())
            store.write(expected)
            assertEquals(listOf(expected), store.observeAll().first())
            assertEquals(2, database.library().entries("keep-me", 10, 0).size)
            database.snapshotTo(backup)
        } finally { database.close() }

        for (path in listOf(file, backup)) {
            val reopened = openPortableStateDatabase(path.toString())
            try {
                assertEquals(expected, RoomPodcastProgressStore(reopened.podcastProgress()).read("youtube:episode"))
                assertEquals(2, reopened.library().entries("keep-me", 10, 0).size)
            } finally { reopened.close() }
        }
        Files.deleteIfExists(file)
        backup.parent.toFile().deleteRecursively()
    }

    @Test fun versionEightFileMigratesToLatestGainingSongMetadataOverrides() = runBlocking<Unit> {
        val file = Files.createTempFile("archivetune-v8", ".db")
        rebuildFromExport(8, file, listOf(
            "INSERT INTO portable_playlist (id, title, tags, createdSeq, songSortType, songSortDescending) " +
                "VALUES ('keep-me', 'Seeded', '[]', 1, 'CUSTOM', 1)",
        ))

        val database = openPortableStateDatabase(file.toString())
        try {
            database.openNow()
        } finally {
            database.close()
        }

        val migrated = BundledSQLiteDriver().open(file.toString())
        try {
            migrated.prepare("PRAGMA user_version").use {
                assertTrue(it.step())
                assertEquals(PORTABLE_STATE_SCHEMA_VERSION.toLong(), it.getLong(0))
            }
            migrated.prepare("PRAGMA table_info(`portable_song_metadata_override`)").use {
                val columns = mutableListOf<String>()
                val primaryKey = mutableListOf<String>()
                while (it.step()) {
                    columns += "${it.getText(1)}:${it.getText(2)}:notnull=${it.getLong(3)}"
                    if (it.getLong(5) > 0) primaryKey += it.getText(1)
                }
                assertEquals(
                    listOf(
                        "provider:TEXT:notnull=1",
                        "songId:TEXT:notnull=1",
                        "title:TEXT:notnull=0",
                        "artistNamesJson:TEXT:notnull=0",
                        "updatedAtMs:INTEGER:notnull=1",
                    ),
                    columns,
                    "portable_song_metadata_override was not created by the migration",
                )
                assertEquals(listOf("provider", "songId"), primaryKey)
            }
            migrated.prepare("SELECT id FROM portable_playlist").use {
                assertTrue(it.step())
                assertEquals("keep-me", it.getText(0))
                assertFalse(it.step())
            }
            migrated.prepare(
                "INSERT INTO portable_song_metadata_override (provider, songId, title, artistNamesJson, updatedAtMs) " +
                    "VALUES ('youtube', 'song', 'Edited', '[\"Edited artist\"]', 7)",
            ).use { it.step() }
            migrated.prepare(
                "SELECT title, artistNamesJson, updatedAtMs FROM portable_song_metadata_override " +
                    "WHERE provider = 'youtube' AND songId = 'song'",
            ).use {
                assertTrue(it.step())
                assertEquals("Edited", it.getText(0))
                assertEquals("[\"Edited artist\"]", it.getText(1))
                assertEquals(7L, it.getLong(2))
                assertFalse(it.step())
            }
        } finally {
            migrated.close()
            Files.deleteIfExists(file)
        }
    }

    @Test fun versionNineMigrationKeepsPodcastProgressAndRepeatedPlaylistSongs() = runBlocking<Unit> {
        val file = Files.createTempFile("archivetune-v9", ".db")
        val progress = PodcastProgress("youtube:episode", 12_000L, null, false, 11L)
        val song = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, "song")
        val edit = SongMetadataOverride("Edited title", listOf("Edited artist"), 13L)
        try {
            rebuildFromExport(9, file, listOf(
                "INSERT INTO portable_playlist (id, title, tags, createdSeq, songSortType, songSortDescending) " +
                    "VALUES ('keep-me', 'Seeded', '[]', 1, 'CUSTOM', 1)",
                "INSERT INTO portable_podcast_progress (episodeId, positionMs, durationMs, finished, updatedAtMs) " +
                    "VALUES ('youtube:episode', 12000, NULL, 0, 11)",
            ) + (0..1).map { position ->
                val json = ContentJson.encode(
                    ContentOccurrence("occ-$position", ContentItem(song, "Provider title")),
                ).toString()
                "INSERT INTO portable_playlist_entry (playlistId, occurrenceId, position, json, addedSeq) " +
                    "VALUES ('keep-me', 'occ-$position', $position, '$json', $position)"
            })

            val database = openPortableStateDatabase(file.toString())
            try {
                database.openNow()
                assertEquals(progress, RoomPodcastProgressStore(database.podcastProgress()).read(progress.episodeId))
                val entries = database.library().entries("keep-me", 10, 0).map {
                    ContentJson.decode(Json.parseToJsonElement(it.json).jsonObject)
                }
                assertEquals(listOf("occ-0", "occ-1"), entries.map { it.occurrenceId })
                assertEquals(listOf(song, song), entries.map { it.item.ref })
                val metadata = RoomSongMetadataRepository(database.songMetadataOverrides())
                assertTrue(metadata.overrides().isEmpty())
                metadata.setOverride(song, edit)
                assertEquals(mapOf(song to edit), metadata.overrides())
            } finally {
                database.close()
            }

            val reopened = openPortableStateDatabase(file.toString())
            try {
                assertEquals(progress, RoomPodcastProgressStore(reopened.podcastProgress()).read(progress.episodeId))
                assertEquals(2, reopened.library().entries("keep-me", 10, 0).size)
                assertEquals(
                    mapOf(song to edit),
                    RoomSongMetadataRepository(reopened.songMetadataOverrides()).overrides(),
                )
            } finally {
                reopened.close()
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test fun versionElevenFileMigratesToLatestGainingPlaylistRemoteColumns() = runBlocking<Unit> {
        val file = Files.createTempFile("archivetune-v11", ".db")
        rebuildFromExport(11, file, listOf(
            "INSERT INTO portable_playlist (id, title, tags, createdSeq, songSortType, songSortDescending, isHidden) " +
                "VALUES ('keep-me', 'Seeded', '[]', 1, 'CUSTOM', 1, 0)",
        ))

        val database = openPortableStateDatabase(file.toString())
        try {
            database.openNow()
        } finally {
            database.close()
        }

        val migrated = BundledSQLiteDriver().open(file.toString())
        try {
            migrated.prepare("PRAGMA user_version").use {
                assertTrue(it.step())
                assertEquals(PORTABLE_STATE_SCHEMA_VERSION.toLong(), it.getLong(0))
            }
            migrated.prepare("PRAGMA table_info(`portable_playlist`)").use {
                val columns = mutableListOf<String>()
                while (it.step()) columns += it.getText(1)
                for (column in listOf("customOrder", "browseId", "remoteSongCount")) {
                    assertTrue(column in columns, "portable_playlist did not gain $column")
                }
            }
            migrated.prepare(
                "SELECT id, coalesce(customOrder, -1), coalesce(browseId, 'missing'), " +
                    "coalesce(remoteSongCount, -1) FROM portable_playlist",
            ).use {
                assertTrue(it.step())
                assertEquals("keep-me", it.getText(0))
                assertEquals(-1L, it.getLong(1))
                assertEquals("missing", it.getText(2))
                assertEquals(-1L, it.getLong(3))
                assertFalse(it.step())
            }
        } finally {
            migrated.close()
            Files.deleteIfExists(file)
        }
    }

    @Test fun currentSchemaExportIdentityMatchesARoomCreatedDatabase() = runBlocking<Unit> {
        val file = Files.createTempFile("archivetune-schema-export", ".db")
        val database = openPortableStateDatabase(file.toString())
        try { database.openNow() } finally { database.close() }
        val connection = BundledSQLiteDriver().open(file.toString())
        try {
            connection.prepare("SELECT identity_hash FROM room_master_table WHERE id = 42").use {
                assertTrue(it.step())
                assertEquals(schema(PORTABLE_STATE_SCHEMA_VERSION).getValue("identityHash").jsonPrimitive.content, it.getText(0))
            }
        } finally { connection.close(); Files.deleteIfExists(file) }
    }

    private fun rebuildFromExport(version: Int, file: Path, seed: List<String>) {
        val spec = schema(version)
        val connection = BundledSQLiteDriver().open(file.toString())
        try {
            for (entity in spec.getValue("entities").jsonArray) {
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                connection.exec(entity.jsonObject.getValue("createSql").jsonPrimitive.content, table)
                entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                    connection.exec(index.jsonObject.getValue("createSql").jsonPrimitive.content, table)
                }
            }
            spec.getValue("setupQueries").jsonArray.forEach { query ->
                connection.prepare(query.jsonPrimitive.content).use { it.step() }
            }
            seed.forEach { statement -> connection.prepare(statement).use { it.step() } }
            connection.prepare("PRAGMA user_version = $version").use { it.step() }
        } finally {
            connection.close()
        }
    }

    private fun schema(version: Int): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream(
            "/room-schemas/moe.rukamori.archivetune.shared.data.persistence.PortableStateDatabase/$version.json",
        )!!.use { it.readBytes().decodeToString() },
    ).jsonObject.getValue("database").jsonObject

    private fun androidx.sqlite.SQLiteConnection.exec(sql: String, table: String) {
        prepare(sql.replace("\${TABLE_NAME}", table)).use { it.step() }
    }
}

package moe.rukamori.archivetune.shared.data.persistence

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Rebuilds a version-7 database file from the committed schema export, seeds it, and opens it through Room so the
 * retained migrations run for real. The export's createSql, index and room_master_table statements are exactly what
 * a version-7 build produced, so the file is indistinguishable from one a user carried over.
 */
class PortableStateMigrationTest {
    @Test fun versionSevenFileMigratesToCurrentKeepingRows() = runBlocking<Unit> {
        val spec = schema(7)
        val file = Files.createTempFile("archivetune-v7", ".db")
        val connection = BundledSQLiteDriver().open(file.toString())
        try {
            for (entity in spec.jsonObject.getValue("entities").jsonArray) {
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                connection.exec(entity.jsonObject.getValue("createSql").jsonPrimitive.content, table)
                entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                    connection.exec(index.jsonObject.getValue("createSql").jsonPrimitive.content, table)
                }
            }
            spec.jsonObject.getValue("setupQueries").jsonArray.forEach { query ->
                connection.prepare(query.jsonPrimitive.content).use { it.step() }
            }
            connection.prepare("PRAGMA user_version = 7").use { it.step() }
            connection.prepare(
                "INSERT INTO portable_playlist (id, title, tags, createdSeq, songSortType, songSortDescending) " +
                    "VALUES ('keep-me', 'Seeded', '[]', 1, 'CUSTOM', 1)",
            ).use { it.step() }
        } finally {
            connection.close()
        }

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
            for (table in listOf("portable_search_history", "portable_set_video_id", "portable_podcast_progress")) {
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

    private fun schema(version: Int): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream(
            "/room-schemas/moe.rukamori.archivetune.shared.data.persistence.PortableStateDatabase/$version.json",
        )!!.use { it.readBytes().decodeToString() },
    ).jsonObject.getValue("database").jsonObject

    private fun androidx.sqlite.SQLiteConnection.exec(sql: String, table: String) {
        prepare(sql.replace("\${TABLE_NAME}", table)).use { it.step() }
    }
}

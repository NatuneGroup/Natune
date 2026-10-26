package moe.rukamori.archivetune.shared.data.persistence

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Rebuilds a version-7 database file from the committed schema export, seeds it, and opens it through Room so the
 * retained migrations run for real. The export's createSql, index and room_master_table statements are exactly what
 * a version-7 build produced, so the file is indistinguishable from one a user carried over.
 */
class PortableStateMigrationTest {
    @Test fun versionSevenFileMigratesToVersionEightKeepingRows() = runBlocking<Unit> {
        val spec = Json.parseToJsonElement(
            javaClass.getResourceAsStream(
                "/room-schemas/moe.rukamori.archivetune.shared.data.persistence.PortableStateDatabase/7.json",
            )!!.use { it.readBytes().decodeToString() },
        ).jsonObject.getValue("database")
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
            for (table in listOf("portable_search_history", "portable_set_video_id")) {
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

    private fun androidx.sqlite.SQLiteConnection.exec(sql: String, table: String) {
        prepare(sql.replace("\${TABLE_NAME}", table)).use { it.step() }
    }
}

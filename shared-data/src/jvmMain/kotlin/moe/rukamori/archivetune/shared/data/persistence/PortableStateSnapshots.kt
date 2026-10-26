package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path

/** The schema version [PortableStateDatabase] declares. Snapshots newer than this cannot be opened by this build. */
const val PORTABLE_STATE_SCHEMA_VERSION: Int = 7

/**
 * Writes a consistent copy of the open database to [target], which must not exist yet. VACUUM INTO reads one snapshot
 * of the writer connection, so the copy includes the WAL and never a half-written transaction.
 */
suspend fun PortableStateDatabase.snapshotTo(target: Path) {
    require(target.isAbsolute && !Files.exists(target)) { "Snapshot target must be a new absolute path" }
    useWriterConnection { connection ->
        connection.usePrepared("VACUUM INTO ?") { statement ->
            statement.bindText(1, target.toString())
            statement.step()
        }
    }
}

/**
 * Opens the database now instead of on its first query, so a restored file whose schema Room refuses fails here, while
 * the host can still put the previous file back.
 */
suspend fun PortableStateDatabase.openNow() {
    useWriterConnection { connection ->
        connection.usePrepared("SELECT count(*) FROM sqlite_master") { statement -> statement.step() }
    }
}

/**
 * Checks a portable state snapshot without opening it through Room and returns its schema version. Throws
 * [IllegalArgumentException] for anything that is not an intact portable state file this build can migrate.
 */
fun inspectPortableStateFile(file: Path): Int {
    val header = Files.newInputStream(file).use { it.readNBytes(16) }
    require(header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) { "Not an SQLite file" }
    val connection = BundledSQLiteDriver().open(file.toString())
    try {
        val version = connection.prepare("PRAGMA user_version").use { statement ->
            require(statement.step()) { "No schema version" }
            statement.getLong(0).toInt()
        }
        require(version in 1..PORTABLE_STATE_SCHEMA_VERSION) { "Unsupported portable state schema" }
        connection.prepare("PRAGMA integrity_check").use { statement ->
            require(statement.step() && statement.getText(0) == "ok" && !statement.step()) { "Corrupt SQLite file" }
        }
        connection.prepare("SELECT identity_hash FROM room_master_table WHERE id = 42").use { statement ->
            require(statement.step() && statement.getText(0).isNotBlank()) { "Not a Room database" }
        }
        REQUIRED_TABLES.forEach { table ->
            connection.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
                statement.bindText(1, table)
                require(statement.step()) { "Portable state table missing" }
            }
        }
        return version
    } finally {
        connection.close()
    }
}

/** Tables every portable state schema has had since version 1. */
private val REQUIRED_TABLES = listOf("queue_checkpoint", "library_item")

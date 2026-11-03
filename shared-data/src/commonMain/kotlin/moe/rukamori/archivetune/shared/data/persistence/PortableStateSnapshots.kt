package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection

/** The schema version [PortableStateDatabase] declares. Snapshots newer than this cannot be opened by this build. */
const val PORTABLE_STATE_SCHEMA_VERSION: Int = 12

/** The 16-byte header every SQLite database file starts with. */
internal val SQLITE_FILE_HEADER: ByteArray = "SQLite format 3\u0000".encodeToByteArray()

/**
 * Writes a consistent copy of the open database to [targetPath], which must not exist yet. VACUUM INTO reads one
 * snapshot of the writer connection, so the copy includes the WAL and never a half-written transaction.
 */
suspend fun PortableStateDatabase.snapshotTo(targetPath: String) {
    require(targetPath.isNotBlank()) { "Snapshot target must be an absolute path" }
    useWriterConnection { connection ->
        connection.usePrepared("VACUUM INTO ?") { statement ->
            statement.bindText(1, targetPath)
            statement.step()
        }
    }
}

/**
 * Opens the database now instead of on its first query, so a restored file whose schema Room refuses fails here,
 * while the host can still put the previous file back.
 */
suspend fun PortableStateDatabase.openNow() {
    useWriterConnection { connection ->
        connection.usePrepared("SELECT count(*) FROM sqlite_master") { statement -> statement.step() }
    }
}

/**
 * The pragma and table checks behind every host's portable-state inspection, run on an already open connection:
 * a supported schema version, a clean integrity check, the Room master table and the tables every schema version
 * has had. Throws [IllegalArgumentException] for anything that is not an intact portable state file this build can
 * migrate.
 */
internal fun inspectPortableStateConnection(connection: SQLiteConnection): Int {
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
    PORTABLE_STATE_REQUIRED_TABLES.forEach { table ->
        connection.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
            statement.bindText(1, table)
            require(statement.step()) { "Portable state table missing" }
        }
    }
    return version
}

/** Tables every portable state schema has had since version 1. */
private val PORTABLE_STATE_REQUIRED_TABLES = listOf("queue_checkpoint", "library_item")

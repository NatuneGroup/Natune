package moe.rukamori.archivetune.shared.data.persistence

import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a consistent copy of the open database to [target], which must not exist yet. VACUUM INTO reads one snapshot
 * of the writer connection, so the copy includes the WAL and never a half-written transaction.
 */
suspend fun PortableStateDatabase.snapshotTo(target: Path) {
    require(target.isAbsolute && !Files.exists(target)) { "Snapshot target must be a new absolute path" }
    snapshotTo(target.toString())
}

/**
 * Checks a portable state snapshot without opening it through Room and returns its schema version. Throws
 * [IllegalArgumentException] for anything that is not an intact portable state file this build can migrate.
 */
fun inspectPortableStateFile(file: Path): Int {
    val header = Files.newInputStream(file).use { it.readNBytes(16) }
    require(header.contentEquals(SQLITE_FILE_HEADER)) { "Not an SQLite file" }
    val connection = portableStateDriver().open(file.toString())
    try {
        return inspectPortableStateConnection(connection)
    } finally {
        connection.close()
    }
}

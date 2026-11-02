@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.persistence

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

/**
 * Checks a portable state snapshot without opening it through Room and returns its schema version. Throws
 * [IllegalArgumentException] for anything that is not an intact portable state file this build can migrate.
 */
fun inspectPortableStateFile(path: String): Int {
    val header = ByteArray(16)
    val file = fopen(path, "rb") ?: throw IllegalArgumentException("Not an SQLite file")
    try {
        header.usePinned { pinned ->
            require(fread(pinned.addressOf(0), 1u, header.size.toULong(), file) == header.size.toULong()) {
                "Not an SQLite file"
            }
        }
    } finally {
        fclose(file)
    }
    require(header.contentEquals(SQLITE_FILE_HEADER)) { "Not an SQLite file" }
    val connection = portableStateDriver().open(path)
    try {
        return inspectPortableStateConnection(connection)
    } finally {
        connection.close()
    }
}

package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.Room
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

actual fun portableStateDriver(): SQLiteDriver = BundledSQLiteDriver()

/** Opens the portable state file at [absolutePath]. The Android schema-36 `song.db` is never opened here. */
fun openPortableStateDatabase(absolutePath: String): PortableStateDatabase {
    require(absolutePath.startsWith("/")) { "Database path must be absolute" }
    require(!absolutePath.substringAfterLast('/').equals("song.db", ignoreCase = true)) {
        "Android song.db is owned by its schema-36 host"
    }
    return Room.databaseBuilder<PortableStateDatabase>(name = absolutePath).buildPortableStateDatabase()
}

/**
 * The portable state file of this app in Application Support, which the system keeps across updates and includes in the
 * device backup, as the library is the user's own data.
 */
@OptIn(ExperimentalForeignApi::class)
fun iosPortableStateDatabasePath(): String {
    val manager = NSFileManager.defaultManager
    val support = (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL).path
        ?: error("Application Support is unavailable")
    val root = "$support/library"
    manager.createDirectoryAtPath(root, true, null, null)
    return "$root/portable-state.db"
}

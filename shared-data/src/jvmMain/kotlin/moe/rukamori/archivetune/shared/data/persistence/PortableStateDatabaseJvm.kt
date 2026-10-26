package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.Room
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Path

actual fun portableStateDriver(): SQLiteDriver = BundledSQLiteDriver()

fun openPortableStateDatabase(absolutePath: String): PortableStateDatabase {
    require(absolutePath == ":memory:" || Path.of(absolutePath).isAbsolute) { "Database path must be absolute" }
    require(!Path.of(absolutePath).fileName.toString().equals("song.db", ignoreCase = true)) { "Android song.db is owned by its schema-36 host" }
    return if (absolutePath == ":memory:")
        Room.inMemoryDatabaseBuilder<PortableStateDatabase>().buildPortableStateDatabase()
    else Room.databaseBuilder<PortableStateDatabase>(name = absolutePath).buildPortableStateDatabase()
}

package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.Room
import java.nio.file.Path

fun openPortableStateDatabase(absolutePath: String): PortableStateDatabase {
    require(absolutePath == ":memory:" || Path.of(absolutePath).isAbsolute) { "Database path must be absolute" }
    require(!Path.of(absolutePath).fileName.toString().equals("song.db", ignoreCase = true)) { "Android song.db is owned by its schema-36 host" }
    return if (absolutePath == ":memory:")
        Room.inMemoryDatabaseBuilder<PortableStateDatabase>().buildPortableStateDatabase()
    else Room.databaseBuilder<PortableStateDatabase>(name = absolutePath).buildPortableStateDatabase()
}

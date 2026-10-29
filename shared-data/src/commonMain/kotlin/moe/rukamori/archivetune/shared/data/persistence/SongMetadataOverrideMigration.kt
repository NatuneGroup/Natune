package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection

/** Additive and self-contained. Step 10, after the v9 podcast progress table. */
internal object SongMetadataOverrideMigration : Migration(9, 10) {
    override fun migrate(connection: SQLiteConnection) {
        connection.prepare(
            "CREATE TABLE IF NOT EXISTS `portable_song_metadata_override` (`provider` TEXT NOT NULL, " +
                "`songId` TEXT NOT NULL, `title` TEXT, `artistNamesJson` TEXT, `updatedAtMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`provider`, `songId`))",
        ).use { it.step() }
    }
}

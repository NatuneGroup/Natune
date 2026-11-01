package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection

/** Additive and self-contained. Step 11, after the v10 song-metadata override table. */
internal object SongLyricsOffsetMigration : Migration(10, 11) {
    override fun migrate(connection: SQLiteConnection) {
        connection.prepare(
            "CREATE TABLE IF NOT EXISTS `portable_song_lyrics_offset` (`provider` TEXT NOT NULL, " +
                "`songId` TEXT NOT NULL, `offsetMs` INTEGER NOT NULL, PRIMARY KEY(`provider`, `songId`))",
        ).use { it.step() }
    }
}

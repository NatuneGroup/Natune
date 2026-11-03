package moe.rukamori.archivetune.shared.data.persistence

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection

/**
 * Additive and self-contained. Step 12: the collection's custom order and the linked YouTube record on each playlist.
 * All three columns stay nullable, so nothing existing is rewritten and a playlist never pushed keeps them absent.
 */
internal object PlaylistRemoteMigration : Migration(11, 12) {
    override fun migrate(connection: SQLiteConnection) {
        for (statement in listOf(
            "ALTER TABLE `portable_playlist` ADD COLUMN `customOrder` INTEGER",
            "ALTER TABLE `portable_playlist` ADD COLUMN `browseId` TEXT",
            "ALTER TABLE `portable_playlist` ADD COLUMN `remoteSongCount` INTEGER",
        )) {
            connection.prepare(statement).use { it.step() }
        }
    }
}

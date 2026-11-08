package moe.rukamori.archivetune.shared.data.backup

import androidx.sqlite.SQLiteConnection
import moe.rukamori.archivetune.shared.data.persistence.PortableAndroidLibraryImport

fun validateAndroidBackupDatabase(connection: SQLiteConnection) {
                val version = connection.prepare("PRAGMA user_version").use { statement ->
                    require(statement.step()) { "Android library has no schema version" }
                    statement.getLong(0)
                }
                // Public Room identities from the retained Android schema exports, not credentials. The additive
                // migrations (38 and newer) have no hand-invented identity, so their exact shape is checked below.
                val expectedIdentity = when (version) {
                    36L -> "7f6fc454b3f0d77468bc4ae9d5a71c3f"
                    37L -> "ea9228eb769d7cdde2b6a9a6a4532182"
                    in 38L..PortableAndroidLibraryImport.ANDROID_SCHEMA_VERSION.toLong() -> null
                    else -> throw IllegalArgumentException("Unsupported Android library schema")
                }
                connection.prepare("PRAGMA integrity_check").use { statement ->
                    require(statement.step() && statement.getText(0) == "ok" && !statement.step()) { "Corrupt SQLite snapshot" }
                }
                connection.prepare("SELECT identity_hash FROM room_master_table WHERE id = 42").use { statement ->
                    require(statement.step() && statement.getText(0).isNotBlank()) {
                        "SQLite snapshot does not match the retained Android schema"
                    }
                    if (expectedIdentity != null) require(statement.getText(0) == expectedIdentity) {
                        "SQLite snapshot does not match the retained Android schema"
                    }
                }
                if (expectedIdentity == null) {
                    // The additive metadata migration has no hand-invented Room identity hash.
                    // Validate its exact column/primary-key shape; the importer also reads the
                    // canonical source columns before it can commit a portable file.
                    val columns = connection.prepare("PRAGMA table_info(`song_metadata_override`)").use { statement ->
                        buildList {
                            while (statement.step()) add(
                                listOf(statement.getText(1), statement.getText(2), statement.getLong(3).toString(), statement.getLong(5).toString()),
                            )
                        }
                    }
                    require(columns == listOf(
                        listOf("songId", "TEXT", "1", "1"),
                        listOf("title", "TEXT", "0", "0"),
                        listOf("artistNames", "TEXT", "0", "0"),
                        listOf("updatedAt", "INTEGER", "1", "0"),
                    )) { "Unexpected song metadata override schema" }
                    if (version >= 39L) {
                        // Schema 39 adds `song.lyricsOffset`; its exact column shape is checked the same
                        // way rather than trusting an identity hash a Room build never generated here.
                        val lyricsOffset = connection.prepare("PRAGMA table_info(`song`)").use { statement ->
                            var column: List<String>? = null
                            while (statement.step()) {
                                if (statement.getText(1) == "lyricsOffset") {
                                    column = listOf(
                                        statement.getText(2),
                                        statement.getLong(3).toString(),
                                        if (statement.isNull(4)) "" else statement.getText(4),
                                    )
                                }
                            }
                            column
                        }
                        require(lyricsOffset == listOf("INTEGER", "1", "0")) {
                            "Unexpected song lyrics offset schema"
                        }
                    }
                }
}

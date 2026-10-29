package moe.rukamori.archivetune.shared.data.persistence

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.*
import moe.rukamori.archivetune.db.SongMetadataOverrideSql

/** Executes the exact SQL used by the Android DAO/migration, not a reimplemented search predicate. */
class SongMetadataOverrideSqlTest {
    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step(); Unit }
    private fun database(): SQLiteConnection = BundledSQLiteDriver().open(":memory:").also {
        it.exec("CREATE TABLE song (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, inLibrary INTEGER, titleOverride INTEGER NOT NULL DEFAULT 0)")
        it.exec("CREATE TABLE artist (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL)")
        it.exec("CREATE TABLE song_artist_map (songId TEXT NOT NULL, artistId TEXT NOT NULL)")
        it.exec("CREATE TABLE song_metadata_override (songId TEXT PRIMARY KEY NOT NULL, title TEXT, artistNames TEXT, updatedAt INTEGER NOT NULL)")
        it.exec("INSERT INTO song VALUES ('edited', 'Provider title', 1, 0), ('normal', 'Normal title', 1, 0), ('unsaved', 'Edited title', NULL, 0)")
        it.exec("INSERT INTO artist VALUES ('artist', 'Provider artist')")
        it.exec("INSERT INTO song_artist_map VALUES ('edited', 'artist'), ('normal', 'artist')")
    }
    private fun SQLiteConnection.search(query: String): List<String> =
        prepare(SongMetadataOverrideSql.SEARCH_SONGS).use {
            it.bindText(1, query)
            it.bindLong(2, 100)
            buildList { while (it.step()) add(it.getText(0)) }
        }
    private fun SQLiteConnection.count(query: String): Long =
        prepare(SongMetadataOverrideSql.SEARCH_SONGS_COUNT).use {
            it.bindText(1, query); assertTrue(it.step()); it.getLong(0)
        }

    @Test fun editedTitleAndArtistQueriesAgreeWithCountsAndExcludeUnsavedRows() {
        val db = database()
        try {
            db.exec("INSERT INTO song_metadata_override VALUES ('edited', 'Edited title', 'Edited artist', 5)")
            assertEquals(listOf("edited"), db.search("EDITED TITLE"))
            assertEquals(listOf("edited"), db.search("edited artist"))
            assertEquals(listOf("normal"), db.search("Provider artist"))
            assertTrue(db.search("Provider title").isEmpty())
            for (query in listOf("", "edited", "Provider artist", "not found")) {
                assertEquals(db.search(query).size.toLong(), db.count(query))
            }
        } finally { db.close() }
    }

    @Test fun uneditedFieldsFollowRefreshAndResetExposesLatestProviderData() {
        val db = database()
        try {
            db.exec("INSERT INTO song_metadata_override VALUES ('edited', 'Edited title', NULL, 5)")
            db.exec("UPDATE song SET title = 'New title' WHERE id = 'edited'")
            db.exec("UPDATE artist SET name = 'New artist' WHERE id = 'artist'")
            assertEquals(listOf("edited"), db.search("Edited title"))
            assertEquals(setOf("edited", "normal"), db.search("New artist").toSet())
            db.exec("DELETE FROM song_metadata_override WHERE songId = 'edited'")
            assertEquals(listOf("edited"), db.search("New title"))
            assertTrue(db.search("Edited title").isEmpty())
        } finally { db.close() }
    }

    @Test fun legacyTitleMoveIsIdempotentAndCannotOverwriteANewerEdit() {
        val db = database()
        try {
            db.exec("UPDATE song SET titleOverride = 1")
            db.exec("INSERT INTO song_metadata_override VALUES ('edited', 'Newer edit', 'Newer artist', 99)")
            repeat(2) {
                db.exec(SongMetadataOverrideSql.MOVE_LEGACY_TITLES)
                db.exec(SongMetadataOverrideSql.CLEAR_LEGACY_TITLE_FLAG)
            }
            db.prepare("SELECT title, artistNames, updatedAt FROM song_metadata_override WHERE songId = 'edited'").use {
                assertTrue(it.step()); assertEquals("Newer edit", it.getText(0)); assertEquals("Newer artist", it.getText(1)); assertEquals(99L, it.getLong(2))
            }
            db.prepare("SELECT title FROM song WHERE id = 'edited'").use {
                assertTrue(it.step()); assertEquals("Provider title", it.getText(0))
            }
            db.prepare("SELECT COUNT(*) FROM song WHERE titleOverride != 0").use {
                assertTrue(it.step()); assertEquals(0L, it.getLong(0))
            }
            db.prepare("SELECT title, artistNames FROM song_metadata_override WHERE songId = 'normal'").use {
                assertTrue(it.step()); assertEquals("Normal title", it.getText(0)); assertTrue(it.isNull(1))
            }
        } finally { db.close() }
    }
}

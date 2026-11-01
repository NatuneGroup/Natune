package moe.rukamori.archivetune.shared.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.data.persistence.AndroidSongDbExport
import moe.rukamori.archivetune.shared.data.persistence.BackupCategory
import moe.rukamori.archivetune.shared.data.persistence.LegacySongDbImport
import moe.rukamori.archivetune.shared.data.persistence.SequenceTimes
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot

/**
 * The Android export is checked against the importer: the rich schema-36 fixture is imported, exported as schema 39
 * and imported again, and every mapped table must come back with the same rows.
 */
class AndroidSongDbExportTest {
    private val now = 1_800_000_000_000L

    private fun fixture(): ByteArray =
        javaClass.getResourceAsStream("/fixtures/schema36-rich-song.db")!!.use { it.readBytes() }

    private fun withPortable(block: suspend (root: Path, portable: Path, imported: LegacySongDbImport.Outcome) -> Unit) =
        runBlocking {
            val root = Files.createTempDirectory("archivetune-android-export")
            try {
                val source = root.resolve("source-song.db").also { Files.write(it, fixture()) }
                val portable = root.resolve("portable-state.db")
                val imported = LegacySongDbImport.convert(source, portable)
                block(root, portable, imported)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    private fun SQLiteConnection.exec(sql: String) {
        prepare(sql).use { it.step() }
    }

    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use {
        assertTrue(it.step())
        it.getLong(0)
    }

    @Test fun exportedLibraryImportsBackWithEveryMappedRow() = withPortable { root, portable, imported ->
        val songDb = root.resolve("song.db")
        val exported = AndroidSongDbExport.export(portable, songDb, now)
        assertEquals(0, exported.skippedItems)
        assertEquals(imported.librarySongs, exported.librarySongs)
        assertEquals(imported.playlistEntries, exported.playlistEntries)

        val again = LegacySongDbImport.convert(songDb, root.resolve("round-trip.db"))
        assertEquals(imported.librarySongs, again.librarySongs)
        assertEquals(imported.likedAlbums, again.likedAlbums)
        assertEquals(imported.bookmarkedArtists, again.bookmarkedArtists)
        assertEquals(imported.blockedArtists, again.blockedArtists)
        assertEquals(imported.playlists, again.playlists)
        assertEquals(imported.playlistEntries, again.playlistEntries)
        assertEquals(imported.listenEvents, again.listenEvents)
        assertEquals(imported.tags, again.tags)
        assertEquals(imported.tagAssignments, again.tagAssignments)
        assertEquals(imported.searches, again.searches)
        assertEquals(imported.setVideoIds, again.setVideoIds)
        assertEquals(0, again.droppedEntries)
    }

    @Test fun exportCarriesTheSchemaThirtyNineIdentityAndRows() = withPortable { root, portable, _ ->
        val songDb = root.resolve("song.db")
        AndroidSongDbExport.export(portable, songDb, now)
        val connection = BundledSQLiteDriver().open(songDb.toString())
        try {
            assertEquals(39L, connection.long("PRAGMA user_version"))
            connection.prepare("SELECT identity_hash FROM room_master_table WHERE id = 42").use {
                assertTrue(it.step())
                // Public identity of app/schemas/...InternalDatabase/39.json.
                assertEquals("5d24a191640ead2be1499cd924f7155d", it.getText(0))
            }
            assertEquals(21L + 1L, connection.long(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            ))
            assertEquals(3L, connection.long("SELECT COUNT(*) FROM sqlite_master WHERE type = 'view'"))
            connection.prepare("SELECT liked FROM song WHERE id = 'fixture-song'").use {
                assertTrue(it.step())
                assertEquals(1L, it.getLong(0))
            }
            connection.prepare("SELECT setVideoId FROM playlist_song_map ORDER BY position").use {
                assertTrue(it.step()); assertEquals("set-a", it.getText(0))
                assertTrue(it.step()); assertEquals("set-b", it.getText(0))
                assertFalse(it.step())
            }
            connection.prepare("SELECT timestamp, playTime FROM event ORDER BY timestamp").use {
                assertTrue(it.step())
                assertEquals(1700000000000L, it.getLong(0))
                assertEquals(5000L, it.getLong(1))
            }
            assertEquals(2000L, connection.long("SELECT blockedAt FROM artist WHERE id = 'uc-blocked'"))
            assertEquals(35000L, connection.long("SELECT totalPlayTime FROM song WHERE id = 'fixture-song'"))
        } finally {
            connection.close()
        }
    }

    @Test fun archiveFromTheExportPassesTheAndroidCodec() = withPortable { root, portable, _ ->
        val songDb = root.resolve("song.db")
        AndroidSongDbExport.export(portable, songDb, now)
        val codec = JvmBackupArchiveCodec()
        val archive = codec.create(PreferenceSnapshot.EMPTY, setOf(BackupCategory.LIBRARY), mapOf("song.db" to Files.readAllBytes(songDb)))
        assertTrue(AndroidBackupArchive.recognizes(archive))
        val scratch = Files.createDirectory(root.resolve("scratch"))
        assertTrue(AndroidBackupArchive.convert(archive, scratch).database != null)
    }

    @Test fun otherProvidersAreSkippedAndCountedSequencesBecomeOrderedTimes() = withPortable { root, portable, _ ->
        val connection = BundledSQLiteDriver().open(portable.toString())
        try {
            val json = connection.prepare("SELECT json FROM library_item WHERE kind = 'SONG' AND mediaId = 'fixture-song'").use {
                assertTrue(it.step())
                it.getText(0)
            }
            val counted = json.replace("fixture-song", "counted-song").replace("'", "''")
            connection.exec(
                "INSERT INTO library_item (provider, kind, mediaId, title, json, liked, addedSeq, likedSeq) " +
                    "VALUES ('youtube', 'SONG', 'counted-song', 'Counted', '$counted', 0, 7, 0)",
            )
            connection.exec(
                "INSERT INTO library_item (provider, kind, mediaId, title, json, liked, addedSeq, likedSeq) " +
                    "VALUES ('spotify', 'SONG', 'other-song', 'Other', '{}', 1, 8, 8)",
            )
        } finally {
            connection.close()
        }
        val songDb = root.resolve("song.db")
        val exported = AndroidSongDbExport.export(portable, songDb, now)
        assertEquals(1, exported.skippedItems)
        val result = BundledSQLiteDriver().open(songDb.toString())
        try {
            // The counters of the fixture's album (1500) and artist (1000) order behind the song's 7.
            assertEquals(now - 3000L, result.long("SELECT inLibrary FROM song WHERE id = 'counted-song'"))
            assertEquals(0L, result.long("SELECT COUNT(*) FROM song WHERE id = 'other-song'"))
        } finally {
            result.close()
        }
    }

    @Test fun sequenceTimesKeepEpochValuesAndOrderCounters() {
        val times = SequenceTimes(listOf(3L, 1L, 9L, 1_700_000_000_000L, Long.MAX_VALUE), now)
        assertEquals(now - 3000L, times.at(1L))
        assertEquals(now - 2000L, times.at(3L))
        assertEquals(now - 1000L, times.at(9L))
        assertEquals(1_700_000_000_000L, times.at(1_700_000_000_000L))
        assertNull(times.at(Long.MAX_VALUE))
        assertNull(times.at(0L))
    }
}

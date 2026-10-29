package moe.rukamori.archivetune.shared.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.data.persistence.LegacySongDbImport
import moe.rukamori.archivetune.shared.data.persistence.PORTABLE_STATE_SCHEMA_VERSION
import moe.rukamori.archivetune.shared.data.persistence.inspectPortableStateFile

/**
 * The schema-36 rich fixture carries one of every row kind the importer maps: a liked song, a bookmarked and a
 * blocked artist, a bookmarked album, a playlist holding the same song twice, two listen events, a tag assignment,
 * two search-history entries and a setVideoId mapping.
 */
class LegacySongDbImportTest {
    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes() }

    private fun convert(bytes: ByteArray): Pair<Path, LegacySongDbImport.Outcome> = runBlocking {
        val songDb = Files.createTempFile("archivetune-songdb", ".db")
        Files.write(songDb, bytes)
        val target = Files.createTempDirectory("archivetune-import").resolve("portable-state.db")
        val outcome = LegacySongDbImport.convert(songDb, target)
        Files.deleteIfExists(songDb)
        target to outcome
    }

    @Test fun richFixtureProducesEveryMappedTable() {
        val (target, outcome) = fixture("schema36-rich-song.db").let(::convert)
        try {
            assertEquals(1, outcome.librarySongs)
            assertEquals(1, outcome.likedAlbums)
            assertEquals(1, outcome.bookmarkedArtists)
            assertEquals(1, outcome.blockedArtists)
            assertEquals(1, outcome.playlists)
            assertEquals(2, outcome.playlistEntries)
            assertEquals(2, outcome.listenEvents)
            assertEquals(1, outcome.tags)
            assertEquals(1, outcome.tagAssignments)
            assertEquals(2, outcome.searches)
            assertEquals(1, outcome.setVideoIds)
            assertEquals(0, outcome.droppedEntries)
            assertEquals(PORTABLE_STATE_SCHEMA_VERSION, inspectPortableStateFile(target))

            val connection = BundledSQLiteDriver().open(target.toString())
            try {
                // The liked song, the bookmarked album and the bookmarked artist each keep one library row.
                connection.prepare("SELECT kind, mediaId, title, liked FROM library_item ORDER BY kind").use {
                    val kinds = ArrayList<String>()
                    while (it.step()) {
                        kinds += it.getText(0)
                        assertEquals(1L, it.getLong(3))
                    }
                    assertEquals(listOf("ALBUM", "ARTIST", "SONG"), kinds)
                }
                connection.prepare(
                    "SELECT artistId, blockedAtMs FROM portable_artist_block",
                ).use {
                    assertTrue(it.step())
                    assertEquals("uc-blocked", it.getText(0))
                    assertEquals(2000L, it.getLong(1))
                    assertFalse(it.step())
                }
                // Duplicate playlist rows survive in order, each with its own occurrence id and setVideoId.
                connection.prepare(
                    "SELECT occurrenceId, position, json FROM portable_playlist_entry ORDER BY position",
                ).use {
                    val occurrences = ArrayList<String>()
                    val setVideoIds = ArrayList<String>()
                    while (it.step()) {
                        occurrences += it.getText(0)
                        val json = it.getText(2)
                        assertTrue(json.contains("fixture-song"))
                        setVideoIds += Regex("\"setVideoId\":\"([^\"]+)\"").find(json)!!.groupValues[1]
                    }
                    assertEquals(2, occurrences.size)
                    assertEquals(occurrences.toSet().size, 2)
                    assertEquals(listOf("set-a", "set-b"), setVideoIds)
                }
                // The baseline's events land as portable listens with the credit rows a live listen writes.
                connection.prepare(
                    "SELECT id, timestampMs, playTimeMs FROM portable_listen_event ORDER BY timestampMs",
                ).use {
                    assertTrue(it.step())
                    assertEquals(1700000000000L, it.getLong(1))
                    assertEquals(5000L, it.getLong(2))
                    assertTrue(it.step())
                    assertEquals(30000L, it.getLong(2))
                    assertFalse(it.step())
                }
                connection.prepare("SELECT COUNT(*) FROM portable_listen_entity").use {
                    assertTrue(it.step())
                    assertEquals(4L, it.getLong(0))
                }
                connection.prepare("SELECT playlistId, tagId FROM portable_playlist_tag").use {
                    assertTrue(it.step())
                    assertEquals("fixture-playlist", it.getText(0))
                    assertEquals("tag-1", it.getText(1))
                }
                // Search history keeps the baseline's oldest-to-newest order.
                connection.prepare("SELECT `query` FROM portable_search_history ORDER BY id").use {
                    assertTrue(it.step()); assertEquals("alpha", it.getText(0))
                    assertTrue(it.step()); assertEquals("beta", it.getText(0))
                    assertFalse(it.step())
                }
                connection.prepare("SELECT videoId, setVideoId FROM portable_set_video_id").use {
                    assertTrue(it.step())
                    assertEquals("fixture-song", it.getText(0))
                    assertEquals("set-a", it.getText(1))
                    assertFalse(it.step())
                }
            } finally {
                connection.close()
            }
        } finally {
            target.parent.toFile().deleteRecursively()
        }
    }

    @Test fun archiveZipRunsCodecValidationThenImports() = runBlocking<Unit> {
        val settings = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<ArchiveTuneBackup><Settings>" +
            "<string name=\"playerDesignStyle\" value=\"FLAMINGO\"/>" +
            "</Settings></ArchiveTuneBackup>").toByteArray()
        val archive = zip(
            "song.db" to fixture("schema36-rich-song.db"),
            "settings.xml" to settings,
        )
        assertTrue(AndroidBackupArchive.recognizes(archive))
        val scratch = Files.createTempDirectory("archivetune-android-import")
        try {
            val converted = AndroidBackupArchive.convert(archive, scratch)
            assertFalse(converted.isEmpty)
            assertEquals(PORTABLE_STATE_SCHEMA_VERSION, inspectPortableStateFile(converted.database!!))
            val key = converted.settings.keys.single()
            assertEquals("playerDesignStyle", key.name)
            assertEquals("FLAMINGO", converted.settings[key])
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    @Test fun schemaThirtySevenArchivePreservesEpisodeBookmarksAndPlayedMarkersOutsideTheLibrary() = runBlocking<Unit> {
        val source = Files.createTempFile("archivetune-schema37", ".db")
        val scratch = Files.createTempDirectory("archivetune-progress-import")
        try {
            Files.write(source, fixture("schema36-rich-song.db"))
            val connection = BundledSQLiteDriver().open(source.toString())
            try {
                connection.prepare(
                    "CREATE TABLE podcast_progress (episodeId TEXT NOT NULL, positionMs INTEGER NOT NULL, " +
                        "durationMs INTEGER, finished INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL, PRIMARY KEY(episodeId))",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO podcast_progress VALUES ('youtube:unfinished', 12000, 600000, 0, 1000), " +
                        "('youtube:played', 0, NULL, 1, 2000)",
                ).use { it.step() }
                connection.prepare("PRAGMA user_version = 37").use { it.step() }
                // Public identity of app/schemas/...InternalDatabase/37.json.
                connection.prepare(
                    "UPDATE room_master_table SET identity_hash = 'ea9228eb769d7cdde2b6a9a6a4532182' WHERE id = 42",
                ).use { it.step() }
            } finally { connection.close() }
            val bytes = Files.readAllBytes(source)
            val archive = zip("song.db" to bytes)
            val codec = JvmBackupArchiveCodec()
            val prepared = codec.prepare(archive, setOf(moe.rukamori.archivetune.shared.data.persistence.BackupCategory.LIBRARY))
            assertContentEquals(bytes, prepared.libraryFiles.getValue("song.db"))
            val converted = AndroidBackupArchive.convert(archive, scratch)
            val restored = BundledSQLiteDriver().open(converted.database!!.toString())
            try {
                restored.prepare(
                    "SELECT episodeId, positionMs, durationMs, finished, updatedAtMs FROM portable_podcast_progress ORDER BY episodeId",
                ).use {
                    assertTrue(it.step())
                    assertEquals("youtube:played", it.getText(0))
                    assertEquals(0L, it.getLong(1))
                    assertTrue(it.isNull(2))
                    assertEquals(1L, it.getLong(3))
                    assertEquals(2000L, it.getLong(4))
                    assertTrue(it.step())
                    assertEquals("youtube:unfinished", it.getText(0))
                    assertEquals(12000L, it.getLong(1))
                    assertEquals(600000L, it.getLong(2))
                    assertEquals(0L, it.getLong(3))
                    assertFalse(it.step())
                }
                restored.prepare("SELECT COUNT(*) FROM portable_playlist_entry").use {
                    assertTrue(it.step())
                    assertEquals(2L, it.getLong(0))
                }
            } finally { restored.close() }
        } finally {
            Files.deleteIfExists(source)
            scratch.toFile().deleteRecursively()
        }
    }

    @Test fun foreignAndDesktopArchivesAreNotRecognized() = runBlocking<Unit> {
        assertFalse(AndroidBackupArchive.recognizes(zip("random.txt" to byteArrayOf(1, 2, 3))))
        assertFalse(AndroidBackupArchive.recognizes(zip("backup.json" to "{}".toByteArray())))
        assertFalse(AndroidBackupArchive.recognizes(byteArrayOf(1, 2, 3, 4)))
        val empty = Files.createTempDirectory("archivetune-android-empty")
        try {
            assertFailsWith<IllegalArgumentException> {
                AndroidBackupArchive.convert(zip("random.txt" to byteArrayOf(9)), empty)
            }
        } finally {
            empty.toFile().deleteRecursively()
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
}

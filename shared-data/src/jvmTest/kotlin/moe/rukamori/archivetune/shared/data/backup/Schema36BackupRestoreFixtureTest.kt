/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlin.test.*
import moe.rukamori.archivetune.shared.data.persistence.BackupCategory
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot

/** Synthetic rows in the historical exported schema, NOT a real user's DB or Java queue fixture. */
class Schema36BackupRestoreFixtureTest {
    @Test fun validatesAndReturnsOriginalSchema36BytesWithDuplicatePlaylistRowsIntact() {
        val fixture = javaClass.getResourceAsStream("/fixtures/schema36-synthetic-song.db")!!.use { it.readBytes() }
        val codec = JvmBackupArchiveCodec()
        val archive = codec.create(PreferenceSnapshot.EMPTY, setOf(BackupCategory.LIBRARY), mapOf("song.db" to fixture))
        val prepared = codec.prepare(archive, setOf(BackupCategory.LIBRARY))
        assertContentEquals(fixture, prepared.libraryFiles.getValue("song.db"))
        val path = Files.createTempFile("archivetune-schema36-test", ".db")
        try {
            Files.write(path, prepared.libraryFiles.getValue("song.db"))
            val connection = BundledSQLiteDriver().open(path.toString())
            try {
                connection.prepare("SELECT songId, position, setVideoId FROM playlist_song_map ORDER BY position").use {
                    assertTrue(it.step()); assertEquals("fixture-song", it.getText(0)); assertEquals(0L, it.getLong(1)); assertEquals("set-a", it.getText(2))
                    assertTrue(it.step()); assertEquals("fixture-song", it.getText(0)); assertEquals(1L, it.getLong(1)); assertEquals("set-b", it.getText(2))
                    assertFalse(it.step())
                }
            } finally { connection.close() }
        } finally { Files.deleteIfExists(path) }
    }
}

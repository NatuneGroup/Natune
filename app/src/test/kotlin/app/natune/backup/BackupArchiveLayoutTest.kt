package app.natune.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchiveLayoutTest {
    @Test
    fun natuneManifestsAreOwnArchivesAndEverythingElseIsForeign() {
        assertFalse(BackupArchiveLayout.isForeign("""{"format":"natune.desktop.backup","formatVersion":1}""".toByteArray(), true))
        assertFalse(BackupArchiveLayout.isForeign("""{"format":"natune.android.backup"}""".toByteArray(), true))
        // The manifest of a real Metrolist backup.
        assertTrue(BackupArchiveLayout.isForeign("""{"platform":"ANDROID","databaseVersion":65}""".toByteArray(), true))
        assertTrue(BackupArchiveLayout.isForeign("not json".toByteArray(), false))
        assertTrue(BackupArchiveLayout.isForeign("""{"format":7}""".toByteArray(), false))
    }

    @Test
    fun aMissingManifestIsForeignOnlyWhenTheArchiveUsesSettingsJson() {
        // A .backup file (song.db + settings.xml) has never carried a manifest.
        assertFalse(BackupArchiveLayout.isForeign(null, hasSettingsJson = false))
        assertTrue(BackupArchiveLayout.isForeign(null, hasSettingsJson = true))
    }

    @Test
    fun extraEntriesAreSkippedButNamesLeavingTheArchiveAreRefused() {
        listOf("backup.json", "settings.json").forEach { assertTrue(it, AndroidRestoreArchivePolicy.isAllowed(it)) }
        listOf("README.txt", "metrolist/extra.bin").forEach { name ->
            assertFalse(name, AndroidRestoreArchivePolicy.isAllowed(name))
            assertFalse(name, AndroidRestoreArchivePolicy.isTraversal(name))
        }
        listOf("../settings.xml", "/data/song.db", "fonts/..\\x.ttf", "fonts//x.ttf", "a/../../b").forEach { name ->
            assertTrue(name, AndroidRestoreArchivePolicy.isTraversal(name))
        }
    }
}

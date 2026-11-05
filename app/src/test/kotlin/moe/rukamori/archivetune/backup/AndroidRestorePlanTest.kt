package moe.rukamori.archivetune.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure allowlist/staging policy checks; the Android file transaction itself is not a JVM test. */
class AndroidRestorePlanTest {
    @Test
    fun legacyArchiveEntriesAndSidecarsAreAccepted() {
        listOf(
            "song.db",
            "song.db-wal",
            "song.db-shm",
            "song.db-journal",
            "settings.xml",
            "settings.preferences_pb",
            "fonts/custom.ttf",
        ).forEach { name ->
            assertTrue(name, AndroidRestoreArchivePolicy.isAllowed(name))
        }
    }

    @Test
    fun unknownAndPathTraversalEntriesAreRejected() {
        listOf(
            "../settings.xml",
            "fonts/../outside.ttf",
            "fonts//tmp/outside.ttf",
            "fonts/..\\outside.ttf",
            "song.db/../../settings.xml",
            "unknown.entry",
        ).forEach { name ->
            assertFalse(name, AndroidRestoreArchivePolicy.isAllowed(name))
        }
    }

    @Test
    fun fontNamesAreReducedToTheAllowlistedLeaf() {
        assertEquals("custom.ttf", AndroidRestoreArchivePolicy.fontName("fonts/custom.ttf"))
    }

    @Test
    fun secondRestoreIsRefusedWhileReadyOrJournalIsPresent() {
        assertTrue(AndroidRestoreArchivePolicy.canStage(readyExists = false, journalExists = false))
        assertFalse(AndroidRestoreArchivePolicy.canStage(readyExists = true, journalExists = false))
        assertFalse(AndroidRestoreArchivePolicy.canStage(readyExists = false, journalExists = true))
        assertFalse(AndroidRestoreArchivePolicy.canStage(readyExists = true, journalExists = true))
    }
}

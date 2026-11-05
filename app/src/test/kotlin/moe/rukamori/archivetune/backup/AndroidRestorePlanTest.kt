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

    @Test
    fun fontManifestRequiresTheExactSortedNameAndLengthSet() {
        val expected = listOf(
            AndroidRestoreFont("b.ttf", 20),
            AndroidRestoreFont("a.ttf", 10),
        )
        val encoded = AndroidRestoreFontPolicy.encode(expected)
        assertEquals(expected.sortedBy { it.name }, AndroidRestoreFontPolicy.decode(encoded))
        assertTrue(AndroidRestoreFontPolicy.matches(expected, expected.reversed()))
        assertFalse(AndroidRestoreFontPolicy.matches(expected, listOf(AndroidRestoreFont("a.ttf", 11), AndroidRestoreFont("b.ttf", 20))))
        assertFalse(AndroidRestoreFontPolicy.matches(expected, expected + AndroidRestoreFont("c.ttf", 30)))
        assertFalse(AndroidRestoreFontPolicy.isValid(AndroidRestoreFont("a.ttf", 0)))
    }

    @Test
    fun stageIdsAndBlockedMarkerPolicyAreBounded() {
        assertTrue(AndroidRestoreFontPolicy.isSafeStageId("123-abc"))
        assertFalse(AndroidRestoreFontPolicy.isSafeStageId("../outside"))
        assertFalse(AndroidRestoreFontPolicy.isSafeStageId(""))
        assertFalse(AndroidRestoreBlockedPolicy.shouldSkipHeavyRecovery(markerExists = false))
        assertTrue(AndroidRestoreBlockedPolicy.shouldSkipHeavyRecovery(markerExists = true))
        assertTrue(AndroidRestoreBlockedPolicy.shouldAttemptBestEffortScreen(markerExistedAtStartup = false))
        assertFalse(AndroidRestoreBlockedPolicy.shouldAttemptBestEffortScreen(markerExistedAtStartup = true))
    }

    @Test
    fun settingsRollbackPolicyRestoresOnlyAMatchingSnapshot() {
        assertEquals(
            AndroidRestoreSettingsRollbackAction.RESTORE_SNAPSHOT,
            AndroidRestoreSettingsRollbackPolicy.decide(previousSettingsPresent = true, snapshotExists = true),
        )
        assertEquals(
            AndroidRestoreSettingsRollbackAction.DELETE_LIVE,
            AndroidRestoreSettingsRollbackPolicy.decide(previousSettingsPresent = false, snapshotExists = false),
        )
        assertEquals(
            AndroidRestoreSettingsRollbackAction.FAIL_CLOSED,
            AndroidRestoreSettingsRollbackPolicy.decide(previousSettingsPresent = true, snapshotExists = false),
        )
        assertEquals(
            AndroidRestoreSettingsRollbackAction.FAIL_CLOSED,
            AndroidRestoreSettingsRollbackPolicy.decide(previousSettingsPresent = false, snapshotExists = true),
        )
    }
}

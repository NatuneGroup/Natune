package moe.rukamori.archivetune.backup

import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.data.backup.RecoveryOutcome
import moe.rukamori.archivetune.shared.data.backup.RestoreFileOps
import moe.rukamori.archivetune.shared.data.backup.RestoreTarget
import moe.rukamori.archivetune.shared.data.backup.RestoreTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The coordinator's recovery classification, exercised without Android Context or filesystem APIs. */
class AndroidRestoreCoordinatorRecoveryTest {
    private class FakeFileOps : RestoreFileOps {
        val files = linkedMapOf<String, ByteArray>()
        var failDeletes = false

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun atomicRename(from: String, to: String) {
            val bytes = files.remove(from) ?: throw IllegalStateException("missing $from")
            files[to] = bytes
        }

        override fun delete(path: String) {
            if (failDeletes) throw IllegalStateException("delete refused")
            files.remove(path)
        }

        override fun writeAtomically(path: String, bytes: ByteArray) {
            files[path] = bytes
        }

        override fun readBytes(path: String): ByteArray? = files[path]

        override fun list(directory: String): List<String> =
            files.keys.filter { it.startsWith("$directory/") }.sorted()
    }

    @Test
    fun committedRecoveryCleansUpWithoutBlockingOrResuming() = runTest {
        val ops = FakeFileOps()
        val journal = "restore-pending/transaction"
        val live = "databases/song.db"
        val staged = "restore-pending/stage-test/staged/song.db"
        val backup = "restore-pending/stage-test/previous/song.db"
        ops.files[live] = byteArrayOf(1)
        ops.files[staged] = byteArrayOf(2)

        // Fail during the post-COMMITTED cleanup so recovery sees committed leftovers.
        ops.failDeletes = true
        val transaction = RestoreTransaction(ops, journal, listOf(RestoreTarget(live, staged, backup)))
        try {
            transaction.commit()
            throw AssertionError("cleanup failure was not injected")
        } catch (_: IllegalStateException) {
        }
        assertTrue(ops.exists(journal))
        ops.failDeletes = false

        var cleanups = 0
        var queueDeletes = 0
        var blocked = 0
        var resumes = 0
        val ready = ReadyMarker(
            stageId = "stage-test",
            library = true,
            settings = false,
            account = false,
            settingsKind = SettingsKind.NONE,
            fontEntries = emptyList(),
            previousSettingsPresent = false,
        )
        val outcome = AndroidRestoreCoordinator.recoverAtStartup(
            ops = ops,
            journalPath = journal,
            ready = ready,
            callbacks = AndroidRestoreCoordinator.AndroidRestoreRecoveryCallbacks(
                hasRequiredStage = { true },
                commit = { throw AssertionError("COMMITTED must not commit") },
                cleanup = { cleanups++ },
                deletePendingQueue = { queueDeletes++ },
                resumeApply = { resumes++ },
                block = { blocked++ },
            ),
        )

        assertEquals(RecoveryOutcome.COMMITTED, outcome)
        assertEquals(1, queueDeletes)
        assertEquals(1, cleanups)
        assertEquals(0, resumes)
        assertEquals(0, blocked)
        assertFalse(ops.exists(journal))
    }

    @Test
    fun committedNoOpRecoveryKeepsTheSavedQueue() = runTest {
        val ops = FakeFileOps()
        val journal = "restore-pending/transaction"
        val transaction = RestoreTransaction(ops, journal, emptyList())
        ops.failDeletes = true
        runCatching { transaction.commit() }
        ops.failDeletes = false

        var queueDeletes = 0
        var cleanups = 0
        val ready = ReadyMarker(
            stageId = "stage-noop",
            library = false,
            settings = false,
            account = true,
            settingsKind = SettingsKind.NONE,
            fontEntries = emptyList(),
            previousSettingsPresent = false,
        )
        val outcome = AndroidRestoreCoordinator.recoverAtStartup(
            ops = ops,
            journalPath = journal,
            ready = ready,
            callbacks = AndroidRestoreCoordinator.AndroidRestoreRecoveryCallbacks(
                hasRequiredStage = { true },
                commit = { },
                cleanup = { cleanups++ },
                deletePendingQueue = { queueDeletes++ },
                resumeApply = { },
                block = { },
            ),
        )

        if (outcome == RecoveryOutcome.COMMITTED) assertEquals(1, cleanups)
        assertEquals(0, queueDeletes)
    }

    @Test
    fun settingsRollbackRestoresTheSnapshotOrDeletesTheCreatedFile() = runTest {
        val ops = FakeFileOps()
        val live = "files/datastore/settings.preferences_pb"
        val snapshot = "restore-pending/stage-test/settings-previous.preferences_pb"
        ops.files[live] = byteArrayOf(9)
        ops.files[snapshot] = byteArrayOf(1)

        restorePreviousSettingsFromSnapshot(ops, live, snapshot, previousSettingsPresent = true)
        assertTrue(ops.files.getValue(live).contentEquals(byteArrayOf(1)))

        ops.files.remove(snapshot)
        ops.files[live] = byteArrayOf(9)
        restorePreviousSettingsFromSnapshot(ops, live, snapshot, previousSettingsPresent = false)
        assertFalse(ops.exists(live))
    }
}

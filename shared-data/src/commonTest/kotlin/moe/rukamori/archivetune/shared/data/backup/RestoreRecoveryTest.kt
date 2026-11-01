package moe.rukamori.archivetune.shared.data.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The crash-safety contract of [RestoreTransaction] against an in-memory filesystem that can die on any
 * mutating call. A kill is an [Error], not an [Exception]: the transaction's own rollback must not run for a
 * process that vanished, which is exactly the state [RestoreTransaction.recover] exists for. Kills injected as
 * ordinary [RuntimeException]s instead exercise the in-process rollback path.
 */
class RestoreRecoveryTest {
    private class KilledException(val at: Int) : Error("killed at mutating op $at")

    private class FakeFileOps : RestoreFileOps {
        val files = mutableMapOf<String, ByteArray>()
        var mutatingOps = 0
        var failAt = -1
        var failAt2 = -1
        var succeedThenFailAt = -1
        var failAsProcessKill = true

        private fun checkpoint() {
            mutatingOps++
            if (mutatingOps == failAt || mutatingOps == failAt2) {
                if (failAsProcessKill) throw KilledException(mutatingOps)
                throw RuntimeException("injected failure at op $mutatingOps")
            }
        }

        // A post-op throw models a native call that applied its effect (the rename happened) but still
        // surfaced an error — the state after it must be recoverable, not a hidden partial move.
        private fun postCheckpoint() {
            if (mutatingOps == succeedThenFailAt) throw RuntimeException("post-op failure at op $mutatingOps")
        }

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun atomicRename(from: String, to: String) {
            checkpoint()
            val bytes = files.remove(from) ?: throw IllegalStateException("rename of missing $from")
            files[to] = bytes
            postCheckpoint()
        }

        override fun delete(path: String) {
            checkpoint()
            files.remove(path)
            postCheckpoint()
        }

        override fun writeAtomically(path: String, bytes: ByteArray) {
            checkpoint()
            files[path] = bytes
            postCheckpoint()
        }

        override fun readBytes(path: String): ByteArray? = files[path]

        override fun list(directory: String): List<String> =
            files.keys.filter { it.startsWith("$directory/") }.sorted()
    }

    private enum class Side { OLD, NEW }

    private fun targets(): List<RestoreTarget> =
        RestoreTarget.database(LIVE_DB, STAGED_DB, BACKUP_DB) +
            RestoreTarget(LIVE_SETTINGS, STAGED_SETTINGS, BACKUP_SETTINGS)

    private fun seeded(oldWal: Boolean = true, newWal: Boolean = true): FakeFileOps {
        val ops = FakeFileOps()
        ops.files[LIVE_DB] = OLD_DB
        if (oldWal) ops.files["$LIVE_DB-wal"] = OLD_WAL
        ops.files["$LIVE_DB-shm"] = OLD_SHM
        ops.files[LIVE_SETTINGS] = OLD_SETTINGS
        ops.files[STAGED_DB] = NEW_DB
        if (newWal) ops.files["$STAGED_DB-wal"] = NEW_WAL
        ops.files[STAGED_DB + "-shm"] = NEW_SHM
        ops.files[STAGED_SETTINGS] = NEW_SETTINGS
        return ops
    }

    /** The whole live set must match one side of the swap: old everywhere or new everywhere, never a mix. */
    private fun assertLiveEquals(ops: FakeFileOps, side: Side, oldWal: Boolean = true, newWal: Boolean = true) {
        val wal = when (side) {
            Side.OLD -> if (oldWal) OLD_WAL else null
            Side.NEW -> if (newWal) NEW_WAL else null
        }
        val expected = mapOf(
            LIVE_DB to if (side == Side.OLD) OLD_DB else NEW_DB,
            "$LIVE_DB-wal" to wal,
            "$LIVE_DB-shm" to if (side == Side.OLD) OLD_SHM else NEW_SHM,
            LIVE_SETTINGS to if (side == Side.OLD) OLD_SETTINGS else NEW_SETTINGS,
        )
        expected.forEach { (path, bytes) ->
            if (bytes == null) {
                assertNull(ops.files[path], "live file that must be absent survived: $path")
            } else {
                assertContentEquals(bytes, ops.files[path], "live file has the wrong contents: $path")
            }
        }
    }

    private fun assertConsistentAfterRecover(
        ops: FakeFileOps,
        outcome: RecoveryOutcome,
        oldWal: Boolean = true,
        newWal: Boolean = true,
    ) {
        when (outcome) {
            RecoveryOutcome.NOTHING, RecoveryOutcome.ROLLED_BACK -> assertLiveEquals(ops, Side.OLD, oldWal, newWal)
            RecoveryOutcome.COMMITTED, RecoveryOutcome.PENDING_APPLY ->
                assertLiveEquals(ops, Side.NEW, oldWal, newWal)
            RecoveryOutcome.CORRUPT -> error("recovery reported CORRUPT with every file recoverable")
        }
    }

    @Test
    fun happyPathCommitsAllTargetsAndCleansUp() {
        val ops = seeded()
        RestoreTransaction(ops, JOURNAL, targets()).commit()
        assertLiveEquals(ops, Side.NEW)
        assertFalse(ops.exists(JOURNAL), "journal left after a clean commit")
        listOf(BACKUP_DB, "$BACKUP_DB-wal", "$BACKUP_DB-shm", BACKUP_SETTINGS).forEach {
            assertFalse(ops.exists(it), "backup left after a clean commit: $it")
        }
        listOf(STAGED_DB, "$STAGED_DB-wal", "$STAGED_DB-shm", STAGED_SETTINGS).forEach {
            assertFalse(ops.exists(it), "staged file left after a clean commit: $it")
        }
    }

    @Test
    fun everyCommitKillPointRecoversToAConsistentState() {
        val total = FakeFileOps().also { ops ->
            ops.files.putAll(seeded().files)
            RestoreTransaction(ops, JOURNAL, targets()).commit()
        }.mutatingOps
        for (kill in 1..total) {
            val ops = seeded()
            ops.failAt = kill
            assertFailsWith<KilledException> { RestoreTransaction(ops, JOURNAL, targets()).commit() }
            val outcome = RestoreTransaction.recover(ops, JOURNAL)
            assertConsistentAfterRecover(ops, outcome)
            // Recovery is done once it succeeded; a second pass is a no-op, not a second interpretation.
            assertEquals(RecoveryOutcome.NOTHING, RestoreTransaction.recover(ops, JOURNAL), "recover #$kill rerun")
            assertConsistentAfterRecover(
                ops,
                if (outcome == RecoveryOutcome.COMMITTED) RecoveryOutcome.COMMITTED else RecoveryOutcome.ROLLED_BACK,
            )
        }
    }

    @Test
    fun killsDuringRecoveryStillConverge() {
        // A kill mid-install leaves the ORIGINALS_MOVED journal; recovery from there takes a known op count.
        val ops = seeded()
        ops.failAt = 8 // first staged -> live promote already landed for the database
        assertFailsWith<KilledException> { RestoreTransaction(ops, JOURNAL, targets()).commit() }
        val recoveryOps = FakeFileOps().also { counting ->
            counting.files.putAll(ops.files)
        }.let { counting ->
            RestoreTransaction.recover(counting, JOURNAL)
            counting.mutatingOps
        }
        for (kill in 1..recoveryOps) {
            val crashed = seeded()
            crashed.failAt = 8
            assertFailsWith<KilledException> { RestoreTransaction(crashed, JOURNAL, targets()).commit() }
            crashed.mutatingOps = 0
            crashed.failAt = kill
            assertFailsWith<KilledException> { RestoreTransaction.recover(crashed, JOURNAL) }
            crashed.failAt = -1
            assertEquals(RecoveryOutcome.ROLLED_BACK, RestoreTransaction.recover(crashed, JOURNAL), "kill #$kill")
            assertLiveEquals(crashed, Side.OLD)
        }
    }

    @Test
    fun walSidecarPresentOrAbsentOnEitherSide() {
        data class WalCase(val oldWal: Boolean, val newWal: Boolean, val kill: Int)
        listOf(
            WalCase(oldWal = true, newWal = true, kill = -1),
            WalCase(oldWal = false, newWal = true, kill = -1),
            WalCase(oldWal = true, newWal = false, kill = -1),
            WalCase(oldWal = false, newWal = false, kill = -1),
            WalCase(oldWal = true, newWal = false, kill = 8),
            WalCase(oldWal = false, newWal = true, kill = 8),
        ).forEach { (oldWal, newWal, kill) ->
            val ops = seeded(oldWal, newWal)
            if (kill >= 0) {
                ops.failAt = kill
                assertFailsWith<KilledException> { RestoreTransaction(ops, JOURNAL, targets()).commit() }
                assertConsistentAfterRecover(ops, RestoreTransaction.recover(ops, JOURNAL), oldWal, newWal)
                assertLiveEquals(ops, Side.OLD, oldWal, newWal)
            } else {
                RestoreTransaction(ops, JOURNAL, targets()).commit()
                assertLiveEquals(ops, Side.NEW, oldWal, newWal)
            }
        }
    }

    @Test
    fun missingBackupAndLiveFailsClosed() {
        val ops = seeded()
        ops.failAt = 8 // kill after ORIGINALS_MOVED was journaled, mid staged -> live promotes
        assertFailsWith<KilledException> { RestoreTransaction(ops, JOURNAL, targets()).commit() }
        // Both copies of the database are gone: neither the old nor the new state can be rebuilt.
        ops.files.remove(LIVE_DB)
        ops.files.remove(BACKUP_DB)
        assertEquals(RecoveryOutcome.CORRUPT, RestoreTransaction.recover(ops, JOURNAL))
        assertTrue(ops.exists(JOURNAL), "corrupt recovery must keep the journal for a later decision")
    }

    @Test
    fun unreadableJournalFailsClosed() {
        val ops = seeded()
        ops.files[JOURNAL] = byteArrayOf(0x1f, 0x2e)
        assertEquals(RecoveryOutcome.CORRUPT, RestoreTransaction.recover(ops, JOURNAL))
    }

    @Test
    fun missingRequiredStageAbortsBeforeJournaling() {
        val ops = seeded()
        ops.files.remove(STAGED_DB)
        assertFailsWith<IllegalStateException> { RestoreTransaction(ops, JOURNAL, targets()).commit() }
        assertEquals(RecoveryOutcome.NOTHING, RestoreTransaction.recover(ops, JOURNAL))
        assertLiveEquals(ops, Side.OLD)
    }

    @Test
    fun inProcessFailureRollsBackInsideCommit() {
        val ops = seeded()
        ops.failAsProcessKill = false
        ops.failAt = 8 // inside the commit, after ORIGINALS_MOVED: the database is already promoted
        assertFailsWith<RuntimeException> { RestoreTransaction(ops, JOURNAL, targets()).commit() }
        assertLiveEquals(ops, Side.OLD)
        assertFalse(ops.exists(JOURNAL))
        assertFalse(ops.exists(BACKUP_DB))
        assertFalse(ops.exists(STAGED_SETTINGS))
    }

    @Test
    fun journalRoundTripsPhaseAndTargets() {
        val entries = listOf(
            RestorePlanEntry("a/db", "s/db", "p/db", hadLive = true, hasStaged = true),
            RestorePlanEntry("a/db-wal", "s/db-wal", "p/db-wal", hadLive = false, hasStaged = true),
        )
        val decoded = RestorePhaseJournal.decode(RestorePhaseJournal(RestorePhase.ORIGINALS_MOVED, entries).encode())
        assertEquals(RestorePhase.ORIGINALS_MOVED, decoded.phase)
        assertEquals(entries, decoded.targets)
    }

    /** A fully retained commit: new files live, old files under the backup paths, `APPLYING` journal. */
    private fun appliedOps(oldWal: Boolean = true, newWal: Boolean = true): FakeFileOps {
        val ops = seeded(oldWal, newWal)
        RestoreTransaction(ops, JOURNAL, targets()).commit(retainBackups = true)
        return ops
    }

    private fun assertBackupsAreOld(ops: FakeFileOps, oldWal: Boolean = true) {
        assertContentEquals(OLD_DB, ops.files[BACKUP_DB], "backup db was not the original")
        if (oldWal) {
            assertContentEquals(OLD_WAL, ops.files["$BACKUP_DB-wal"], "backup wal was not the original")
        } else {
            assertNull(ops.files["$BACKUP_DB-wal"], "backup wal should not exist")
        }
        assertContentEquals(OLD_SHM, ops.files["$BACKUP_DB-shm"], "backup shm was not the original")
        assertContentEquals(OLD_SETTINGS, ops.files[BACKUP_SETTINGS], "backup settings were not the original")
    }

    /** The mutating ops [run] performs on a freshly retained commit, isolated from the commit's own op count. */
    private fun mutatingOpCountOf(run: (FakeFileOps) -> Unit): Int {
        val ops = appliedOps()
        ops.mutatingOps = 0
        run(ops)
        return ops.mutatingOps
    }

    @Test
    fun retainedCommitReportsPendingApplyAndKeepsBackups() {
        val ops = appliedOps()
        assertLiveEquals(ops, Side.NEW)
        assertBackupsAreOld(ops)
        assertTrue(ops.exists(JOURNAL), "retained commit must keep the journal")
        assertEquals(RecoveryOutcome.PENDING_APPLY, RestoreTransaction.recover(ops, JOURNAL))
        assertNotNull(RestoreTransaction.resume(ops, JOURNAL))
    }

    @Test
    fun finalizeKillPointsStillEndFullyNew() {
        val total = mutatingOpCountOf { RestoreTransaction.resume(it, JOURNAL)!!.finalize() }
        for (kill in 1..total) {
            val ops = appliedOps()
            ops.mutatingOps = 0
            ops.failAt = kill
            assertFailsWith<KilledException> { RestoreTransaction.resume(ops, JOURNAL)!!.finalize() }
            when (RestoreTransaction.recover(ops, JOURNAL)) {
                RecoveryOutcome.PENDING_APPLY -> RestoreTransaction.resume(ops, JOURNAL)!!.finalize()
                RecoveryOutcome.COMMITTED -> Unit
                else -> error("unexpected recovery outcome at kill #$kill")
            }
            assertLiveEquals(ops, Side.NEW)
            assertFalse(ops.exists(JOURNAL), "kill #$kill")
        }
    }

    @Test
    fun rollbackCommittedKillPointsStillEndFullyOld() {
        val total = mutatingOpCountOf { RestoreTransaction.resume(it, JOURNAL)!!.rollbackCommitted() }
        for (kill in 1..total) {
            val ops = appliedOps()
            ops.mutatingOps = 0
            ops.failAt = kill
            assertFailsWith<KilledException> { RestoreTransaction.resume(ops, JOURNAL)!!.rollbackCommitted() }
            when (RestoreTransaction.recover(ops, JOURNAL)) {
                RecoveryOutcome.PENDING_APPLY -> RestoreTransaction.resume(ops, JOURNAL)!!.rollbackCommitted()
                RecoveryOutcome.ROLLED_BACK -> Unit
                else -> error("unexpected recovery outcome at kill #$kill")
            }
            assertLiveEquals(ops, Side.OLD)
            assertFalse(ops.exists(JOURNAL), "kill #$kill")
        }
    }

    @Test
    fun pendingApplyRoundTrip() {
        // The non-file apply succeeded: finalize keeps the new files and drops the backups and journal.
        val committed = appliedOps(oldWal = false, newWal = true)
        assertEquals(RecoveryOutcome.PENDING_APPLY, RestoreTransaction.recover(committed, JOURNAL))
        RestoreTransaction.resume(committed, JOURNAL)!!.finalize()
        assertLiveEquals(committed, Side.NEW, oldWal = false, newWal = true)
        assertFalse(committed.exists(JOURNAL))
        assertFalse(committed.exists(BACKUP_DB))

        // The non-file apply failed: rollbackCommitted puts the old files back everywhere, wal included.
        val rolled = appliedOps(oldWal = true, newWal = false)
        assertEquals(RecoveryOutcome.PENDING_APPLY, RestoreTransaction.recover(rolled, JOURNAL))
        RestoreTransaction.resume(rolled, JOURNAL)!!.rollbackCommitted()
        assertLiveEquals(rolled, Side.OLD, oldWal = true, newWal = false)
        assertFalse(rolled.exists(JOURNAL))
        assertFalse(rolled.exists(BACKUP_DB))
    }

    @Test
    fun resumeReturnsNullWithoutAJournal() {
        assertNull(RestoreTransaction.resume(FakeFileOps(), JOURNAL))
        val malformed = FakeFileOps()
        malformed.files[JOURNAL] = byteArrayOf(0x7f)
        assertNull(RestoreTransaction.resume(malformed, JOURNAL))
    }

    @Test
    fun finalizeAndRollbackCommittedWithoutJournalAreNoops() {
        val ops = seeded()
        val transaction = RestoreTransaction(ops, JOURNAL, targets())
        transaction.finalize()
        transaction.rollbackCommitted()
        assertLiveEquals(ops, Side.OLD)
    }

    @Test
    fun rollbackCommittedRejectsAnUncommittedJournal() {
        val ops = seeded()
        ops.failAt = 2 // killed while moving originals: journal stays STAGED
        assertFailsWith<KilledException> { RestoreTransaction(ops, JOURNAL, targets()).commit() }
        assertFailsWith<IllegalStateException> {
            RestoreTransaction.resume(ops, JOURNAL)!!.rollbackCommitted()
        }
        assertEquals(RecoveryOutcome.ROLLED_BACK, RestoreTransaction.recover(ops, JOURNAL))
        assertLiveEquals(ops, Side.OLD)
    }

    /** The phase the journal on disk currently records; the test fails if it is unreadable. */
    private fun currentPhase(ops: FakeFileOps): RestorePhase =
        RestorePhaseJournal.decode(ops.files.getValue(JOURNAL).decodeToString()).phase

    @Test
    fun rollbackStepFailureInsideCommitKeepsTheJournal() {
        val ops = seeded()
        ops.failAsProcessKill = false
        ops.failAt = 8 // inside the commit: the wal install fails after the database was promoted
        ops.failAt2 = 10 // inside the rollback that follows: the first originals restore fails too
        assertFailsWith<RuntimeException> { RestoreTransaction(ops, JOURNAL, targets()).commit() }
        // A failed rollback step must not be swallowed: the journal stays at ORIGINALS_MOVED so the next
        // recover() retries the restore instead of startup opening a half-restored database.
        assertEquals(RestorePhase.ORIGINALS_MOVED, currentPhase(ops))
        assertEquals(RecoveryOutcome.ROLLED_BACK, RestoreTransaction.recover(ops, JOURNAL))
        assertLiveEquals(ops, Side.OLD)
        assertFalse(ops.exists(JOURNAL))
    }

    @Test
    fun runtimeSidecarsSurvivePendingApplyRecovery() {
        // Room opened between the retained commit and the kill that stranded APPLYING: its wal is a runtime
        // file, not a swap file, and must not by itself force a rollback or CORRUPT.
        val ops = appliedOps(newWal = false)
        ops.files["$LIVE_DB-wal"] = RUNTIME_WAL
        assertEquals(RecoveryOutcome.PENDING_APPLY, RestoreTransaction.recover(ops, JOURNAL))
        RestoreTransaction.resume(ops, JOURNAL)!!.finalize()
        assertContentEquals(RUNTIME_WAL, ops.files["$LIVE_DB-wal"])
        assertFalse(ops.exists(JOURNAL))
    }

    @Test
    fun runtimeSidecarsDoNotCorruptCommittedRecovery() {
        // Cleanup had already deleted the wal backup when the kill landed, and Room then created a fresh wal.
        // Requiring sidecar presence to match the journal would read this as CORRUPT; it is a finished commit.
        val ops = appliedOps(newWal = false)
        val journal = RestorePhaseJournal.decode(ops.files.getValue(JOURNAL).decodeToString())
        ops.files[JOURNAL] = RestorePhaseJournal(RestorePhase.COMMITTED, journal.targets).encode().encodeToByteArray()
        ops.files.remove("$BACKUP_DB-wal")
        ops.files["$LIVE_DB-wal"] = RUNTIME_WAL
        assertEquals(RecoveryOutcome.COMMITTED, RestoreTransaction.recover(ops, JOURNAL))
        assertContentEquals(RUNTIME_WAL, ops.files["$LIVE_DB-wal"])
        assertFalse(ops.exists(JOURNAL))
    }

    @Test
    fun rollbackDeletesRuntimeSidecarsWithoutOriginals() {
        // The old database never had a wal: a runtime wal created beside the new one must not survive the
        // rollback and pair the restored db with a foreign wal.
        val ops = appliedOps(oldWal = false, newWal = false)
        ops.files["$LIVE_DB-wal"] = RUNTIME_WAL
        RestoreTransaction.resume(ops, JOURNAL)!!.rollbackCommitted()
        assertNull(ops.files["$LIVE_DB-wal"])
        assertLiveEquals(ops, Side.OLD, oldWal = false, newWal = false)
    }

    @Test
    fun rollbackRestoresTheOriginalSidecarOverRuntimeOnes() {
        val ops = appliedOps(oldWal = true, newWal = false)
        ops.files["$LIVE_DB-wal"] = RUNTIME_WAL
        RestoreTransaction.resume(ops, JOURNAL)!!.rollbackCommitted()
        assertContentEquals(OLD_WAL, ops.files["$LIVE_DB-wal"])
        assertLiveEquals(ops, Side.OLD, oldWal = true, newWal = false)
    }

    @Test
    fun killPointsDuringCommittedRollbackStillConverge() {
        // A live file lost outside the swap leaves APPLYING inconsistent: recovery rolls the commit back, and
        // a kill mid-rollback re-enters through ORIGINALS_MOVED — spent backups mean "already restored", never
        // "missing original", so every kill point still converges to the old files.
        val total = mutatingOpCountOf { ops ->
            ops.files.remove(LIVE_SETTINGS)
            RestoreTransaction.recover(ops, JOURNAL)
        }
        for (kill in 1..total) {
            val ops = appliedOps()
            ops.files.remove(LIVE_SETTINGS)
            ops.mutatingOps = 0
            ops.failAt = kill
            assertFailsWith<KilledException> { RestoreTransaction.recover(ops, JOURNAL) }
            ops.failAt = -1
            assertEquals(
                RecoveryOutcome.ROLLED_BACK,
                RestoreTransaction.recover(ops, JOURNAL),
                "kill #$kill",
            )
            assertLiveEquals(ops, Side.OLD)
            assertFalse(ops.exists(JOURNAL), "kill #$kill")
        }
    }

    @Test
    fun postRenameFailureDuringCommittedRollbackRetries() {
        // The restore rename landed but the op still reported failure: rollbackCommitted aborts mid-way, and
        // the ORIGINALS_MOVED journal it wrote first lets recover() finish the same rollback.
        val ops = appliedOps()
        ops.mutatingOps = 0
        ops.succeedThenFailAt = 3 // op 1 journals ORIGINALS_MOVED; ops 2.. are the restore renames
        assertFailsWith<RuntimeException> {
            RestoreTransaction.resume(ops, JOURNAL)!!.rollbackCommitted()
        }
        ops.succeedThenFailAt = -1
        assertEquals(RestorePhase.ORIGINALS_MOVED, currentPhase(ops))
        assertEquals(RecoveryOutcome.ROLLED_BACK, RestoreTransaction.recover(ops, JOURNAL))
        assertLiveEquals(ops, Side.OLD)
        assertFalse(ops.exists(JOURNAL))
    }

    @Test
    fun postRenameFailureDuringRecoveryRollbackRetries() {
        // Same post-op failure inside the recovery rollback itself: the thrown op already applied, the journal
        // knows the phase, and the next recover() treats the restored targets as done rather than lost.
        val ops = appliedOps()
        ops.files.remove(LIVE_SETTINGS) // live set no longer matches APPLYING: full rollback path
        ops.mutatingOps = 0
        ops.succeedThenFailAt = 3 // op 1 journals ORIGINALS_MOVED; ops 2.. are the restore renames
        assertFailsWith<RuntimeException> { RestoreTransaction.recover(ops, JOURNAL) }
        ops.succeedThenFailAt = -1
        assertEquals(RestorePhase.ORIGINALS_MOVED, currentPhase(ops))
        assertEquals(RecoveryOutcome.ROLLED_BACK, RestoreTransaction.recover(ops, JOURNAL))
        assertLiveEquals(ops, Side.OLD)
        assertFalse(ops.exists(JOURNAL))
    }

    private companion object {
        const val JOURNAL = "restore-pending/journal"
        const val LIVE_DB = "data/portable-state.db"
        const val STAGED_DB = "restore-pending/portable-state.db"
        const val BACKUP_DB = "previous/portable-state.db"
        const val LIVE_SETTINGS = "data/settings.json"
        const val STAGED_SETTINGS = "restore-pending/settings.json"
        const val BACKUP_SETTINGS = "previous/settings.json"

        val OLD_DB = byteArrayOf(1, 1, 1)
        val OLD_WAL = byteArrayOf(2, 2, 2)
        val OLD_SHM = byteArrayOf(3, 3, 3)
        val OLD_SETTINGS = byteArrayOf(4, 4, 4)
        val NEW_DB = byteArrayOf(9, 9, 9)
        val NEW_WAL = byteArrayOf(8, 8, 8)
        val NEW_SHM = byteArrayOf(7, 7, 7)
        val NEW_SETTINGS = byteArrayOf(6, 6, 6)
        val RUNTIME_WAL = byteArrayOf(5, 5, 5)
    }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.data.backup.IosRestoreFileOps
import moe.rukamori.archivetune.shared.data.backup.PortableBackupFormat
import moe.rukamori.archivetune.shared.data.backup.RecoveryOutcome
import moe.rukamori.archivetune.shared.data.backup.RestoreFileOps
import moe.rukamori.archivetune.shared.data.backup.RestoreStartFacts
import moe.rukamori.archivetune.shared.data.backup.RestoreStartStep
import moe.rukamori.archivetune.shared.data.backup.RestoreTarget
import moe.rukamori.archivetune.shared.data.backup.RestoreTransaction
import moe.rukamori.archivetune.shared.data.backup.decideRestoreStart
import moe.rukamori.archivetune.shared.data.backup.mergedRestoredSettings
import moe.rukamori.archivetune.shared.data.backup.readPortableFileBytes
import moe.rukamori.archivetune.shared.data.persistence.BackupCategory
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.domain.backup.BackupDataCategory
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore
import platform.Foundation.NSFileManager

/** The [RestoreTransaction] journal inside a staged restore, beside the `journal` format it replaces. */
internal const val RESTORE_TRANSACTION_FILE = "transaction"

/** The originals a retained commit sets aside, inside the staged restore so a failure can keep them. */
private const val RESTORE_PREVIOUS_DIRECTORY = "previous"

/** Presence is the whole marker; a single byte keeps the atomic write honest. */
private val RESTORE_ABORT_SENTINEL = byteArrayOf(1)

/**
 * What [IosRestoreStartup.begin] settled before the session's database may open. A legacy pending set keeps
 * driving its own helpers through [pending]; a transaction restore returns its [transaction] handle so the
 * vault apply can end in [RestoreTransaction.finalize] or [RestoreTransaction.rollbackCommitted].
 */
internal class IosRestoreStart(
    val pending: IosPendingRestore? = null,
    val transaction: RestoreTransaction? = null,
    /** The live database file is the staged one now and must prove itself to Room before settings merge. */
    val databaseSwapped: Boolean = false,
    /** Staged settings still owe the vault the owned-section merge. */
    val needsSettingsApply: Boolean = false,
    /**
     * The file rollback is done but the vault still holds the merged settings — the vault rollback
     * owes it the `settings-before` snapshot before the old library may open.
     */
    val needsSettingsRollback: Boolean = false,
    /**
     * Fail closed: an unresolved-corruption marker holds the instance and only recovery mode may run — no
     * database open, no backup work — until the user picks a resolution.
     */
    val blocked: Boolean = false,
)

/**
 * The startup half of a staged restore on top of [RestoreTransaction]. [begin] runs before Room opens: it
 * finishes or undoes whatever an earlier process journaled, completes a persisted abort jointly — files then
 * vault — and refuses to open at all while the blocked marker stands. The vault apply then ends a healthy
 * commit: the settings merge runs while the original files are still set aside, and only then are the backups
 * and journal dropped.
 */
internal object IosRestoreStartup {
    fun transactionJournalPath(pendingDirectory: String): String =
        "$pendingDirectory/$RESTORE_TRANSACTION_FILE"

    private fun stagedSettingsPath(pendingDirectory: String): String =
        "$pendingDirectory/${PortableBackupFormat.SETTINGS}"

    /**
     * Resolves the staged restore of an earlier backup session while Room is still closed. A killed swap is
     * completed or rolled back from the journal, a fresh staged set starts its retained commit, a pending set
     * the old journal format already began is left for the legacy helpers — never both — and a persisted abort
     * finishes undoing the joint apply instead of being re-read as a new restore. The instance-level blocked
     * marker outranks all of it: set by an unresolvable journal or a failed joint undo, it holds recovery mode
     * until the user resolves it.
     */
    fun begin(instanceDirectory: String, databaseFile: String, report: (String) -> Unit): IosRestoreStart {
        // A journaled resolution is settled before anything else reads state: one still bound to a live block
        // resumes its own choice rather than re-prompting, one whose block is already gone owes only its own
        // delete, and a stale journal is ignored so it can never resolve a different block.
        if (IosRestoreRecovery.resumeResolution(IosRestoreFileOps(), instanceDirectory, databaseFile, iosNowMillis())) {
            report("recorded restore resolution finished")
        }
        if (IosRestoreRecovery.isBlocked(instanceDirectory)) {
            report("restore blocked; recovery mode until the user resolves it")
            return IosRestoreStart(blocked = true)
        }
        val pending = try {
            IosPendingRestore.read(instanceDirectory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("staged restore unreadable: ${failure::class.simpleName}")
            return IosRestoreStart()
        } ?: return IosRestoreStart()
        if (pending.legacyFormat) {
            // The build that wrote the old journal owns this swap to its end; the transaction machine must not
            // second-guess files another format already moved.
            try {
                pending.swapDatabase(instanceDirectory, databaseFile)
            } catch (failure: Exception) {
                report("restored library not applied: ${failure::class.simpleName}")
                try {
                    pending.rollbackDatabase(instanceDirectory, databaseFile)
                } catch (rollback: Exception) {
                    report("library rollback failed: ${rollback::class.simpleName}")
                }
                runCatching { pending.markFailed() }
            }
            return IosRestoreStart(pending = pending)
        }
        val ops = IosRestoreFileOps()
        val journalPath = transactionJournalPath(pending.directory)
        // recover() ends a rollback by deleting the journal, so the abort decision must be durable first:
        // a kill in between leaves nothing to re-derive it from, and the next start re-reads the rolled-back
        // set as fresh staging while the merged vault snapshot waits forever. Only a journal whose recovery
        // can end no other way earns the marker — a resumable apply or a finished commit must not be flipped
        // into an undo — and a marker that cannot be written blocks the set rather than undoing blind.
        val abortPath = IosRestoreRecovery.abortMarkerPath(pending.directory)
        if (!ops.exists(abortPath) && RestoreTransaction.recoverEndsInRollback(ops, journalPath)) {
            try {
                ops.writeAtomically(abortPath, RESTORE_ABORT_SENTINEL)
            } catch (failure: Exception) {
                report("restore abort marker not written: ${failure::class.simpleName}")
                return blockedStart(ops, instanceDirectory, pending, report)
            }
        }
        val outcome = try {
            RestoreTransaction.recover(ops, journalPath)
        } catch (failure: Exception) {
            report("restore journal unreadable: ${failure::class.simpleName}")
            RecoveryOutcome.CORRUPT
        }
        val step = decideRestoreStart(
            RestoreStartFacts(
                blocked = false,
                pending = true,
                legacyFormat = false,
                abortMarker = ops.exists(IosRestoreRecovery.abortMarkerPath(pending.directory)),
                settingsBefore = pending.hasSettingsBefore,
                stagedSettings = pending.hasStagedSettings,
                ownedSettingsEmpty = ownedSettingCategories(pending).isEmpty(),
                outcome = outcome,
            ),
        )
        return when (step) {
            RestoreStartStep.START_COMMIT ->
                startCommit(ops, pending, instanceDirectory, databaseFile, journalPath, report)
            RestoreStartStep.RESUME_APPLY -> resumeApply(ops, pending, instanceDirectory, journalPath, report)
            RestoreStartStep.FINISH_COMMITTED -> {
                // A cleanup kill: the new files are live and only the staging directory is left to drop.
                runCatching { deleteTree(pending.directory) }
                IosRestoreStart()
            }
            RestoreStartStep.FAILED_ROLLBACK -> {
                report("interrupted restore rolled back")
                runCatching { pending.markFailed() }
                IosRestoreStart(pending = pending)
            }
            RestoreStartStep.JOINT_ABORT, RestoreStartStep.VAULT_ROLLBACK ->
                jointAbort(ops, pending, instanceDirectory, journalPath, outcome, report)
            else -> blockedStart(ops, instanceDirectory, pending, report)
        }
    }

    /** Starts the two-phase commit for a fresh staged set: database files swap now, backups stay for the apply. */
    private fun startCommit(
        ops: RestoreFileOps,
        pending: IosPendingRestore,
        instanceDirectory: String,
        databaseFile: String,
        journalPath: String,
        report: (String) -> Unit,
    ): IosRestoreStart {
        val targets = if (BackupDataCategory.LIBRARY in pending.categories) {
            ensureDirectory("${pending.directory}/$RESTORE_PREVIOUS_DIRECTORY")
            RestoreTarget.database(
                livePath = "$instanceDirectory/$databaseFile",
                stagedPath = "${pending.directory}/${PortableBackupFormat.DATABASE}",
                backupPath = "${pending.directory}/$RESTORE_PREVIOUS_DIRECTORY/$databaseFile",
            )
        } else {
            emptyList()
        }
        val transaction = RestoreTransaction(ops, journalPath, targets)
        try {
            transaction.commit(retainBackups = true)
        } catch (failure: Exception) {
            report("restored library not applied: ${failure::class.simpleName}")
            if (ops.exists(journalPath)) {
                // The rollback left the journal behind — its own undo failed or never finished — so files may
                // sit mid-swap. Hold the instance for recovery mode instead of opening around them.
                report("restore rollback incomplete; journal still present")
                return blockedStart(ops, instanceDirectory, pending, report)
            }
            runCatching { pending.markFailed() }
            return IosRestoreStart(pending = pending)
        }
        return IosRestoreStart(
            pending = pending,
            transaction = transaction,
            databaseSwapped = targets.isNotEmpty(),
            needsSettingsApply = pending.hasStagedSettings,
        )
    }

    /**
     * The undo of the joint apply, whether the abort marker is fresh or already persisted: the database
     * rollback finishes first — a still-committed journal goes through [RestoreTransaction.rollbackCommitted] —
     * then the graph owes the vault its `settings-before` snapshot while [IosRestoreStart.needsSettingsRollback]
     * stands. Without a snapshot the vault never merged and the plain failed surface is enough.
     */
    private fun jointAbort(
        ops: RestoreFileOps,
        pending: IosPendingRestore,
        instanceDirectory: String,
        journalPath: String,
        outcome: RecoveryOutcome,
        report: (String) -> Unit,
    ): IosRestoreStart {
        try {
            ops.writeAtomically(IosRestoreRecovery.abortMarkerPath(pending.directory), RESTORE_ABORT_SENTINEL)
        } catch (failure: Exception) {
            report("restore abort marker not written: ${failure::class.simpleName}")
            return blockedStart(ops, instanceDirectory, pending, report)
        }
        when (outcome) {
            RecoveryOutcome.PENDING_APPLY, RecoveryOutcome.COMMITTED -> {
                val transaction = RestoreTransaction.resume(ops, journalPath)
                val rolledBack = transaction != null && try {
                    transaction.rollbackCommitted()
                    true
                } catch (failure: Exception) {
                    report("joint abort rollback failed: ${failure::class.simpleName}")
                    false
                }
                if (!rolledBack) return blockedStart(ops, instanceDirectory, pending, report)
            }
            RecoveryOutcome.CORRUPT -> return blockedStart(ops, instanceDirectory, pending, report)
            else -> Unit // NOTHING or ROLLED_BACK: the file rollback already settled.
        }
        return if (pending.hasSettingsBefore) {
            IosRestoreStart(pending = pending, needsSettingsRollback = true)
        } else {
            runCatching { pending.markFailed() }
            IosRestoreStart(pending = pending)
        }
    }

    /**
     * Fail closed, durably: the blocked marker goes down before the pending set is quarantined, and when the
     * marker itself cannot be written the set stays in place so the next start re-detects the state rather
     * than opening files nobody can vouch for.
     */
    private fun blockedStart(
        ops: RestoreFileOps,
        instanceDirectory: String,
        pending: IosPendingRestore?,
        report: (String) -> Unit,
    ): IosRestoreStart {
        val marked = runCatching { IosRestoreRecovery.block(ops, instanceDirectory) }.isSuccess
        if (!marked) {
            report("restore blocked marker not written; pending set kept for the next start")
        }
        if (marked) runCatching { pending?.markFailed() }
        return IosRestoreStart(pending = pending, blocked = true)
    }

    /** A retained commit an earlier process left behind; the vault apply is owed again, then [finalize]. */
    private fun resumeApply(
        ops: RestoreFileOps,
        pending: IosPendingRestore,
        instanceDirectory: String,
        journalPath: String,
        report: (String) -> Unit,
    ): IosRestoreStart {
        val transaction = RestoreTransaction.resume(ops, journalPath)
        if (transaction == null) {
            report("restore journal lost mid-apply")
            return blockedStart(ops, instanceDirectory, pending, report)
        }
        return IosRestoreStart(
            pending = pending,
            transaction = transaction,
            databaseSwapped = transaction.targets.any {
                it.stagedPath == "${pending.directory}/${PortableBackupFormat.DATABASE}"
            },
            needsSettingsApply = pending.hasStagedSettings,
        )
    }

    /** The settings sections the staged restore owns; a missing marker leaves them unowned, never guessed. */
    private fun ownedSettingCategories(pending: IosPendingRestore): Set<BackupCategory> =
        pending.categories.mapNotNull { category ->
            when (category) {
                BackupDataCategory.ACCOUNT -> BackupCategory.ACCOUNT
                BackupDataCategory.SETTINGS -> BackupCategory.SETTINGS
                BackupDataCategory.LIBRARY -> null
            }
        }.toSet()

    /**
     * The vault half of the staged restore: the live snapshot is written to `settings-before` once, then the
     * owned sections merge over it. Idempotent — a restarted apply finds the snapshot and writes the same merge.
     */
    suspend fun applyPendingSettings(
        pending: IosPendingRestore,
        ops: RestoreFileOps,
        settings: VaultSettingsStore,
    ) {
        val staged = stagedSettingsPath(pending.directory)
        if (!ops.exists(staged)) return
        val owned = ownedSettingCategories(pending)
        if (owned.isEmpty()) return
        val restored = PortableBackupFormat.decodeSettings(
            readPortableFileBytes(staged, PortableBackupFormat.MAX_SETTINGS_BYTES),
        )
        val current = settings.snapshot()
        val before = IosRestoreRecovery.settingsBeforePath(pending.directory)
        if (!ops.exists(before)) {
            ops.writeAtomically(before, PortableBackupFormat.encodeRestoreSettings(current))
        }
        settings.replaceAll(mergedRestoredSettings(current, restored, owned))
    }

    /** Puts the `settings-before` snapshot back when the apply failed after it was written. */
    suspend fun rollbackPendingSettings(
        pending: IosPendingRestore,
        ops: RestoreFileOps,
        settings: VaultSettingsStore,
    ) {
        val bytes = ops.readBytes(IosRestoreRecovery.settingsBeforePath(pending.directory)) ?: return
        settings.replaceAll(PortableBackupFormat.decodeSettings(bytes))
    }
}

private fun ensureDirectory(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
}

private fun deleteTree(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, null)
}

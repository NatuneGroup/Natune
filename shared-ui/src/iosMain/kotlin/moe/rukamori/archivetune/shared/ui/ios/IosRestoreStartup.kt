package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.data.backup.IosRestoreFileOps
import moe.rukamori.archivetune.shared.data.backup.PortableBackupFormat
import moe.rukamori.archivetune.shared.data.backup.RecoveryOutcome
import moe.rukamori.archivetune.shared.data.backup.RestoreFileOps
import moe.rukamori.archivetune.shared.data.backup.RestoreTarget
import moe.rukamori.archivetune.shared.data.backup.RestoreTransaction
import moe.rukamori.archivetune.shared.data.backup.mergedRestoredSettings
import moe.rukamori.archivetune.shared.data.backup.readPortableFileBytes
import moe.rukamori.archivetune.shared.data.persistence.BackupCategory
import moe.rukamori.archivetune.shared.domain.backup.BackupDataCategory
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore
import platform.Foundation.NSFileManager

/** The [RestoreTransaction] journal inside a staged restore, beside the `journal` format it replaces. */
internal const val RESTORE_TRANSACTION_FILE = "transaction"

/** The pre-apply vault snapshot a two-phase restore keeps until the staged settings merge succeeds. */
private const val RESTORE_SETTINGS_BEFORE = "settings-before"

/** The originals a retained commit sets aside, inside the staged restore so a failure can keep them. */
private const val RESTORE_PREVIOUS_DIRECTORY = "previous"

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
    /** Fail closed: the live database file may not be opened at all. */
    val corrupt: Boolean = false,
)

/**
 * The startup half of a staged restore on top of [RestoreTransaction]. [begin] runs before Room opens: it
 * finishes or undoes whatever an earlier process journaled, starts the retained commit for a fresh staged set,
 * and reports back which outcome the caller owes next. The vault apply then ends it: the settings merge runs
 * while the original files are still set aside, and only then are the backups and journal dropped.
 */
internal object IosRestoreStartup {
    /**
     * The throwaway database Room opens when the live file may not be touched. It sits next to the live file so
     * the session can still assemble and surface the restore-failed state; nothing it owns ever lands on the
     * real path.
     */
    const val CORRUPT_DATABASE_FILE = "restore-corrupt.db"

    fun transactionJournalPath(pendingDirectory: String): String = "$pendingDirectory/$RESTORE_TRANSACTION_FILE"

    private fun settingsBeforePath(pendingDirectory: String): String = "$pendingDirectory/$RESTORE_SETTINGS_BEFORE"

    private fun stagedSettingsPath(pendingDirectory: String): String =
        "$pendingDirectory/${PortableBackupFormat.SETTINGS}"

    /**
     * Resolves the staged restore of an earlier backup session while Room is still closed. A killed swap is
     * completed or rolled back from the journal, a fresh staged set starts its retained commit, and a pending
     * set the old journal format already began is left for the legacy helpers — never both.
     */
    fun begin(instanceDirectory: String, databaseFile: String, report: (String) -> Unit): IosRestoreStart {
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
        val outcome = try {
            RestoreTransaction.recover(ops, journalPath)
        } catch (failure: Exception) {
            report("restore journal unreadable: ${failure::class.simpleName}")
            RecoveryOutcome.CORRUPT
        }
        return when (outcome) {
            RecoveryOutcome.NOTHING ->
                startCommit(ops, pending, instanceDirectory, databaseFile, journalPath, report)
            RecoveryOutcome.PENDING_APPLY -> resumeApply(ops, pending, journalPath, report)
            RecoveryOutcome.COMMITTED -> {
                // A cleanup kill: the new files are live and only the staging directory is left to drop.
                runCatching { deleteTree(pending.directory) }
                IosRestoreStart()
            }
            RecoveryOutcome.ROLLED_BACK -> {
                report("interrupted restore rolled back")
                runCatching { pending.markFailed() }
                IosRestoreStart(pending = pending)
            }
            RecoveryOutcome.CORRUPT -> {
                report("restore corrupt; pending set kept for diagnosis")
                runCatching { pending.markFailed() }
                IosRestoreStart(pending = pending, corrupt = true)
            }
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

    /** A retained commit an earlier process left behind; the vault apply is owed again, then [finalize]. */
    private fun resumeApply(
        ops: RestoreFileOps,
        pending: IosPendingRestore,
        journalPath: String,
        report: (String) -> Unit,
    ): IosRestoreStart {
        val transaction = RestoreTransaction.resume(ops, journalPath)
        if (transaction == null) {
            report("restore journal lost mid-apply")
            runCatching { pending.markFailed() }
            return IosRestoreStart(pending = pending, corrupt = true)
        }
        // Staged settings whose owning categories are gone cannot be merged honestly; the all-or-nothing answer
        // is to put the files back rather than ship a half-applied restore.
        if (pending.hasStagedSettings && ownedSettingCategories(pending).isEmpty()) {
            report("restored settings lack their categories; rolling back")
            runCatching { transaction.rollbackCommitted() }
            runCatching { pending.markFailed() }
            return IosRestoreStart(pending = pending)
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
        val before = settingsBeforePath(pending.directory)
        if (!ops.exists(before)) {
            ops.writeAtomically(before, PortableBackupFormat.encodeSettings(current))
        }
        settings.replaceAll(mergedRestoredSettings(current, restored, owned))
    }

    /** Puts the `settings-before` snapshot back when the apply failed after it was written. */
    suspend fun rollbackPendingSettings(
        pending: IosPendingRestore,
        ops: RestoreFileOps,
        settings: VaultSettingsStore,
    ) {
        val bytes = ops.readBytes(settingsBeforePath(pending.directory)) ?: return
        settings.replaceAll(PortableBackupFormat.decodeSettings(bytes))
    }
}

private fun ensureDirectory(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
}

private fun deleteTree(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, null)
}

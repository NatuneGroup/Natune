package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.backup.IosRestoreFileOps
import moe.rukamori.archivetune.shared.data.persistence.PortableStateDatabase
import moe.rukamori.archivetune.shared.data.persistence.openNow
import moe.rukamori.archivetune.shared.data.persistence.openPortableStateDatabase
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore

/**
 * Opens the portable state database; when a staged library was swapped in, it is opened now so a file Room
 * refuses still lets the previous database come back instead of leaving a broken library. Null means the
 * vault rollback failed beside it: markers and the blocked flag stay, and the caller assembles recovery
 * mode instead of a mismatched pair.
 */
internal suspend fun openIosGraphDatabase(
    databasePath: String,
    databaseDirectory: String,
    databaseFile: String,
    start: IosRestoreStart,
    settings: VaultSettingsStore,
    report: (String) -> Unit,
): PortableStateDatabase? {
    val database = openPortableStateDatabase(databasePath)
    val swapped = start.databaseSwapped || start.pending?.databaseSwapped == true
    if (!swapped) return database
    try {
        database.openNow()
        return database
    } catch (cancelled: CancellationException) {
        database.close()
        throw cancelled
    } catch (failure: Exception) {
        report("restored library refused: ${failure::class.simpleName}")
        val ops = IosRestoreFileOps()
        var vaultOk = true
        withContext(NonCancellable) {
            // Room releases the files before the rollback replaces them; the abort marker is durable
            // first so a kill re-enters the joint undo instead of a fresh interpretation.
            runCatching { database.close() }
            start.pending?.let { pending ->
                if (start.transaction != null) {
                    val abortPersisted = runCatching {
                        ops.writeAtomically(
                            IosRestoreRecovery.abortMarkerPath(pending.directory),
                            byteArrayOf(1),
                        )
                    }.isSuccess
                    if (abortPersisted) {
                        // A vault merge that already landed goes back with the files it belongs to.
                        if (pending.hasSettingsBefore) {
                            vaultOk = runCatching {
                                IosRestoreStartup.rollbackPendingSettings(pending, ops, settings)
                            }.isSuccess
                            if (!vaultOk) report("restored settings rollback failed")
                        }
                        runCatching { start.transaction.rollbackCommitted() }
                    } else {
                        // No durable abort decision: a kill now would re-read a half-undone set
                        // as a fresh restore, so block and hold for recovery mode.
                        report("restore abort marker not written")
                        vaultOk = false
                    }
                } else {
                    pending.rollbackDatabase(databaseDirectory, databaseFile)
                }
            }
            if (vaultOk) {
                runCatching { start.pending?.markFailed() }
            } else {
                runCatching { IosRestoreRecovery.block(ops, databaseDirectory) }
            }
        }
        if (!vaultOk) return null
        return openPortableStateDatabase(databasePath)
    }
}

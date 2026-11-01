@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import moe.rukamori.archivetune.shared.data.backup.RestoreFileOps
import moe.rukamori.archivetune.shared.data.backup.RestoreResolution
import moe.rukamori.archivetune.shared.data.backup.RestoreResolutionEntry
import moe.rukamori.archivetune.shared.data.backup.RestoreResolutionFacts
import moe.rukamori.archivetune.shared.data.backup.RestoreResolutionStage
import moe.rukamori.archivetune.shared.data.backup.RestoreResolutionStep
import moe.rukamori.archivetune.shared.data.backup.RestoreTarget
import moe.rukamori.archivetune.shared.data.backup.decodeRestoreResolution
import moe.rukamori.archivetune.shared.data.backup.decideRestoreResolution
import moe.rukamori.archivetune.shared.data.backup.encodeRestoreResolution
import platform.Foundation.NSFileManager

/**
 * The file vocabulary and resolution actions of the restore recovery mode. Two markers carry the durable
 * decisions startup makes before it touches a file: `abort` inside the pending set says the joint apply is being
 * undone (files rolled back AND the vault restored from `settings-before`), and `restore-blocked` inside the
 * instance directory says the live database is unresolvable until the user picks a resolution. Both are written
 * atomically so a kill never leaves a half-written marker.
 *
 * The two user resolutions never delete: everything they move aside lands in a `restore-quarantine-<stamp>`
 * directory that survives for diagnosis.
 */
internal object IosRestoreRecovery {
    const val ABORT_FILE = "abort"
    const val SETTINGS_BEFORE_FILE = "settings-before"
    const val BLOCKED_FILE = "restore-blocked"

    private const val PENDING_DIRECTORY = "restore-pending"
    private const val FAILED_DIRECTORY = "restore-failed"
    private const val PREVIOUS_DIRECTORY = "previous"
    private const val QUARANTINE_PREFIX = "restore-quarantine-"
    private const val RESOLUTION_FILE = "restore-resolution"

    private val BLOCK_SENTINEL = byteArrayOf(1)

    fun abortMarkerPath(pendingDirectory: String): String = "$pendingDirectory/$ABORT_FILE"

    fun settingsBeforePath(pendingDirectory: String): String = "$pendingDirectory/$SETTINGS_BEFORE_FILE"

    fun blockedMarkerPath(instanceDirectory: String): String = "$instanceDirectory/$BLOCKED_FILE"

    /** The durable record of the user's chosen resolution while its file work runs. */
    fun resolutionJournalPath(instanceDirectory: String): String = "$instanceDirectory/$RESOLUTION_FILE"

    /** The instance-level marker every database open checks first; while set, only recovery mode runs. */
    fun isBlocked(instanceDirectory: String): Boolean = exists(blockedMarkerPath(instanceDirectory))

    /** Persists the blocked decision atomically; the file's presence is the whole marker. */
    fun block(ops: RestoreFileOps, instanceDirectory: String) {
        ops.writeAtomically(blockedMarkerPath(instanceDirectory), BLOCK_SENTINEL)
    }

    /** The resolution a kill left in flight, or null while none was chosen or the journal does not parse. */
    fun recordedResolution(ops: RestoreFileOps, instanceDirectory: String): RestoreResolutionEntry? =
        try {
            ops.readBytes(resolutionJournalPath(instanceDirectory))
                ?.decodeToString()
                ?.let(::decodeRestoreResolution)
        } catch (failure: Exception) {
            null
        }

    /** True when a kept set — failed or still-pending — holds the originals "restore previous" puts back. */
    fun hasRestorableBackup(instanceDirectory: String, databaseFile: String): Boolean =
        originalsDirectory(instanceDirectory, databaseFile) != null

    /** The set holding an original library: the failed set's `previous/` first, then the pending set's. */
    private fun originalsDirectory(instanceDirectory: String, databaseFile: String): String? =
        listOf(FAILED_DIRECTORY, PENDING_DIRECTORY)
            .map { "$instanceDirectory/$it/$PREVIOUS_DIRECTORY" }
            .firstOrNull { exists("$it/$databaseFile") }

    /**
     * Finishes a journaled resolution on a cold start, before Room opens. True means the markers cleared and
     * startup may continue; false — no journal, an unfinished pass, or a failure — leaves the block standing.
     */
    fun resumeResolution(
        ops: RestoreFileOps,
        instanceDirectory: String,
        databaseFile: String,
        nowMillis: Long,
    ): Boolean {
        val recorded = recordedResolution(ops, instanceDirectory) ?: return false
        return resolveRestore(ops, instanceDirectory, databaseFile, recorded.resolution, nowMillis)
    }

    /**
     * Runs a blocked restore's resolution to its end: the choice is journaled before a file moves, the journal
     * advances between passes, and every pass is idempotent — a kill anywhere resumes the same choice in the
     * same phase instead of re-prompting or half-applying the other action. False leaves the blocked marker,
     * the journal and the recovery surface standing for the next start.
     */
    fun resolveRestore(
        ops: RestoreFileOps,
        instanceDirectory: String,
        databaseFile: String,
        resolution: RestoreResolution,
        nowMillis: Long,
    ): Boolean {
        val journalPath = resolutionJournalPath(instanceDirectory)
        val recorded = recordedResolution(ops, instanceDirectory)
        val step = decideRestoreResolution(
            RestoreResolutionFacts(
                recorded = recorded,
                chosen = resolution,
                originalsInPending = exists("$instanceDirectory/$PENDING_DIRECTORY/$PREVIOUS_DIRECTORY/$databaseFile"),
                originalsInFailed = exists("$instanceDirectory/$FAILED_DIRECTORY/$PREVIOUS_DIRECTORY/$databaseFile"),
            ),
        )
        if (step == RestoreResolutionStep.UNRESOLVABLE) return false
        val chosen = recorded?.resolution ?: resolution
        var stage = recorded?.stage
        try {
            if (stage == null) {
                writeResolution(ops, instanceDirectory, RestoreResolutionEntry(chosen, RestoreResolutionStage.QUARANTINING))
                stage = RestoreResolutionStage.QUARANTINING
            }
            when (chosen) {
                RestoreResolution.RESTORE_PREVIOUS -> {
                    if (stage == RestoreResolutionStage.QUARANTINING) {
                        quarantineLive(instanceDirectory, databaseFile, freshQuarantine(instanceDirectory, nowMillis))
                        writeResolution(ops, instanceDirectory, RestoreResolutionEntry(chosen, RestoreResolutionStage.RESTORING))
                        stage = RestoreResolutionStage.RESTORING
                    }
                    if (stage == RestoreResolutionStage.RESTORING) {
                        restoreOriginals(instanceDirectory, databaseFile, originalsDirectory(instanceDirectory, databaseFile))
                        writeResolution(ops, instanceDirectory, RestoreResolutionEntry(chosen, RestoreResolutionStage.CLEANUP))
                        stage = RestoreResolutionStage.CLEANUP
                    }
                }
                RestoreResolution.START_EMPTY -> {
                    if (stage != RestoreResolutionStage.CLEANUP) {
                        val quarantine = freshQuarantine(instanceDirectory, nowMillis)
                        quarantineLive(instanceDirectory, databaseFile, quarantine)
                        quarantineSets(instanceDirectory, quarantine)
                        writeResolution(ops, instanceDirectory, RestoreResolutionEntry(chosen, RestoreResolutionStage.CLEANUP))
                        stage = RestoreResolutionStage.CLEANUP
                    }
                }
            }
            if (stage != RestoreResolutionStage.CLEANUP) return false
            // Every move is done and the cleanup record is durable; the markers drop last.
            deleteItem(blockedMarkerPath(instanceDirectory))
            deleteItem(journalPath)
            return true
        } catch (failure: Exception) {
            return false
        }
    }

    private fun writeResolution(ops: RestoreFileOps, instanceDirectory: String, entry: RestoreResolutionEntry) {
        ops.writeAtomically(
            resolutionJournalPath(instanceDirectory),
            encodeRestoreResolution(entry).encodeToByteArray(),
        )
    }

    /** Every live database file into the quarantine; a suffix that never existed is simply skipped. */
    private fun quarantineLive(instanceDirectory: String, databaseFile: String, quarantine: String) {
        RestoreTarget.DATABASE_SIDECARS.forEach { suffix ->
            val live = "$instanceDirectory/$databaseFile$suffix"
            if (exists(live)) moveItem(live, "$quarantine/$databaseFile$suffix")
        }
    }

    /** The pending and failed sets as a whole into the quarantine — the empty start keeps none of them. */
    private fun quarantineSets(instanceDirectory: String, quarantine: String) {
        listOf(PENDING_DIRECTORY, FAILED_DIRECTORY).forEach { name ->
            val path = "$instanceDirectory/$name"
            if (exists(path)) moveItem(path, "$quarantine/$name")
        }
    }

    /** Originals back over the empty live slots; already-restored suffixes (backup gone) are left alone. */
    private fun restoreOriginals(instanceDirectory: String, databaseFile: String, previous: String?) {
        if (previous == null) return
        RestoreTarget.DATABASE_SIDECARS.forEach { suffix ->
            val backed = "$previous/$databaseFile$suffix"
            if (exists(backed)) moveItem(backed, "$instanceDirectory/$databaseFile$suffix")
        }
    }

    private fun freshQuarantine(instanceDirectory: String, nowMillis: Long): String {
        val path = "$instanceDirectory/$QUARANTINE_PREFIX$nowMillis"
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
        return path
    }

    private fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    private fun moveItem(from: String, to: String) {
        check(NSFileManager.defaultManager.moveItemAtPath(from, to, null)) { "File move failed: $from" }
    }

    private fun deleteItem(path: String) {
        if (exists(path)) NSFileManager.defaultManager.removeItemAtPath(path, null)
    }
}

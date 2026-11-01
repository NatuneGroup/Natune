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
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import platform.Foundation.NSFileManager

/**
 * The file vocabulary and resolution actions of the restore recovery mode. Two markers carry the durable
 * decisions startup makes before it touches a file: `abort` inside the pending set says the joint apply is being
 * undone (files rolled back AND the vault restored from `settings-before`), and `restore-blocked` inside the
 * instance directory says the live database is unresolvable until the user picks a resolution. Both are written
 * atomically so a kill never leaves a half-written marker, and the blocked marker's content doubles as the
 * block's id: a resolution journal that survives from an earlier block can never resolve a different one.
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

    fun abortMarkerPath(pendingDirectory: String): String = "$pendingDirectory/$ABORT_FILE"

    fun settingsBeforePath(pendingDirectory: String): String = "$pendingDirectory/$SETTINGS_BEFORE_FILE"

    fun blockedMarkerPath(instanceDirectory: String): String = "$instanceDirectory/$BLOCKED_FILE"

    /** The durable record of the user's chosen resolution while its file work runs. */
    fun resolutionJournalPath(instanceDirectory: String): String = "$instanceDirectory/$RESOLUTION_FILE"

    /** The instance-level marker every database open checks first; while set, only recovery mode runs. */
    fun isBlocked(instanceDirectory: String): Boolean = exists(blockedMarkerPath(instanceDirectory))

    /**
     * Persists the blocked decision atomically. The file's presence is the marker and its content — the write
     * instant — is the block's id, which every resolution journal records to bind itself to this block.
     */
    fun block(ops: RestoreFileOps, instanceDirectory: String) {
        ops.writeAtomically(blockedMarkerPath(instanceDirectory), iosNowMillis().toString().encodeToByteArray())
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
     * Finishes a journaled resolution on a cold start, before Room opens — or, when its block is already gone,
     * settles just the journal's own tail. True means startup may continue. False — no journal, a journal bound
     * to a different block (stale: left untouched so it can never act on the new block), an unfinished pass or
     * a failure — leaves whatever block stands for recovery mode or the next start.
     */
    fun resumeResolution(
        ops: RestoreFileOps,
        instanceDirectory: String,
        databaseFile: String,
        nowMillis: Long,
    ): Boolean {
        val entry = recordedResolution(ops, instanceDirectory) ?: return false
        if (!isBlocked(instanceDirectory)) {
            // The block this journal served is gone: a finished resolution owes only its own delete; a
            // half-finished one is stale state and must never move files against the new state.
            if (entry.stage != RestoreResolutionStage.CLEANUP) return false
            return try {
                deleteItem(resolutionJournalPath(instanceDirectory))
                true
            } catch (failure: Exception) {
                false
            }
        }
        if (entry.blockToken == null || entry.blockToken != blockedMarkerToken(ops, instanceDirectory)) {
            return false
        }
        return resolveRestore(ops, instanceDirectory, databaseFile, entry.resolution, nowMillis)
    }

    /**
     * Runs a blocked restore's resolution to its end: the choice is journaled — bound to the blocked marker's
     * content and, for restore-previous, pinned to the originals directory and the exact file names it must
     * leave live — before a file moves; the journal advances between passes and every pass is idempotent, so a
     * kill anywhere resumes the same choice in the same phase instead of re-prompting or half-applying the
     * other action. False leaves the blocked marker, the journal and the recovery surface standing.
     */
    fun resolveRestore(
        ops: RestoreFileOps,
        instanceDirectory: String,
        databaseFile: String,
        resolution: RestoreResolution,
        nowMillis: Long,
    ): Boolean {
        val journalPath = resolutionJournalPath(instanceDirectory)
        val blockToken = blockedMarkerToken(ops, instanceDirectory)
        // A journal is honored only while it belongs to the block on disk; a stale one is another block's
        // record and loses to the fresh pick entirely.
        val recorded = recordedResolution(ops, instanceDirectory)?.takeIf {
            it.blockToken != null && it.blockToken == blockToken
        }
        val step = decideRestoreResolution(
            RestoreResolutionFacts(
                recorded = recorded,
                chosen = resolution,
                journalBound = recorded != null,
                originalsInPending = exists("$instanceDirectory/$PENDING_DIRECTORY/$PREVIOUS_DIRECTORY/$databaseFile"),
                originalsInFailed = exists("$instanceDirectory/$FAILED_DIRECTORY/$PREVIOUS_DIRECTORY/$databaseFile"),
            ),
        )
        val chosen = when (step) {
            RestoreResolutionStep.RESOLVE_PREVIOUS -> RestoreResolution.RESTORE_PREVIOUS
            RestoreResolutionStep.RESOLVE_EMPTY -> RestoreResolution.START_EMPTY
            RestoreResolutionStep.UNRESOLVABLE -> return false
        }
        // A pick different from the journaled action starts a fresh record at the first pass.
        var entry = recorded?.takeIf { it.resolution == chosen }
        var stage = entry?.stage
        fun advance(current: RestoreResolutionEntry, to: RestoreResolutionStage): RestoreResolutionEntry {
            val next = current.copy(stage = to)
            writeResolution(ops, instanceDirectory, next)
            return next
        }
        try {
            if (stage == null) {
                val fresh = freshResolutionEntry(chosen, instanceDirectory, databaseFile, blockToken)
                writeResolution(ops, instanceDirectory, fresh)
                entry = fresh
                stage = RestoreResolutionStage.QUARANTINING
            }
            when (chosen) {
                RestoreResolution.RESTORE_PREVIOUS -> {
                    if (stage == RestoreResolutionStage.QUARANTINING) {
                        quarantineLive(instanceDirectory, databaseFile, freshQuarantine(instanceDirectory, nowMillis))
                        entry = advance(entry ?: return false, RestoreResolutionStage.RESTORING)
                        stage = RestoreResolutionStage.RESTORING
                    }
                    if (stage == RestoreResolutionStage.RESTORING) {
                        if (!restoreRecordedOriginals(entry ?: return false, instanceDirectory)) return false
                        entry = advance(entry ?: return false, RestoreResolutionStage.RETIRING)
                        stage = RestoreResolutionStage.RETIRING
                    }
                    if (stage == RestoreResolutionStage.RETIRING) {
                        retirePending(instanceDirectory, freshQuarantine(instanceDirectory, nowMillis))
                        entry = advance(entry ?: return false, RestoreResolutionStage.CLEANUP)
                        stage = RestoreResolutionStage.CLEANUP
                    }
                }
                RestoreResolution.START_EMPTY -> {
                    if (stage != RestoreResolutionStage.CLEANUP) {
                        val quarantine = freshQuarantine(instanceDirectory, nowMillis)
                        quarantineLive(instanceDirectory, databaseFile, quarantine)
                        quarantineSets(instanceDirectory, quarantine)
                        entry = advance(entry ?: return false, RestoreResolutionStage.CLEANUP)
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

    /** The content the block marker was written with — the id a resolution journal binds to. */
    private fun blockedMarkerToken(ops: RestoreFileOps, instanceDirectory: String): String? =
        try {
            ops.readBytes(blockedMarkerPath(instanceDirectory))?.decodeToString()
        } catch (failure: Exception) {
            null
        }

    /**
     * The record for a resolution just chosen: bound to the block on disk, and for restore-previous pinned to
     * the originals directory and the exact file names that must end up live — so a half-consumed originals
     * set can never read as "nothing to put back" on resume.
     */
    private fun freshResolutionEntry(
        chosen: RestoreResolution,
        instanceDirectory: String,
        databaseFile: String,
        blockToken: String?,
    ): RestoreResolutionEntry {
        val source = if (chosen == RestoreResolution.RESTORE_PREVIOUS) {
            originalsDirectory(instanceDirectory, databaseFile)
        } else {
            null
        }
        val files = source?.let { dir ->
            RestoreTarget.DATABASE_SIDECARS.map { "$databaseFile$it" }.filter { exists("$dir/$it") }
        }.orEmpty()
        return RestoreResolutionEntry(chosen, RestoreResolutionStage.QUARANTINING, blockToken, source, files)
    }

    /**
     * Promotes every recorded original still waiting in the pinned source, then proves each expected file sits
     * at its live slot. False — files missing on both sides with nothing to promote — is unresolvable, not
     * success: the journal stays at RESTORING and the block stands.
     */
    private fun restoreRecordedOriginals(entry: RestoreResolutionEntry, instanceDirectory: String): Boolean {
        val source = entry.source ?: return false
        entry.files.forEach { name ->
            val backed = "$source/$name"
            if (exists(backed)) moveItem(backed, "$instanceDirectory/$name")
        }
        return entry.files.all { exists("$instanceDirectory/$it") }
    }

    /** The resolved pending transaction set into quarantine so the next startup does not replay it. */
    private fun retirePending(instanceDirectory: String, quarantine: String) {
        val path = "$instanceDirectory/$PENDING_DIRECTORY"
        if (exists(path)) moveItem(path, "$quarantine/$PENDING_DIRECTORY")
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

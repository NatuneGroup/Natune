@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import moe.rukamori.archivetune.shared.data.backup.RestoreFileOps
import moe.rukamori.archivetune.shared.data.backup.RestoreTarget
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

    private val BLOCK_SENTINEL = byteArrayOf(1)

    fun abortMarkerPath(pendingDirectory: String): String = "$pendingDirectory/$ABORT_FILE"

    fun settingsBeforePath(pendingDirectory: String): String = "$pendingDirectory/$SETTINGS_BEFORE_FILE"

    fun blockedMarkerPath(instanceDirectory: String): String = "$instanceDirectory/$BLOCKED_FILE"

    /** The instance-level marker every database open checks first; while set, only recovery mode runs. */
    fun isBlocked(instanceDirectory: String): Boolean = exists(blockedMarkerPath(instanceDirectory))

    /** Persists the blocked decision atomically; the file's presence is the whole marker. */
    fun block(ops: RestoreFileOps, instanceDirectory: String) {
        ops.writeAtomically(blockedMarkerPath(instanceDirectory), BLOCK_SENTINEL)
    }

    /** True when a failed set still keeps the originals the "restore previous" action would put back. */
    fun hasRestorableBackup(instanceDirectory: String, databaseFile: String): Boolean =
        exists("$instanceDirectory/$FAILED_DIRECTORY/$PREVIOUS_DIRECTORY/$databaseFile")

    /**
     * Puts the originals a failed set kept under `restore-failed/previous/` back over the live slots, after
     * quarantining the files they replace. The blocked marker clears only once every move landed, so a failure
     * part-way leaves recovery mode engaged and the quarantine holding what was moved.
     */
    fun restorePreviousLibrary(instanceDirectory: String, databaseFile: String, nowMillis: Long) {
        val previous = "$instanceDirectory/$FAILED_DIRECTORY/$PREVIOUS_DIRECTORY"
        require(exists("$previous/$databaseFile")) { "No previous library to restore" }
        val quarantine = freshQuarantine(instanceDirectory, nowMillis)
        RestoreTarget.DATABASE_SIDECARS.forEach { suffix ->
            val live = "$instanceDirectory/$databaseFile$suffix"
            val backed = "$previous/$databaseFile$suffix"
            if (exists(live)) moveItem(live, "$quarantine/$databaseFile$suffix")
            if (exists(backed)) moveItem(backed, live)
        }
        deleteItem(blockedMarkerPath(instanceDirectory))
    }

    /**
     * Gives the user a clean instance without deleting anything: the live database set, the pending set and the
     * failed set all move into a dated quarantine, then the blocked marker clears. The next open builds a fresh
     * database beside an empty vault-restored slate.
     */
    fun startEmptyLibrary(instanceDirectory: String, databaseFile: String, nowMillis: Long) {
        val quarantine = freshQuarantine(instanceDirectory, nowMillis)
        RestoreTarget.DATABASE_SIDECARS.forEach { suffix ->
            val live = "$instanceDirectory/$databaseFile$suffix"
            if (exists(live)) moveItem(live, "$quarantine/$databaseFile$suffix")
        }
        listOf(PENDING_DIRECTORY, FAILED_DIRECTORY).forEach { name ->
            val path = "$instanceDirectory/$name"
            if (exists(path)) moveItem(path, "$quarantine/$name")
        }
        deleteItem(blockedMarkerPath(instanceDirectory))
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

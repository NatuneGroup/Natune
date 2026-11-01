package moe.rukamori.archivetune.shared.data.backup

/**
 * The file primitives a [RestoreTransaction] needs, each one small enough to be atomic on a real filesystem.
 * Implementations are synchronous by contract: a restore swap runs while the owning database and settings store
 * are closed, so nothing suspends and every call either finishes or throws.
 *
 * Atomicity obligations an implementation must keep for the recovery machine to hold:
 *
 * - [atomicRename] must replace the destination in one atomic step (POSIX `rename`), never a copy-then-delete.
 * - [writeAtomically] must write a sibling temporary file, fsync it, then atomically rename it over the target,
 *   so a crash never leaves a torn file.
 * - [delete] removes a single file.
 */
interface RestoreFileOps {
    /** True when a file exists at [path]. */
    fun exists(path: String): Boolean

    /** Moves [from] to [to], atomically replacing [to] when it exists. */
    fun atomicRename(from: String, to: String)

    /** Removes the file at [path]. */
    fun delete(path: String)

    /** Writes [bytes] to [path] through a fsynced temporary file and an atomic rename. */
    fun writeAtomically(path: String, bytes: ByteArray)

    /** The bytes at [path], or null when the file is missing. */
    fun readBytes(path: String): ByteArray?

    /** The paths of files directly under [directory]; empty when it is missing or empty. */
    fun list(directory: String): List<String>
}

/**
 * One file the restore replaces, and the two paths the swap uses around it: [stagedPath] holds the incoming file
 * before the commit starts (written by the caller, e.g. an unpacked archive), and [backupPath] receives the live
 * file for the duration of the commit so a rollback can restore it.
 *
 * [stagedRequired] marks files a valid stage must always produce (the database itself, the settings file).
 * Optional sidecars such as `-wal`/`-shm` are staged only when the backup carried them, so they are not required.
 */
data class RestoreTarget(
    val livePath: String,
    val stagedPath: String,
    val backupPath: String,
    val stagedRequired: Boolean = true,
) {
    companion object {
        /** The suffixes Room may keep next to the database file; `-wal`/`-shm` come and go with checkpoints. */
        val DATABASE_SIDECARS = listOf("", "-wal", "-shm")

        /** The database file plus its `-wal` and `-shm` sidecars; only the database itself is a required stage. */
        fun database(livePath: String, stagedPath: String, backupPath: String): List<RestoreTarget> =
            DATABASE_SIDECARS.mapIndexed { index, suffix ->
                RestoreTarget("$livePath$suffix", "$stagedPath$suffix", "$backupPath$suffix", stagedRequired = index == 0)
            }
    }
}

/** The durable step a [RestoreTransaction] has reached, read back by [RestoreTransaction.recover] after a kill. */
enum class RestorePhase {
    /** All staged files were snapshotted and the journal written; no live file has been touched yet. */
    STAGED,

    /** Every live file that existed was moved to its backup path; staged installs may be in progress. */
    ORIGINALS_MOVED,

    /**
     * Every staged file is live and the backups were kept on purpose: the caller still owes a non-file apply
     * (the settings vault) for the same all-or-nothing restore, and either finalizes or rolls the files back.
     */
    APPLYING,

    /** Every staged file is live; only backup/journal cleanup remains. */
    COMMITTED,

    /** The swap was undone or never touched the live files; only cleanup remains. */
    ROLLED_BACK,
}

/** What [RestoreTransaction.recover] found and did at process start. */
enum class RecoveryOutcome {
    /** No journal exists: no restore was in flight. */
    NOTHING,

    /** The live files hold the restored (new) contents and the swap was fully cleaned up. */
    COMMITTED,

    /**
     * The live files hold the restored (new) contents and the backups were kept on purpose: the caller still owes
     * the non-file apply (the settings vault) for the same all-or-nothing restore, then calls
     * [RestoreTransaction.finalize] on success or [RestoreTransaction.rollbackCommitted] on failure, through
     * [RestoreTransaction.resume].
     */
    PENDING_APPLY,

    /** The live files hold exactly the pre-restore (old) contents and the swap was fully undone. */
    ROLLED_BACK,

    /**
     * Live data is missing and cannot be rebuilt from either side: a target whose original was moved aside lost
     * both its live file and its backup, or the journal itself is unreadable. The caller must NOT open Room or
     * apply the settings file; the journal is left in place so a later attempt can retry or report.
     */
    CORRUPT,
}

/**
 * An all-or-nothing file swap for a restore: the Room database (with `-wal`/`-shm` sidecars) and the settings
 * file move together, so the process never sees a new database beside old settings.
 *
 * Commit order, each boundary durable before the next begins:
 *
 * 1. Journal `STAGED` once every staged file exists (a missing required stage aborts before journaling).
 * 2. Move every live file that exists to its backup path; journal `ORIGINALS_MOVED`.
 * 3. Move every staged file into the live slot; journal `COMMITTED` (or `APPLYING` when retaining backups).
 * 4. Delete the backups and the journal.
 *
 * Any failure before that last journal write runs [rollback]: every backup returns to its live path, staged
 * leftovers and live files at targets that had no original are removed, the journal is marked `ROLLED_BACK` and
 * deleted, then the original failure is rethrown. A rollback step that itself fails leaves the journal at
 * `ORIGINALS_MOVED` so [recover] retries instead of startup opening a half-restored set. A process killed
 * mid-commit leaves the journal behind for [recover] either way.
 *
 * Two-phase commit: [commit]`(retainBackups = true)` leaves the journal at `APPLYING` with the new files live and
 * every backup intact. That is how the database and the settings vault move together: the caller applies the
 * non-file part, then [finalize] deletes the backups, or [rollbackCommitted] puts them back when the apply
 * fails. [recover] reports the retained commit as [RecoveryOutcome.PENDING_APPLY] so a fresh process can re-run
 * the apply; a rollback that was killed mid-way is instead journaled `ORIGINALS_MOVED`, where [recover] simply
 * finishes it.
 *
 * Caller contract: the Room database and the settings store behind [RestoreTarget.livePath] must be CLOSED before
 * [commit], and must stay closed until [recover] reports a clean outcome at the next start. On [RecoveryOutcome.CORRUPT]
 * the caller must not open Room. Run [recover] before staging a new restore; [commit] refuses to overwrite a
 * journal an earlier transaction left behind.
 */
class RestoreTransaction(
    val ops: RestoreFileOps,
    val journalPath: String,
    val targets: List<RestoreTarget>,
) {
    /**
     * Runs the staged swap to [RestorePhase.COMMITTED] and removes the backups and journal — or, with
     * [retainBackups], stops at [RestorePhase.APPLYING] and keeps both until [finalize] or [rollbackCommitted]
     * is called. Throws the first failure after rolling the live files back to their pre-commit contents — or,
     * when a rollback step fails too, after journaling the remaining rollback for [recover] to retry.
     */
    fun commit(retainBackups: Boolean = false) {
        check(!ops.exists(journalPath)) { "Unrecovered restore journal at $journalPath; run recover() first" }
        val entries = targets.map { target ->
            val hasStaged = ops.exists(target.stagedPath)
            if (target.stagedRequired && !hasStaged) {
                throw IllegalStateException("Staged file missing: ${target.stagedPath}")
            }
            RestorePlanEntry(
                livePath = target.livePath,
                stagedPath = target.stagedPath,
                backupPath = target.backupPath,
                hadLive = ops.exists(target.livePath),
                hasStaged = hasStaged,
            )
        }
        writeJournal(ops, journalPath, RestorePhase.STAGED, entries)
        try {
            entries.forEach { entry ->
                if (ops.exists(entry.livePath)) ops.atomicRename(entry.livePath, entry.backupPath)
            }
            writeJournal(ops, journalPath, RestorePhase.ORIGINALS_MOVED, entries)
            entries.forEach { entry ->
                if (ops.exists(entry.stagedPath)) ops.atomicRename(entry.stagedPath, entry.livePath)
            }
            writeJournal(
                ops, journalPath,
                if (retainBackups) RestorePhase.APPLYING else RestorePhase.COMMITTED,
                entries,
            )
        } catch (failure: Exception) {
            rollback(entries, failure)
        }
        if (retainBackups) return
        // Past COMMITTED the outcome is decided; a cleanup failure keeps the journal so recover() finishes it.
        entries.forEach { entry -> if (ops.exists(entry.backupPath)) ops.delete(entry.backupPath) }
        entries.forEach { entry -> if (ops.exists(entry.stagedPath)) ops.delete(entry.stagedPath) }
        if (ops.exists(journalPath)) ops.delete(journalPath)
    }

    /**
     * Deletes the retained backups, staged leftovers and the journal once the non-file apply succeeded.
     * Idempotent: a kill mid-way leaves the `COMMITTED` journal, and re-running either this or [recover]
     * finishes the deletes.
     */
    fun finalize() {
        val journal = readJournal() ?: return
        check(journal.phase == RestorePhase.APPLYING || journal.phase == RestorePhase.COMMITTED) {
            "finalize() requires a committed restore; journal phase is ${journal.phase}"
        }
        if (journal.phase == RestorePhase.APPLYING) {
            writeJournal(ops, journalPath, RestorePhase.COMMITTED, journal.targets)
        }
        journal.targets.forEach { entry -> if (ops.exists(entry.backupPath)) ops.delete(entry.backupPath) }
        journal.targets.forEach { entry -> if (ops.exists(entry.stagedPath)) ops.delete(entry.stagedPath) }
        if (ops.exists(journalPath)) ops.delete(journalPath)
    }

    /**
     * Puts every retained backup back over the staged installs — the rollback a failed non-file apply owes the
     * user — then deletes staged leftovers and the journal. Idempotent and kill-safe: it re-journals
     * `ORIGINALS_MOVED` before touching a file, so a kill mid-rollback leaves a journal [recover] completes the
     * same rollback from, and a target already restored — live present, backup gone — counts as done rather
     * than missing. Throws before moving anything only when an original is gone from both places.
     */
    fun rollbackCommitted() {
        val journal = readJournal() ?: return
        when (journal.phase) {
            RestorePhase.APPLYING, RestorePhase.COMMITTED -> {
                journal.targets.forEach { entry ->
                    check(!entry.hadLive || ops.exists(entry.livePath) || ops.exists(entry.backupPath)) {
                        "Cannot roll back a committed restore; original is lost: ${entry.livePath}"
                    }
                }
                writeJournal(ops, journalPath, RestorePhase.ORIGINALS_MOVED, journal.targets)
                restoreOriginals(ops, journal.targets)
                writeJournal(ops, journalPath, RestorePhase.ROLLED_BACK, journal.targets)
            }
            RestorePhase.ROLLED_BACK -> Unit
            else -> throw IllegalStateException(
                "rollbackCommitted() requires a committed restore; journal phase is ${journal.phase}",
            )
        }
        journal.targets.forEach { entry -> if (ops.exists(entry.stagedPath)) ops.delete(entry.stagedPath) }
        if (ops.exists(journalPath)) ops.delete(journalPath)
    }

    /** The decoded journal behind [journalPath], or null when the file is gone; a malformed journal throws. */
    private fun readJournal(): RestorePhaseJournal? =
        ops.readBytes(journalPath)?.decodeToString()?.let(RestorePhaseJournal::decode)

    /**
     * Undoes the swap [commit] began after [cause] stopped it: the journal is first advanced to ORIGINALS_MOVED
     * so a kill mid-rollback re-enters a phase that tolerates already-restored files, then every original is put
     * back and installs at targets that had none are removed. A failing step is collected, not swallowed: the
     * journal is left at ORIGINALS_MOVED so the next [recover] retries the same work instead of a startup
     * opening a half-restored database. The commit's own failure always propagates.
     */
    private fun rollback(entries: List<RestorePlanEntry>, cause: Exception): Nothing {
        try {
            writeJournal(ops, journalPath, RestorePhase.ORIGINALS_MOVED, entries)
        } catch (_: Exception) {
        }
        var rollbackFailure: Exception? = null
        entries.forEach { entry ->
            try {
                if (ops.exists(entry.backupPath)) {
                    ops.atomicRename(entry.backupPath, entry.livePath)
                } else if (!entry.hadLive && ops.exists(entry.livePath)) {
                    ops.delete(entry.livePath)
                }
            } catch (failure: Exception) {
                if (rollbackFailure == null) rollbackFailure = failure
            }
        }
        entries.forEach { entry ->
            try {
                if (ops.exists(entry.stagedPath)) ops.delete(entry.stagedPath)
            } catch (failure: Exception) {
                if (rollbackFailure == null) rollbackFailure = failure
            }
        }
        if (rollbackFailure == null) {
            try {
                writeJournal(ops, journalPath, RestorePhase.ROLLED_BACK, entries)
            } catch (_: Exception) {
            }
            try {
                if (ops.exists(journalPath)) ops.delete(journalPath)
            } catch (_: Exception) {
            }
        }
        throw cause
    }

    companion object {
        /**
         * Finishes or undoes a swap a killed process left behind; safe to call again after a second kill because
         * every step is gated on what the files, not memory, say. Reads the targets out of the journal at
         * [journalPath], so a recovering process needs nothing but the ops and the path.
         *
         * The same contract as [commit] applies: run before Room and the settings store open, and treat
         * [RecoveryOutcome.CORRUPT] as "do not open". Fail closed: a target that should have a live file but has
         * neither it nor a backup yields CORRUPT rather than a silently half-restored database.
         */
        fun recover(ops: RestoreFileOps, journalPath: String): RecoveryOutcome {
            val text = ops.readBytes(journalPath)?.decodeToString() ?: return RecoveryOutcome.NOTHING
            val journal = try {
                RestorePhaseJournal.decode(text)
            } catch (bad: IllegalArgumentException) {
                return RecoveryOutcome.CORRUPT
            }
            return when (journal.phase) {
                RestorePhase.COMMITTED -> committedRecovery(ops, journalPath, journal.targets)
                RestorePhase.APPLYING -> applyingRecovery(ops, journalPath, journal.targets)
                RestorePhase.STAGED, RestorePhase.ORIGINALS_MOVED, RestorePhase.ROLLED_BACK ->
                    rollbackRecovery(ops, journalPath, journal.targets)
            }
        }

        /**
         * A handle on the journal [recover] reported, so [finalize] or [rollbackCommitted] can run in a process
         * that never saw the commit. Null when the journal is missing or unreadable; the returned transaction's
         * methods re-read the journal, so the phase is rechecked at call time.
         */
        fun resume(ops: RestoreFileOps, journalPath: String): RestoreTransaction? {
            val journal = ops.readBytes(journalPath)?.decodeToString()?.let { text ->
                try {
                    RestorePhaseJournal.decode(text)
                } catch (bad: IllegalArgumentException) {
                    null
                }
            } ?: return null
            return RestoreTransaction(
                ops,
                journalPath,
                journal.targets.map { entry ->
                    RestoreTarget(entry.livePath, entry.stagedPath, entry.backupPath, stagedRequired = false)
                },
            )
        }

        /**
         * True when a [recover] call on [journalPath] can only end by undoing the swap: a journal pinned before
         * `COMMITTED` is rolled back unconditionally, and a retained commit whose staged file can no longer
         * reach its live slot takes the same path. Reads only — nothing is moved or deleted — so a caller can
         * persist its own undo decision before [recover] starts moving files. False for a missing or unreadable
         * journal: those are the `NOTHING` and `CORRUPT` ends, which do not undo files.
         */
        fun recoverEndsInRollback(ops: RestoreFileOps, journalPath: String): Boolean {
            val journal = try {
                ops.readBytes(journalPath)?.decodeToString()?.let(RestorePhaseJournal::decode)
            } catch (failure: Exception) {
                null
            } ?: return false
            return when (journal.phase) {
                RestorePhase.COMMITTED, RestorePhase.APPLYING ->
                    journal.targets.any { entry ->
                        entry.hasStaged && !ops.exists(entry.livePath) && !ops.exists(entry.stagedPath)
                    }
                RestorePhase.STAGED, RestorePhase.ORIGINALS_MOVED, RestorePhase.ROLLED_BACK -> true
            }
        }

        /**
         * Returns every live file to its original: each backup that exists goes back (overwriting a staged install
         * that already landed), an install at a target that never had a live file is removed, and a backup that is
         * already restored is skipped. Deleting a staged leftover or re-entering after a kill converges on the
         * same result.
         */
        private fun rollbackRecovery(
            ops: RestoreFileOps,
            journalPath: String,
            entries: List<RestorePlanEntry>,
        ): RecoveryOutcome {
            // The rollback phase is journaled BEFORE a file moves: a kill mid-rollback lands back here, where a
            // target whose original is already restored (live present, backup gone) is done, never lost.
            writeJournal(ops, journalPath, RestorePhase.ORIGINALS_MOVED, entries)
            restoreOriginals(ops, entries)
            val lostOriginal = entries.any { entry -> entry.hadLive && !ops.exists(entry.livePath) }
            if (lostOriginal) return RecoveryOutcome.CORRUPT
            entries.forEach { entry -> if (ops.exists(entry.stagedPath)) ops.delete(entry.stagedPath) }
            writeJournal(ops, journalPath, RestorePhase.ROLLED_BACK, entries)
            if (ops.exists(journalPath)) ops.delete(journalPath)
            return RecoveryOutcome.ROLLED_BACK
        }

        /**
         * A retained commit left at `APPLYING`: every staged file is live, so the caller is handed
         * [RecoveryOutcome.PENDING_APPLY] to finish the non-file apply. Consistency only checks that staged
         * files landed — a sidecar Room created at runtime is not part of the swap and never by itself means
         * rollback. A staged file that went missing gets the same rollback as a broken COMMITTED.
         */
        private fun applyingRecovery(
            ops: RestoreFileOps,
            journalPath: String,
            entries: List<RestorePlanEntry>,
        ): RecoveryOutcome {
            entries.forEach { entry ->
                if (entry.hasStaged && !ops.exists(entry.livePath) && ops.exists(entry.stagedPath)) {
                    ops.atomicRename(entry.stagedPath, entry.livePath)
                }
            }
            val consistent = entries.all { entry -> !entry.hasStaged || ops.exists(entry.livePath) }
            if (consistent) return RecoveryOutcome.PENDING_APPLY
            return rollbackRecovery(ops, journalPath, entries)
        }

        /**
         * Finishes a swap whose staged installs all committed: any live file still missing takes its staged copy,
         * then backups, staged leftovers and the journal are deleted. Consistency only checks that staged files
         * landed — a sidecar Room created at runtime, or any other file a target never staged, is ignored here
         * and left alone rather than forcing a rollback or CORRUPT over legitimate runtime files. A staged file
         * missing with nothing to promote means the swap no longer matches its journal and gets the rollback
         * path — which journals ORIGINALS_MOVED before moving so a mid-rollback kill is retried, not re-judged.
         */
        private fun committedRecovery(
            ops: RestoreFileOps,
            journalPath: String,
            entries: List<RestorePlanEntry>,
        ): RecoveryOutcome {
            entries.forEach { entry ->
                if (entry.hasStaged && !ops.exists(entry.livePath) && ops.exists(entry.stagedPath)) {
                    ops.atomicRename(entry.stagedPath, entry.livePath)
                }
            }
            val consistent = entries.all { entry -> !entry.hasStaged || ops.exists(entry.livePath) }
            if (!consistent) return rollbackRecovery(ops, journalPath, entries)
            entries.forEach { entry -> if (ops.exists(entry.backupPath)) ops.delete(entry.backupPath) }
            entries.forEach { entry -> if (ops.exists(entry.stagedPath)) ops.delete(entry.stagedPath) }
            if (ops.exists(journalPath)) ops.delete(journalPath)
            return RecoveryOutcome.COMMITTED
        }
    }
}

/** One target as the journal records it: the three paths plus what the stage snapshot observed on disk. */
internal data class RestorePlanEntry(
    val livePath: String,
    val stagedPath: String,
    val backupPath: String,
    val hadLive: Boolean,
    val hasStaged: Boolean,
) {
    init {
        listOf(livePath, stagedPath, backupPath).forEach { path ->
            require('\t' !in path && '\n' !in path) { "Restore paths may not contain tab or newline: $path" }
        }
    }
}

/**
 * The journal file [RestoreTransaction] rewrites at every phase boundary: a `phase:` head line then one
 * tab-separated `t:` line per target carrying the observed had-live/has-staged flags. Strict like [RestoreJournal]:
 * an unreadable journal is a corrupt restore, never a half-trusted one.
 */
internal class RestorePhaseJournal(
    val phase: RestorePhase,
    val targets: List<RestorePlanEntry>,
) {
    fun encode(): String = buildString {
        append(PHASE_PREFIX).append(phase.name).append('\n')
        targets.forEach { entry ->
            append(TARGET_PREFIX)
                .append(entry.livePath).append('\t')
                .append(entry.stagedPath).append('\t')
                .append(entry.backupPath).append('\t')
                .append(if (entry.hadLive) '1' else '0').append('\t')
                .append(if (entry.hasStaged) '1' else '0').append('\n')
        }
    }

    companion object {
        private const val PHASE_PREFIX = "phase:"
        private const val TARGET_PREFIX = "t:"

        fun decode(text: String): RestorePhaseJournal {
            val lines = text.lines().filter { it.isNotEmpty() }
            val head = lines.firstOrNull() ?: throw IllegalArgumentException("Empty restore journal")
            if (!head.startsWith(PHASE_PREFIX)) throw IllegalArgumentException("Bad restore journal head")
            val phase = RestorePhase.valueOf(head.removePrefix(PHASE_PREFIX))
            val targets = lines.drop(1).map { line ->
                if (!line.startsWith(TARGET_PREFIX)) throw IllegalArgumentException("Bad restore journal line")
                val fields = line.removePrefix(TARGET_PREFIX).split('\t')
                if (fields.size != 5 || fields[3] !in FLAGS || fields[4] !in FLAGS) {
                    throw IllegalArgumentException("Bad restore journal target")
                }
                RestorePlanEntry(
                    livePath = fields[0],
                    stagedPath = fields[1],
                    backupPath = fields[2],
                    hadLive = fields[3] == "1",
                    hasStaged = fields[4] == "1",
                )
            }
            return RestorePhaseJournal(phase, targets)
        }

        private val FLAGS = listOf("0", "1")
    }
}

private fun writeJournal(
    ops: RestoreFileOps,
    journalPath: String,
    phase: RestorePhase,
    entries: List<RestorePlanEntry>,
) {
    ops.writeAtomically(journalPath, RestorePhaseJournal(phase, entries).encode().encodeToByteArray())
}

/**
 * The file half of every rollback: each backup that exists goes back over its live path (replacing a staged
 * install that already landed), and an install at a target that never had a live file is removed. Backup
 * existence is rechecked per call, which is what makes re-entering after a kill converge.
 */
private fun restoreOriginals(ops: RestoreFileOps, entries: List<RestorePlanEntry>) {
    entries.forEach { entry ->
        if (ops.exists(entry.backupPath)) {
            ops.atomicRename(entry.backupPath, entry.livePath)
        } else if (!entry.hadLive && ops.exists(entry.livePath)) {
            ops.delete(entry.livePath)
        }
    }
}

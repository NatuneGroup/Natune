package moe.rukamori.archivetune.shared.data.backup

/**
 * Every fact a host needs to decide what to do with a staged restore before Room opens. The platform layer
 * gathers these without interpretation and hands them to [decideRestoreStart]; the choice is pure so the
 * abort, block and rollback rules are testable without a filesystem.
 *
 * @property blocked an unresolved-corruption marker (`restore-blocked`) sits in the instance directory.
 * @property pending a staged restore directory exists at all.
 * @property legacyFormat the pending set is driven by the older single-phase journal, never the transaction.
 * @property abortMarker a joint abort (`restore-pending/abort`) was already persisted by an earlier process.
 * @property settingsBefore a pre-apply vault snapshot (`restore-pending/settings-before`) exists.
 * @property stagedSettings a settings snapshot is staged inside the pending set.
 * @property ownedSettingsEmpty the pending marker names no account/settings category the staged snapshot owns.
 * @property outcome what [RestoreTransaction.recover] reported for the transaction journal.
 */
data class RestoreStartFacts(
    val blocked: Boolean,
    val pending: Boolean,
    val legacyFormat: Boolean,
    val abortMarker: Boolean,
    val settingsBefore: Boolean,
    val stagedSettings: Boolean,
    val ownedSettingsEmpty: Boolean,
    val outcome: RecoveryOutcome,
)

/** The one thing startup does with a pending restore; every step has a single owner in the host. */
enum class RestoreStartStep {
    /** No pending set — open normally. */
    NONE,

    /** The old journal format owns the swap end to end; the transaction machine must not touch it. */
    LEGACY,

    /** A fresh staged set starts its retained commit. */
    START_COMMIT,

    /** A retained commit is owed its non-file apply, then `finalize`. */
    RESUME_APPLY,

    /** The commit finished; only the staging directory is left to drop. */
    FINISH_COMMITTED,

    /** Files rolled back and nothing more is owed — surface the failure once. */
    FAILED_ROLLBACK,

    /**
     * Undo the joint apply: finish the database rollback AND restore the vault from `settings-before`. Reached
     * from the abort marker or from a pending apply whose staged settings lost their owning categories.
     */
    JOINT_ABORT,

    /** Files already rest old, but `settings-before` says the vault was merged — the rollback owes the vault. */
    VAULT_ROLLBACK,

    /** The journal cannot be trusted; quarantine the set and hold recovery mode until the user resolves it. */
    CORRUPT,

    /** The blocked marker holds the instance in recovery mode regardless of what else is on disk. */
    BLOCKED,
}

/**
 * Chooses the startup step for one pending restore. Ordering rules: the blocked marker outranks everything;
 * the legacy journal owns its set to the end; an abort marker means an earlier process already decided to undo
 * the joint apply, so re-interpreting the outcome is wrong; only then does the recovery outcome speak. A
 * `ROLLED_BACK` with `settings-before` still owes the vault its snapshot, so it is an abort-shaped job, not a
 * clean failure.
 */
fun decideRestoreStart(facts: RestoreStartFacts): RestoreStartStep = when {
    facts.blocked -> RestoreStartStep.BLOCKED
    !facts.pending -> RestoreStartStep.NONE
    facts.legacyFormat -> RestoreStartStep.LEGACY
    facts.abortMarker -> RestoreStartStep.JOINT_ABORT
    else -> when (facts.outcome) {
        RecoveryOutcome.NOTHING -> RestoreStartStep.START_COMMIT
        RecoveryOutcome.PENDING_APPLY ->
            if (facts.stagedSettings && facts.ownedSettingsEmpty) {
                RestoreStartStep.JOINT_ABORT
            } else {
                RestoreStartStep.RESUME_APPLY
            }
        RecoveryOutcome.COMMITTED -> RestoreStartStep.FINISH_COMMITTED
        RecoveryOutcome.ROLLED_BACK ->
            if (facts.settingsBefore) RestoreStartStep.VAULT_ROLLBACK else RestoreStartStep.FAILED_ROLLBACK
        RecoveryOutcome.CORRUPT -> RestoreStartStep.CORRUPT
    }
}

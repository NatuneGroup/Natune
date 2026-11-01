package moe.rukamori.archivetune.shared.data.backup

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The [decideRestoreStart] truth table: every step has exactly one owner, and the precedence — blocked, then
 * missing, then legacy, then the persisted abort decision, then the recovery outcome — is what keeps a killed
 * abort from being re-read as a fresh restore.
 */
class RestoreStartupDecisionTest {
    private fun facts(
        outcome: RecoveryOutcome = RecoveryOutcome.NOTHING,
        blocked: Boolean = false,
        pending: Boolean = true,
        legacyFormat: Boolean = false,
        abortMarker: Boolean = false,
        settingsBefore: Boolean = false,
        stagedSettings: Boolean = false,
        ownedSettingsEmpty: Boolean = false,
    ) = RestoreStartFacts(
        blocked = blocked,
        pending = pending,
        legacyFormat = legacyFormat,
        abortMarker = abortMarker,
        settingsBefore = settingsBefore,
        stagedSettings = stagedSettings,
        ownedSettingsEmpty = ownedSettingsEmpty,
        outcome = outcome,
    )

    @Test
    fun blockedMarkerOutranksEverything() {
        RecoveryOutcome.entries.forEach { outcome ->
            assertEquals(
                RestoreStartStep.BLOCKED,
                decideRestoreStart(facts(outcome = outcome, blocked = true, abortMarker = true)),
                "blocked with $outcome",
            )
            assertEquals(
                RestoreStartStep.BLOCKED,
                decideRestoreStart(facts(outcome = outcome, blocked = true, pending = false)),
                "blocked without pending, $outcome",
            )
        }
    }

    @Test
    fun missingPendingSetIsNothing() {
        RecoveryOutcome.entries.forEach { outcome ->
            assertEquals(RestoreStartStep.NONE, decideRestoreStart(facts(outcome = outcome, pending = false)))
        }
    }

    @Test
    fun legacyJournalOwnsItsSet() {
        RecoveryOutcome.entries.forEach { outcome ->
            assertEquals(
                RestoreStartStep.LEGACY,
                decideRestoreStart(facts(outcome = outcome, legacyFormat = true)),
                "legacy with $outcome",
            )
        }
    }

    @Test
    fun persistedAbortOverridesOutcome() {
        RecoveryOutcome.entries.forEach { outcome ->
            assertEquals(
                RestoreStartStep.JOINT_ABORT,
                decideRestoreStart(facts(outcome = outcome, abortMarker = true)),
                "abort marker with $outcome",
            )
        }
    }

    @Test
    fun freshStagedSetStartsCommit() {
        assertEquals(RestoreStartStep.START_COMMIT, decideRestoreStart(facts(outcome = RecoveryOutcome.NOTHING)))
    }

    @Test
    fun pendingApplyResumesUnlessSettingsLostTheirOwners() {
        assertEquals(
            RestoreStartStep.RESUME_APPLY,
            decideRestoreStart(facts(outcome = RecoveryOutcome.PENDING_APPLY)),
        )
        assertEquals(
            RestoreStartStep.RESUME_APPLY,
            decideRestoreStart(facts(outcome = RecoveryOutcome.PENDING_APPLY, stagedSettings = true)),
        )
        assertEquals(
            RestoreStartStep.JOINT_ABORT,
            decideRestoreStart(
                facts(
                    outcome = RecoveryOutcome.PENDING_APPLY,
                    stagedSettings = true,
                    ownedSettingsEmpty = true,
                ),
            ),
        )
    }

    @Test
    fun committedJournalOnlyCleansUp() {
        assertEquals(
            RestoreStartStep.FINISH_COMMITTED,
            decideRestoreStart(facts(outcome = RecoveryOutcome.COMMITTED)),
        )
    }

    @Test
    fun rolledBackOwesTheVaultWhileItsSnapshotExists() {
        assertEquals(
            RestoreStartStep.VAULT_ROLLBACK,
            decideRestoreStart(facts(outcome = RecoveryOutcome.ROLLED_BACK, settingsBefore = true)),
        )
        assertEquals(
            RestoreStartStep.FAILED_ROLLBACK,
            decideRestoreStart(facts(outcome = RecoveryOutcome.ROLLED_BACK)),
        )
    }

    @Test
    fun corruptStaysCorrupt() {
        assertEquals(RestoreStartStep.CORRUPT, decideRestoreStart(facts(outcome = RecoveryOutcome.CORRUPT)))
    }
}

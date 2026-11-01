package moe.rukamori.archivetune.shared.data.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The [decideRestoreResolution] truth table: a journaled choice always wins and always resumes at whatever
 * pass it reached, original files are read from the failed set and the still-pending set alike, and a fresh
 * "restore previous" with nothing to put back is never promised. The journal itself round-trips strictly.
 */
class RestoreResolutionDecisionTest {
    private fun facts(
        resolution: RestoreResolution = RestoreResolution.START_EMPTY,
        recorded: RestoreResolutionEntry? = null,
        originalsInPending: Boolean = false,
        originalsInFailed: Boolean = false,
    ) = RestoreResolutionFacts(
        recorded = recorded,
        chosen = resolution,
        originalsInPending = originalsInPending,
        originalsInFailed = originalsInFailed,
    )

    @Test
    fun journaledRestorePreviousResumesAfterAKill() {
        // The kill can land in any pass: the recorded choice still finishes, even against a fresh
        // conflicting pick, and no matter which originals are left.
        RestoreResolutionStage.entries.forEach { stage ->
            assertEquals(
                RestoreResolutionStep.RESOLVE_PREVIOUS,
                decideRestoreResolution(
                    facts(
                        resolution = RestoreResolution.START_EMPTY,
                        recorded = RestoreResolutionEntry(RestoreResolution.RESTORE_PREVIOUS, stage),
                    ),
                ),
                "recorded restore-previous at $stage",
            )
        }
    }

    @Test
    fun journaledStartEmptyResumesAfterAKill() {
        RestoreResolutionStage.entries.forEach { stage ->
            assertEquals(
                RestoreResolutionStep.RESOLVE_EMPTY,
                decideRestoreResolution(
                    facts(
                        resolution = RestoreResolution.RESTORE_PREVIOUS,
                        recorded = RestoreResolutionEntry(RestoreResolution.START_EMPTY, stage),
                        originalsInFailed = true,
                    ),
                ),
                "recorded start-empty at $stage",
            )
        }
    }

    @Test
    fun originalsAreReadFromPendingAndFailedSets() {
        assertEquals(
            RestoreResolutionStep.RESOLVE_PREVIOUS,
            decideRestoreResolution(
                facts(resolution = RestoreResolution.RESTORE_PREVIOUS, originalsInPending = true),
            ),
        )
        assertEquals(
            RestoreResolutionStep.RESOLVE_PREVIOUS,
            decideRestoreResolution(
                facts(resolution = RestoreResolution.RESTORE_PREVIOUS, originalsInFailed = true),
            ),
        )
        assertEquals(
            RestoreResolutionStep.UNRESOLVABLE,
            decideRestoreResolution(facts(resolution = RestoreResolution.RESTORE_PREVIOUS)),
        )
    }

    @Test
    fun resolutionJournalRoundTrips() {
        RestoreResolution.entries.forEach { resolution ->
            RestoreResolutionStage.entries.forEach { stage ->
                val entry = RestoreResolutionEntry(resolution, stage)
                assertEquals(entry, decodeRestoreResolution(encodeRestoreResolution(entry)))
            }
        }
        assertNull(decodeRestoreResolution(""))
        assertNull(decodeRestoreResolution("action: something-else\n"))
        assertNull(decodeRestoreResolution("step: nowhere\n"))
        assertNull(decodeRestoreResolution("action: restore-previous\nstep: nowhere\n"))
        // A journal line can be lost to a kill between writes; the action alone still resumes from the start.
        assertEquals(
            RestoreResolutionEntry(RestoreResolution.RESTORE_PREVIOUS, RestoreResolutionStage.QUARANTINING),
            decodeRestoreResolution("action: restore-previous\n"),
        )
    }
}

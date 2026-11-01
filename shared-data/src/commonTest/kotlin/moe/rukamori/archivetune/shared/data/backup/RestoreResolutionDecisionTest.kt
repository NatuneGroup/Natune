package moe.rukamori.archivetune.shared.data.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The [decideRestoreResolution] truth table: a journaled choice wins and resumes only while it still belongs
 * to the block on disk — a stale journal is ignored entirely — and its pinned `source`/`files` survive the
 * journal round-trip so a resume can finish a half-consumed originals set. A dead recorded restore (nothing
 * left to put back) is the one case a fresh "start empty" pick overrides, and a fresh "restore previous"
 * without originals is never promised.
 */
class RestoreResolutionDecisionTest {
    private fun facts(
        resolution: RestoreResolution = RestoreResolution.START_EMPTY,
        recorded: RestoreResolutionEntry? = null,
        journalBound: Boolean = true,
        pinnedFilesPresent: Boolean = false,
        originalsInPending: Boolean = false,
        originalsInFailed: Boolean = false,
    ) = RestoreResolutionFacts(
        recorded = recorded,
        chosen = resolution,
        journalBound = journalBound,
        pinnedFilesPresent = pinnedFilesPresent,
        originalsInPending = originalsInPending,
        originalsInFailed = originalsInFailed,
    )

    @Test
    fun journaledRestorePreviousResumesAfterAKill() {
        // The kill can land in any pass: the recorded choice still finishes, even against a fresh conflicting
        // pick — while originals remain it is never a dead resolution.
        RestoreResolutionStage.entries.forEach { stage ->
            assertEquals(
                RestoreResolutionStep.RESOLVE_PREVIOUS,
                decideRestoreResolution(
                    facts(
                        resolution = RestoreResolution.START_EMPTY,
                        recorded = RestoreResolutionEntry(
                            RestoreResolution.RESTORE_PREVIOUS,
                            stage,
                            blockToken = "block-1",
                            source = "/instance/restore-failed/previous",
                            files = listOf("natune.db", "natune.db-wal"),
                        ),
                        pinnedFilesPresent = true,
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
                        recorded = RestoreResolutionEntry(
                            RestoreResolution.START_EMPTY,
                            stage,
                            blockToken = "block-1",
                        ),
                        originalsInFailed = true,
                    ),
                ),
                "recorded start-empty at $stage",
            )
        }
    }

    @Test
    fun restoringResumeKeepsThePinnedSourceAndFiles() {
        // A kill between the main file and its WAL must resume from the recorded set — not rediscover an
        // emptied originals directory and skip to cleanup with the WAL stranded.
        val entry = RestoreResolutionEntry(
            RestoreResolution.RESTORE_PREVIOUS,
            RestoreResolutionStage.RESTORING,
            blockToken = "1700000000000",
            source = "/instance/restore-failed/previous",
            files = listOf("natune.db", "natune.db-wal", "natune.db-shm"),
        )
        assertEquals(entry, decodeRestoreResolution(encodeRestoreResolution(entry)))
        assertEquals(
            RestoreResolutionStep.RESOLVE_PREVIOUS,
            decideRestoreResolution(facts(recorded = entry)),
        )
    }

    @Test
    fun pendingSetRetiresBeforeCleanupIsDurable() {
        // RETIRING sits strictly between RESTORING and CLEANUP: the resolved pending set is quarantined
        // before cleanup is recorded, so the next startup never replays it, and a kill inside the pass
        // resumes restore-previous rather than finishing blind.
        assertTrue(RestoreResolutionStage.RESTORING.ordinal < RestoreResolutionStage.RETIRING.ordinal)
        assertTrue(RestoreResolutionStage.RETIRING.ordinal < RestoreResolutionStage.CLEANUP.ordinal)
        assertEquals(
            RestoreResolutionStep.RESOLVE_PREVIOUS,
            decideRestoreResolution(
                facts(
                    recorded = RestoreResolutionEntry(
                        RestoreResolution.RESTORE_PREVIOUS,
                        RestoreResolutionStage.RETIRING,
                        blockToken = "block-1",
                        source = "/instance/restore-pending/previous",
                        files = listOf("natune.db"),
                    ),
                    pinnedFilesPresent = true,
                ),
            ),
        )
    }

    @Test
    fun staleJournalNeverResolvesADifferentBlock() {
        // journalBound=false means the journal on disk belongs to an earlier block: its recorded choice must
        // drive nothing — the fresh pick decides as if no journal existed, in both directions.
        assertEquals(
            RestoreResolutionStep.RESOLVE_EMPTY,
            decideRestoreResolution(
                facts(
                    resolution = RestoreResolution.START_EMPTY,
                    recorded = RestoreResolutionEntry(
                        RestoreResolution.RESTORE_PREVIOUS,
                        RestoreResolutionStage.RESTORING,
                        blockToken = "old-block",
                    ),
                    journalBound = false,
                    originalsInFailed = true,
                ),
            ),
        )
        assertEquals(
            RestoreResolutionStep.RESOLVE_PREVIOUS,
            decideRestoreResolution(
                facts(
                    resolution = RestoreResolution.RESTORE_PREVIOUS,
                    recorded = RestoreResolutionEntry(
                        RestoreResolution.START_EMPTY,
                        RestoreResolutionStage.QUARANTINING,
                        blockToken = "old-block",
                    ),
                    journalBound = false,
                    originalsInFailed = true,
                ),
            ),
        )
    }

    @Test
    fun walStillInSourceKeepsRecordedRestoreAlive() {
        // The main file promoted and its WAL never did: the pinned manifest still has a file waiting in
        // source, so the recorded restore is not dead — "start empty" must not override it — even though no
        // kept set flags a main file anymore.
        assertEquals(
            RestoreResolutionStep.RESOLVE_PREVIOUS,
            decideRestoreResolution(
                facts(
                    resolution = RestoreResolution.START_EMPTY,
                    recorded = RestoreResolutionEntry(
                        RestoreResolution.RESTORE_PREVIOUS,
                        RestoreResolutionStage.RESTORING,
                        blockToken = "block-1",
                        source = "/instance/restore-failed/previous",
                        files = listOf("natune.db", "natune.db-wal"),
                    ),
                    pinnedFilesPresent = true,
                ),
            ),
        )
    }

    @Test
    fun deadRecordedRestoreYieldsToStartEmpty() {
        // The only exception to recorded-wins: a journaled restore-previous whose pinned manifest is entirely
        // gone — nothing left in source, nothing live — can never finish, so an explicit "start empty" pick
        // takes over instead of blocking forever.
        assertEquals(
            RestoreResolutionStep.RESOLVE_EMPTY,
            decideRestoreResolution(
                facts(
                    resolution = RestoreResolution.START_EMPTY,
                    recorded = RestoreResolutionEntry(
                        RestoreResolution.RESTORE_PREVIOUS,
                        RestoreResolutionStage.RESTORING,
                        blockToken = "block-1",
                    ),
                ),
            ),
        )
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
        assertNull(decodeRestoreResolution("action: restore-previous\nunknown: line\n"))
        // A journal line can be lost to a kill between writes; the action alone still resumes from the start.
        assertEquals(
            RestoreResolutionEntry(RestoreResolution.RESTORE_PREVIOUS, RestoreResolutionStage.QUARANTINING),
            decodeRestoreResolution("action: restore-previous\n"),
        )
    }
}

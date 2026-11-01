package moe.rukamori.archivetune.shared.data.backup

/**
 * What a blocked restore is resolving to. The value is journaled before a file moves, so a kill mid-resolution
 * resumes the same choice on the next start instead of re-prompting or half-applying the other action.
 */
enum class RestoreResolution(val journalName: String) {
    /** Put the originals a kept set holds back over the live slots. */
    RESTORE_PREVIOUS("restore-previous"),

    /** Quarantine the live, pending and failed sets and open on an empty library. */
    START_EMPTY("start-empty");
}

/**
 * How far a journaled resolution ran. The journal advances between passes so a resume only redoes the pass
 * that was in flight; every move inside a pass is idempotent on its own.
 */
enum class RestoreResolutionStage(val journalName: String) {
    /** Live files are still moving into quarantine. */
    QUARANTINING("quarantining"),

    /** The quarantine pass finished; originals are moving back over the live slots. */
    RESTORING("restoring"),

    /** Every move landed; only the markers are left to clear. */
    CLEANUP("cleanup");
}

/** One resolution-journal record: the choice and the furthest pass it reached. */
data class RestoreResolutionEntry(
    val resolution: RestoreResolution,
    val stage: RestoreResolutionStage,
)

private const val RESOLUTION_ACTION_PREFIX = "action: "
private const val RESOLUTION_STEP_PREFIX = "step: "

/** Two `key: value` lines, written atomically; a missing `step:` line is the first pass. */
fun encodeRestoreResolution(entry: RestoreResolutionEntry): String =
    "$RESOLUTION_ACTION_PREFIX${entry.resolution.journalName}\n" +
        "$RESOLUTION_STEP_PREFIX${entry.stage.journalName}\n"

/** Strict like the transaction journal: anything unreadable is no record at all. */
fun decodeRestoreResolution(text: String): RestoreResolutionEntry? {
    val lines = text.lines().filter { it.isNotEmpty() }
    if (lines.isEmpty() || lines.size > 2) return null
    val resolution = lines.firstOrNull { it.startsWith(RESOLUTION_ACTION_PREFIX) }
        ?.removePrefix(RESOLUTION_ACTION_PREFIX)
        ?.let { name -> RestoreResolution.entries.firstOrNull { it.journalName == name } }
        ?: return null
    val stage = lines.firstOrNull { it.startsWith(RESOLUTION_STEP_PREFIX) }
        ?.removePrefix(RESOLUTION_STEP_PREFIX)
        ?.let { name -> RestoreResolutionStage.entries.firstOrNull { it.journalName == name } ?: return null }
        ?: RestoreResolutionStage.QUARANTINING
    return RestoreResolutionEntry(resolution, stage)
}

/**
 * What the host gathers before touching a file: the journaled choice (if one survived a kill), the user's
 * fresh pick when there is none, and where original files still sit — the failed set's `previous/` or the
 * still-pending set's — so "restore previous" is only promised when they exist.
 */
data class RestoreResolutionFacts(
    val recorded: RestoreResolutionEntry?,
    val chosen: RestoreResolution,
    val originalsInPending: Boolean,
    val originalsInFailed: Boolean,
) {
    /** True when either kept set still holds an original library to put back. */
    val originalsAvailable: Boolean get() = originalsInPending || originalsInFailed
}

/** The next action for one blocked restore; every step has a single owner in the host. */
enum class RestoreResolutionStep {
    /** Put originals back over the live slots, finishing whatever a kill left mid-move. */
    RESOLVE_PREVIOUS,

    /** Quarantine the live, pending and failed sets and open on an empty library. */
    RESOLVE_EMPTY,

    /** A fresh "restore previous" with no originals anywhere: keep the set blocked, never invent files. */
    UNRESOLVABLE,
}

/**
 * The journaled choice always wins and always resumes — its moves are idempotent, so even a half-restored set
 * converges on the same end. Only a brand-new "restore previous" must prove originals exist somewhere first.
 */
fun decideRestoreResolution(facts: RestoreResolutionFacts): RestoreResolutionStep {
    val action = facts.recorded?.resolution ?: facts.chosen
    return when (action) {
        RestoreResolution.RESTORE_PREVIOUS ->
            if (facts.recorded != null || facts.originalsAvailable) {
                RestoreResolutionStep.RESOLVE_PREVIOUS
            } else {
                RestoreResolutionStep.UNRESOLVABLE
            }
        RestoreResolution.START_EMPTY -> RestoreResolutionStep.RESOLVE_EMPTY
    }
}

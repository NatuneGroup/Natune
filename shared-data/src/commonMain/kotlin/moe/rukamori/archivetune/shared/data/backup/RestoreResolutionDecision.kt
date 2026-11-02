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

    /** The originals are live; the resolved pending set is moving into quarantine before markers drop. */
    RETIRING("retiring"),

    /** Every move landed; only the markers are left to clear. */
    CLEANUP("cleanup");
}

/**
 * One resolution-journal record: the choice, the furthest pass it reached, the block it belongs to — stored as
 * the blocked marker's content, so a journal can never resolve a different block — and for "restore previous"
 * the originals directory and the file names expected live before cleanup is durable.
 */
data class RestoreResolutionEntry(
    val resolution: RestoreResolution,
    val stage: RestoreResolutionStage,
    val blockToken: String? = null,
    val source: String? = null,
    val files: List<String> = emptyList(),
)

private const val RESOLUTION_ACTION_PREFIX = "action: "
private const val RESOLUTION_STEP_PREFIX = "step: "
private const val RESOLUTION_BLOCK_PREFIX = "block: "
private const val RESOLUTION_SOURCE_PREFIX = "source: "
private const val RESOLUTION_FILES_PREFIX = "files: "

/** `key: value` lines, written atomically; a missing `step:` line is the first pass. */
fun encodeRestoreResolution(entry: RestoreResolutionEntry): String = buildString {
    append(RESOLUTION_ACTION_PREFIX).append(entry.resolution.journalName).append('\n')
    append(RESOLUTION_STEP_PREFIX).append(entry.stage.journalName).append('\n')
    entry.blockToken?.let { append(RESOLUTION_BLOCK_PREFIX).append(it).append('\n') }
    entry.source?.let { append(RESOLUTION_SOURCE_PREFIX).append(it).append('\n') }
    entry.files.forEach { append(RESOLUTION_FILES_PREFIX).append(it).append('\n') }
}

/** Strict like the transaction journal: anything unreadable is no record at all. */
fun decodeRestoreResolution(text: String): RestoreResolutionEntry? {
    var resolution: RestoreResolution? = null
    var stage = RestoreResolutionStage.QUARANTINING
    var blockToken: String? = null
    var source: String? = null
    val files = mutableListOf<String>()
    text.lines().filter { it.isNotEmpty() }.forEach { line ->
        when {
            line.startsWith(RESOLUTION_ACTION_PREFIX) -> {
                resolution = RestoreResolution.entries.firstOrNull {
                    it.journalName == line.removePrefix(RESOLUTION_ACTION_PREFIX)
                }
                if (resolution == null) return null
            }
            line.startsWith(RESOLUTION_STEP_PREFIX) -> {
                stage = RestoreResolutionStage.entries.firstOrNull {
                    it.journalName == line.removePrefix(RESOLUTION_STEP_PREFIX)
                } ?: return null
            }
            line.startsWith(RESOLUTION_BLOCK_PREFIX) ->
                blockToken = line.removePrefix(RESOLUTION_BLOCK_PREFIX)
            line.startsWith(RESOLUTION_SOURCE_PREFIX) ->
                source = line.removePrefix(RESOLUTION_SOURCE_PREFIX)
            line.startsWith(RESOLUTION_FILES_PREFIX) ->
                files += line.removePrefix(RESOLUTION_FILES_PREFIX)
            else -> return null
        }
    }
    return resolution?.let { RestoreResolutionEntry(it, stage, blockToken, source, files) }
}

/**
 * What the host gathers before touching a file: the journaled choice (if one survived a kill), whether that
 * journal still belongs to the block on disk, the user's fresh pick when there is none, whether any file in
 * the journal's pinned manifest survives — in the pinned source or already at its live slot — and where
 * original main files still sit (the failed set's `previous/` or the still-pending set's) for a fresh pick.
 */
data class RestoreResolutionFacts(
    val recorded: RestoreResolutionEntry?,
    val chosen: RestoreResolution,
    val journalBound: Boolean,
    val pinnedFilesPresent: Boolean,
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
 * The journaled choice always wins and always resumes — but only while it still belongs to the block on disk:
 * a stale journal is ignored, never allowed to resolve a different block. The one exception is a recorded
 * restore-previous that can never finish (no originals left) losing to a fresh "start empty" pick. A
 * brand-new "restore previous" must prove originals exist somewhere first.
 */
fun decideRestoreResolution(facts: RestoreResolutionFacts): RestoreResolutionStep {
    val recorded = facts.recorded?.takeIf { facts.journalBound }
    if (recorded != null) {
        if (recorded.resolution == RestoreResolution.RESTORE_PREVIOUS &&
            facts.chosen == RestoreResolution.START_EMPTY &&
            !facts.pinnedFilesPresent
        ) {
            // Only a manifest-wide loss makes the recorded restore dead: every pinned file absent from the
            // pinned source AND its live slot. A single pinned file still in source — the WAL a kill left
            // behind after the main file promoted — keeps the recorded choice alive, and "start empty"
            // must not override it. No pinned manifest at all reads as dead too.
            return RestoreResolutionStep.RESOLVE_EMPTY
        }
        return when (recorded.resolution) {
            RestoreResolution.RESTORE_PREVIOUS -> RestoreResolutionStep.RESOLVE_PREVIOUS
            RestoreResolution.START_EMPTY -> RestoreResolutionStep.RESOLVE_EMPTY
        }
    }
    return when (facts.chosen) {
        RestoreResolution.RESTORE_PREVIOUS ->
            if (facts.originalsAvailable) {
                RestoreResolutionStep.RESOLVE_PREVIOUS
            } else {
                RestoreResolutionStep.UNRESOLVABLE
            }
        RestoreResolution.START_EMPTY -> RestoreResolutionStep.RESOLVE_EMPTY
    }
}

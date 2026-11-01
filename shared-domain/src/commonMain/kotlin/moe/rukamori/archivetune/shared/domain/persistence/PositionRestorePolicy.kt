package moe.rukamori.archivetune.shared.domain.persistence

/**
 * Whether a persisted playback position may be restored. Apple Music keeps the resume point for
 * twelve hours and then drops it, so a song never restarts mid-way through days later; the position
 * also belongs to one specific item, and a queue whose restored current item is not that item gets
 * nothing. Both the Android resume files and the typed [QueueCheckpoint] ask this one question.
 */
object PositionRestorePolicy {
    /** The resume window: a saved position older than this was not recently heard. */
    const val MAX_SAVED_POSITION_AGE_MS = 43_200_000L

    /**
     * The position worth restoring, or zero. A position restores only while it is fresh
     * ([savedAtEpochMs] inside the window against [nowEpochMs]) and the item it was saved under is
     * still the item being restored ([savedItemId] == [restoredItemId]). A checkpoint without a
     * timestamp cannot prove it is recent, so its position is dropped.
     */
    fun positionMs(
        savedItemId: String?,
        restoredItemId: String?,
        savedAtEpochMs: Long?,
        positionMs: Long,
        nowEpochMs: Long,
    ): Long =
        if (
            positionMs > 0L &&
            savedItemId != null &&
            savedItemId == restoredItemId &&
            savedAtEpochMs != null &&
            nowEpochMs - savedAtEpochMs in 0 until MAX_SAVED_POSITION_AGE_MS
        ) {
            positionMs
        } else {
            0L
        }
}

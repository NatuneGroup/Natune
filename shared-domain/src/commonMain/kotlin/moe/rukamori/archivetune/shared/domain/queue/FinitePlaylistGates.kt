package moe.rukamori.archivetune.shared.domain.queue

/** Decisions that depend on how far a finite playlist has been confirmed. */
object FinitePlaylistGates {
    /** Media3's REPEAT_MODE_OFF. Repeat-one or repeat-all keeps the current queue, so no radio. */
    const val REPEAT_MODE_OFF = 0

    /** Whole-list insertion, import, sync and client shuffle all need the complete list. */
    fun wholeListActionAllowed(phase: FinitePlaylistPhase): Boolean = phase == FinitePlaylistPhase.EXHAUSTED

    /** Recommendations follow a fully loaded list only, with auto-load on and repeat off. */
    fun recommendationsAllowed(phase: FinitePlaylistPhase, autoLoadMore: Boolean, repeatMode: Int): Boolean =
        phase == FinitePlaylistPhase.EXHAUSTED && autoLoadMore && repeatMode == REPEAT_MODE_OFF

    /**
     * Whether an infinite (radio) queue may be seeded from the tail. A finite playlist seeds only once
     * it is EXHAUSTED, so an unknown or failed tail can never start a radio. Other queues seed from a
     * preload item or from an exhausted tail.
     */
    fun infiniteQueueSeedAllowed(
        isFiniteQueue: Boolean,
        phase: FinitePlaylistPhase,
        hasPreloadItem: Boolean,
        hasNextPage: Boolean,
    ): Boolean = if (isFiniteQueue) phase == FinitePlaylistPhase.EXHAUSTED else hasPreloadItem || !hasNextPage

    /** Whether more pages can be requested for this phase. */
    fun hasNextPage(phase: FinitePlaylistPhase): Boolean = phase == FinitePlaylistPhase.PAGEABLE
}

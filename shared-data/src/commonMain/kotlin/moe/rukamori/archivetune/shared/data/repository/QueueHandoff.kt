package moe.rukamori.archivetune.shared.data.repository

import kotlinx.coroutines.flow.Flow
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence

/**
 * A blend the engine has armed from [fromOccurrenceId] into [toOccurrenceId]. It begins when the outgoing media reaches
 * [fadeStartPositionMs] and ends as that media ends, [overlapMs] later. A zero overlap is a gapless splice, which has
 * nothing to show.
 */
data class QueueHandoffState(
    val fromOccurrenceId: String,
    val toOccurrenceId: String,
    val fadeStartPositionMs: Long,
    val overlapMs: Long,
)

/**
 * The host side of a crossfade or automix transition. The queue owner decides when and into which occurrence; the
 * host decides whether the settings ask for a blend and drives its engine.
 */
interface QueueHandoffEngine {
    /** Emits whenever a setting that [overlapMs] reads changes, so a song already playing picks the change up. */
    val configuration: Flow<Any?>

    /**
     * The overlap for [current] into [next] over [durationMs] of outgoing media: a positive blend, zero for a gapless
     * splice, or null to let [current] end on its own before [next] loads.
     */
    fun overlapMs(current: ContentOccurrence, next: ContentOccurrence, durationMs: Long): Long?

    /** Arms [request] so it takes over as the playing media ends. False leaves nothing armed. */
    suspend fun arm(request: PlaybackRequest, overlapMs: Long): Boolean

    /** Drops an armed handoff, so the playing occurrence keeps the output. Nothing happens when none is armed. */
    fun cancel()
}

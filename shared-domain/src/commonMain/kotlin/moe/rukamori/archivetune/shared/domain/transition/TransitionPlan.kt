package moe.rukamori.archivetune.shared.domain.transition

/** Identifies one of the two decks that take turns owning the output. */
enum class TransitionDeck {
    FIRST,
    SECOND;

    fun other(): TransitionDeck = if (this == FIRST) SECOND else FIRST
}

/**
 * How the incoming deck takes over. [GAPLESS] splices at one output frame with no overlap, so the
 * output has neither silence nor mixing. [CROSSFADE] and [AUTOMIX] blend over the overlap; automix
 * differs only in that its planner chose the start frame.
 */
enum class TransitionKind { GAPLESS, CROSSFADE, AUTOMIX }

/**
 * A handoff indexed in output frames. The envelope depends only on the output frame counter, so one
 * plan yields the same gains however the caller splits its blocks.
 */
data class TransitionPlan(
    val kind: TransitionKind,
    val startFrame: Long,
    val overlapFrames: Long,
) {
    init {
        require(startFrame >= 0L) { "startFrame must not be negative" }
        require(overlapFrames >= 0L) { "overlapFrames must not be negative" }
        if (kind == TransitionKind.GAPLESS) {
            require(overlapFrames == 0L) { "a gapless handoff has no overlap" }
        } else {
            require(overlapFrames > 0L) { "a fade needs at least one overlap frame" }
        }
    }

    /** First output frame after the overlap, where the incoming deck owns the output alone. */
    val endFrame: Long
        get() = startFrame + overlapFrames
}

/** Gains for one output frame. Each applies to its own deck. */
data class TransitionGains(
    val outgoing: Float,
    val incoming: Float,
)

package moe.rukamori.archivetune.shared.domain.playback

/**
 * The small action-restrictions model: every transport action carries a verdict and, when it
 * is disallowed, the reason — the same shape Spotify's `disallow_*_reasons` fields take — so
 * surfaces can grey a control out honestly instead of letting a tap fail silently.
 */
enum class PlaybackRestrictionReason {
    NONE,

    /** Nothing is loaded at all — every transport action is unavailable. */
    NOTHING_LOADED,

    /** First queue item, too early in it to offer a restart jump. */
    START_OF_QUEUE,

    /** No successor item exists in the queue. */
    END_OF_QUEUE,

    /** The item itself cannot be seeked (live edge, progressive or unseekable source). */
    NOT_SEEKABLE,
}

/** One action's availability plus the reason when it is denied. */
data class PlaybackActionVerdict(
    val allowed: Boolean,
    val reason: PlaybackRestrictionReason = PlaybackRestrictionReason.NONE,
) {
    init {
        require(allowed || reason != PlaybackRestrictionReason.NONE) {
            "a denied action must name its reason"
        }
    }

    companion object {
        val ALLOWED = PlaybackActionVerdict(allowed = true)
        fun denied(reason: PlaybackRestrictionReason) = PlaybackActionVerdict(false, reason)
    }
}

/** The three verdicts surfaces actually grey out: skip-next, skip-previous and in-item seek. */
data class PlaybackActionRestrictions(
    val next: PlaybackActionVerdict,
    val previous: PlaybackActionVerdict,
    val seekWithin: PlaybackActionVerdict,
)

/** Derives restrictions from the host's player/queue state — the producer supplies booleans. */
object PlaybackRestrictionPolicy {
    fun resolve(
        hasCurrentItem: Boolean,
        hasSuccessor: Boolean,
        atQueueStart: Boolean,
        canRestartCurrentItem: Boolean,
        currentItemSeekable: Boolean,
    ): PlaybackActionRestrictions {
        if (!hasCurrentItem) {
            val denied = PlaybackActionVerdict.denied(PlaybackRestrictionReason.NOTHING_LOADED)
            return PlaybackActionRestrictions(denied, denied, denied)
        }
        return PlaybackActionRestrictions(
            next =
                if (hasSuccessor) {
                    PlaybackActionVerdict.ALLOWED
                } else {
                    PlaybackActionVerdict.denied(PlaybackRestrictionReason.END_OF_QUEUE)
                },
            previous =
                if (!atQueueStart || canRestartCurrentItem) {
                    PlaybackActionVerdict.ALLOWED
                } else {
                    PlaybackActionVerdict.denied(PlaybackRestrictionReason.START_OF_QUEUE)
                },
            seekWithin =
                if (currentItemSeekable) {
                    PlaybackActionVerdict.ALLOWED
                } else {
                    PlaybackActionVerdict.denied(PlaybackRestrictionReason.NOT_SEEKABLE)
                },
        )
    }
}

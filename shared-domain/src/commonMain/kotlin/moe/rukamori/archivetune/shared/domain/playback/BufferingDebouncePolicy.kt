package moe.rukamori.archivetune.shared.domain.playback

/**
 * Coalesces a stuttering raw buffering flag into the one observers see. Apple Music debounces its
 * buffering broadcast by 150 ms so a micro-stall never flickers the UI; a stall that outlives the
 * window still surfaces, and every other state change publishes at once.
 */
class BufferingDebouncePolicy(
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    /** The instant the first hidden buffering event arrived, while the transition is held. */
    private var pendingSinceMs: Long? = null

    data class Result(
        /** The buffering flag observers may see for this event. */
        val visibleBuffering: Boolean,
        /** Milliseconds until the event must be re-evaluated, or null when nothing is held. */
        val retryAfterMs: Long?,
    )

    /**
     * Fold one raw event into the publishable flag. A transition into buffering that was not yet
     * published is hidden while it is younger than [debounceMs]; once it has survived the window
     * it publishes as buffering, and every other event passes through with the hold cleared.
     */
    fun onEvent(rawBuffering: Boolean, alreadyPublished: Boolean, nowMs: Long): Result {
        if (!rawBuffering || alreadyPublished) {
            pendingSinceMs = null
            return Result(rawBuffering, null)
        }
        val since = pendingSinceMs ?: nowMs.also { pendingSinceMs = it }
        val elapsed = nowMs - since
        return if (elapsed >= debounceMs) {
            pendingSinceMs = null
            Result(visibleBuffering = true, retryAfterMs = null)
        } else {
            Result(visibleBuffering = false, retryAfterMs = debounceMs - elapsed)
        }
    }

    /** Drops a held transition: the next raw buffering event starts a fresh window. */
    fun reset() {
        pendingSinceMs = null
    }

    companion object {
        const val DEBOUNCE_MS = 150L
    }
}

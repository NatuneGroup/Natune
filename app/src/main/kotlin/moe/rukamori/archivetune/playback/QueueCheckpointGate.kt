package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.playback.queues.YouTubePlaylistQueue

/** Main-thread acceptance of user edits, independent of the finite loader's retry state. */
internal class QueueCheckpointGate(
    private val queue: () -> Queue,
    private val transientMutation: () -> Boolean,
    private val mediaItemCount: () -> Int,
) {
    private var finiteReplacementHasUserChanges = false

    fun reset() {
        finiteReplacementHasUserChanges = false
    }

    fun onTimelineChanged(isEmpty: Boolean) {
        if (!isEmpty && !transientMutation() && queue().hasUnacceptedFiniteReplacement()) {
            finiteReplacementHasUserChanges = true
        }
    }

    fun blocked(): Boolean {
        if (transientMutation()) return true
        val finite = queue() as? YouTubePlaylistQueue ?: return false
        // User edits only override the loader's own unaccepted state, never the accepted continuation guard below.
        if (finite.hasUnacceptedFiniteReplacement()) return !finiteReplacementHasUserChanges
        // An installed preload does not confirm the first page; either request can still fail.
        // Keep filtered-empty checkpoints until confirmed exhaustion or an explicit queue clear.
        return mediaItemCount() == 0 && (!finite.confirmedExhausted || finite.snapshot.continuation != null)
    }
}

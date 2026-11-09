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
    private var finiteEmptyQueueHasUserRemoval = false

    fun reset() {
        finiteReplacementHasUserChanges = false
        finiteEmptyQueueHasUserRemoval = false
    }

    /** Called only after a user removal has changed the timeline, never for automatic filtering. */
    fun onUserRemoval() {
        val finite = queue() as? YouTubePlaylistQueue ?: return
        // Before the owned initial clear, the remaining items still belong to the previous queue.
        if (!finite.initialTimelineCleared && !finite.timelineInstalled) return
        finiteReplacementHasUserChanges = true
        finiteEmptyQueueHasUserRemoval = mediaItemCount() == 0
    }

    fun onTimelineChanged(isEmpty: Boolean) {
        if (!isEmpty) finiteEmptyQueueHasUserRemoval = false
        val finite = queue() as? YouTubePlaylistQueue ?: return
        if (!isEmpty && !transientMutation() && finite.initialTimelineCleared && finite.hasUnacceptedFiniteReplacement()) {
            finiteReplacementHasUserChanges = true
        }
    }

    fun blocked(): Boolean {
        if (transientMutation()) return true
        val finite = queue() as? YouTubePlaylistQueue ?: return false
        // Preparation can suspend before clearing the previous queue's timeline.
        if (!finite.timelineInstalled && !finite.initialTimelineCleared) return true
        // Nonempty user edits only override the unaccepted replacement; empty removal acceptance is separate.
        if (finite.hasUnacceptedFiniteReplacement()) return !finiteReplacementHasUserChanges
        // An installed preload does not confirm the first page; either request can still fail.
        // Only a removal that left it empty accepts that result; additions cannot accept later filtering.
        return mediaItemCount() == 0 && !finiteEmptyQueueHasUserRemoval &&
            (!finite.confirmedExhausted || finite.snapshot.continuation != null)
    }
}

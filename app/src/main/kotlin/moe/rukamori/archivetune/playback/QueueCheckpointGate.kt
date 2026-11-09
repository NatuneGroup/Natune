package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.playback.queues.YouTubePlaylistQueue

/** Main-thread replacement installation and user acceptance, independent of loader retry state. */
internal class QueueCheckpointGate(
    private val queue: () -> Queue,
    private val transientMutation: () -> Boolean,
    private val mediaItemCount: () -> Int,
) {
    private var pendingReplacement: Queue? = null
    private var finiteReplacementHasUserChanges = false
    private var finiteEmptyQueueHasUserRemoval = false

    fun beginReplacement() {
        pendingReplacement = queue()
        finiteReplacementHasUserChanges = false
        finiteEmptyQueueHasUserRemoval = false
    }

    /** Only the replacement's own initial/preload installation can accept its timeline. */
    fun onReplacementInstalled(installedQueue: Queue) {
        if (queue() === installedQueue) pendingReplacement = null
    }

    fun onUserClear() {
        pendingReplacement = null
        finiteReplacementHasUserChanges = false
        finiteEmptyQueueHasUserRemoval = false
    }

    fun onUserAddition() {
        if (transientMutation() || mediaItemCount() == 0) return
        val finite = queue() as? YouTubePlaylistQueue
        if (finite != null && !finite.initialTimelineCleared && !finite.timelineInstalled) return
        pendingReplacement = null
        finiteReplacementHasUserChanges = finite != null
        finiteEmptyQueueHasUserRemoval = false
    }

    /** Called only after a user removal has changed the timeline, never for automatic filtering. */
    fun onUserRemoval() {
        val finite = queue() as? YouTubePlaylistQueue
        // Before the owned initial clear, the remaining items still belong to the previous queue.
        if (finite != null && !finite.initialTimelineCleared && !finite.timelineInstalled) return
        pendingReplacement = null
        finiteReplacementHasUserChanges = finite != null
        finiteEmptyQueueHasUserRemoval = finite != null && mediaItemCount() == 0
    }

    fun onTimelineChanged(isEmpty: Boolean) {
        if (!isEmpty) finiteEmptyQueueHasUserRemoval = false
        val finite = queue() as? YouTubePlaylistQueue ?: return
        if (!isEmpty && !transientMutation() && finite.initialTimelineCleared && finite.hasUnacceptedFiniteReplacement()) {
            onUserAddition()
        }
    }

    fun blocked(): Boolean {
        if (transientMutation()) return true
        // Automatic filtering can change the identity too; only installation/user edits accept it.
        if (pendingReplacement != null) return true
        val finite = queue() as? YouTubePlaylistQueue ?: return false
        // Preparation can suspend before clearing the previous queue's timeline.
        if (!finite.timelineInstalled && !finite.initialTimelineCleared) return true
        // Nonempty user edits only override the unaccepted replacement; empty removal acceptance is separate.
        if (finite.hasUnacceptedFiniteReplacement() && !finiteReplacementHasUserChanges) return true
        // An installed preload does not confirm the first page; either request can still fail.
        // Only a removal that left it empty accepts that result; additions cannot accept later filtering.
        return mediaItemCount() == 0 && !finiteEmptyQueueHasUserRemoval &&
            (!finite.confirmedExhausted || finite.snapshot.continuation != null)
    }
}

package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.playback.queues.EmptyQueue
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.playback.queues.YouTubePlaylistQueue

/** The checkpoint identity follows the installed timeline, never a requested replacement. */
internal class QueueCheckpointGate(
    private val queue: () -> Queue,
    private val transientMutation: () -> Boolean,
    private val mediaItemCount: () -> Int,
    private val title: () -> String? = { null },
) {
    private var pendingReplacement: Queue? = null
    private var savedTitle: String? = title()
    private var ownedMutationDepth = 0
    private var emptyTimelineAccepted = false
    private var sourceDetached = false
    var persistenceQueue: Queue = queue()
        private set
    val persistenceTitle: String?
        get() = when {
            persistenceQueue === EmptyQueue && mediaItemCount() == 0 -> null
            queue() === persistenceQueue -> title()
            else -> savedTitle
        }
    val timelineMutationInProgress: Boolean
        get() = ownedMutationDepth > 0

    /** Call before changing currentQueue/title, so retained items keep their previous identity. */
    fun beginReplacement(replacement: Queue) {
        savedTitle = persistenceTitle
        pendingReplacement = replacement
        emptyTimelineAccepted = false
        sourceDetached = false
    }

    /** Loader callbacks during their synchronous clear/insertion cannot accept a user edit. */
    fun mutateTimeline(mutation: () -> Unit) {
        ownedMutationDepth++
        try {
            mutation()
        } finally {
            ownedMutationDepth--
        }
    }

    fun onReplacementInstalled(installedQueue: Queue) {
        if (queue() !== installedQueue) return
        if (installedQueue === EmptyQueue) {
            onSourceDetached()
            return
        }
        persistenceQueue = installedQueue
        sourceDetached = false
        savedTitle = title()
        pendingReplacement = null
        if (mediaItemCount() > 0) emptyTimelineAccepted = false
    }

    /** Failure, cancellation and supersession abandon only their own pending request. */
    fun onReplacementFinished(replacement: Queue) {
        if (pendingReplacement === replacement) pendingReplacement = null
    }

    fun onExternalTimelineInstalled(installedQueue: Queue) {
        if (queue() !== installedQueue) return
        onReplacementInstalled(installedQueue)
        emptyTimelineAccepted = mediaItemCount() == 0
        if (emptyTimelineAccepted || installedQueue === EmptyQueue) onSourceDetached(emptyTimelineAccepted)
    }

    /** Shared transaction for service installers and session forwarding; thrown mutations retain identity. */
    fun installExternalTimeline(
        install: () -> Unit,
        acceptIdentity: () -> Queue,
        save: () -> Unit,
        retainIdentity: Boolean = false,
    ) {
        try {
            mutateTimeline {
                install()
                if (!retainIdentity) onExternalTimelineInstalled(acceptIdentity())
            }
        } finally {
            save()
        }
    }

    /** All source resets detach continuation/title; automatic emptiness still protects a saved finite queue. */
    fun onSourceDetached(acceptEmptyTimeline: Boolean = false) {
        sourceDetached = true
        pendingReplacement = null
        if (!acceptEmptyTimeline && mediaItemCount() == 0 && unresolvedFinite(persistenceQueue)) return
        persistenceQueue = EmptyQueue
        savedTitle = null
        emptyTimelineAccepted = acceptEmptyTimeline || mediaItemCount() == 0
    }

    fun onUserClear() = onSourceDetached(acceptEmptyTimeline = true)

    fun onUserAddition() {
        if (transientMutation() || ownedMutationDepth > 0 || mediaItemCount() == 0) return
        acceptUserIdentity()
        emptyTimelineAccepted = false
    }

    /** Called only after a user removal has changed the timeline, never for automatic filtering. */
    fun onUserRemoval() {
        if (transientMutation() || ownedMutationDepth > 0) return
        acceptUserIdentity()
        emptyTimelineAccepted = mediaItemCount() == 0
        if (emptyTimelineAccepted) onSourceDetached(acceptEmptyTimeline = true)
    }

    private fun acceptUserIdentity() {
        if (sourceDetached && queue() === EmptyQueue) onSourceDetached()
        val finite = queue() as? YouTubePlaylistQueue
        // Before the loader's clear, edits still belong to the retained timeline, even after cancel.
        if (finite != null && (finite.initialTimelineCleared || finite.timelineInstalled)) {
            onReplacementInstalled(finite)
        }
        pendingReplacement = null
    }

    fun onTimelineChanged(isEmpty: Boolean) {
        if (!isEmpty) emptyTimelineAccepted = false
        if (transientMutation() || ownedMutationDepth > 0) return
        savedTitle = persistenceTitle
        // Safety valve for an installer/edit outside the loader. Filtering does not accept emptiness.
        pendingReplacement = null
        if (!isEmpty) onUserAddition()
    }

    fun blocked(): Boolean {
        if (transientMutation() || ownedMutationDepth > 0) return true
        // A pending/failed replacement never prevents saving the currently playing items.
        if (mediaItemCount() > 0 || emptyTimelineAccepted) return false
        // A loader clear or automatic filtering cannot erase an unresolved finite playlist.
        // Explicit removal/clear and external installation accept emptiness independently of loading.
        return unresolvedFinite(queue()) || unresolvedFinite(persistenceQueue)
    }

    private fun unresolvedFinite(queue: Queue): Boolean {
        val finite = queue as? YouTubePlaylistQueue ?: return false
        return !finite.confirmedExhausted || finite.snapshot.continuation != null
    }
}

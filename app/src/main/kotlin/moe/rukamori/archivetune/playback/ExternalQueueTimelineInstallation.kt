package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.playback.queues.Queue

/** Only the session's same-occurrence replay can retain unfinished work and gate state. */
internal fun externalTimelineReplacesQueue(
    requestedQueue: Queue?,
    installedQueue: Queue,
    retainsInstalledOccurrences: Boolean,
): Boolean = !retainsInstalledOccurrences || requestedQueue == null || requestedQueue !== installedQueue

/** Service-side decision shared with deterministic player-thread regression fixtures. */
internal fun installServiceQueueTimeline(
    gate: QueueCheckpointGate,
    requestedQueue: Queue?,
    retainsInstalledOccurrences: Boolean,
    cancelObsoleteWork: () -> Unit,
    install: () -> Unit,
    acceptIdentity: () -> Queue,
    save: () -> Unit,
    evaluateLoading: () -> Unit,
) {
    val replacement = externalTimelineReplacesQueue(requestedQueue, gate.persistenceQueue, retainsInstalledOccurrences)
    if (replacement) cancelObsoleteWork()
    gate.installExternalTimeline(install, acceptIdentity, save, retainIdentity = !replacement)
    evaluateLoading()
}

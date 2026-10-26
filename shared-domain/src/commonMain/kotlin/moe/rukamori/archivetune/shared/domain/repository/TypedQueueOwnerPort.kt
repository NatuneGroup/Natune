/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.repository

import moe.rukamori.archivetune.shared.domain.persistence.QueueCheckpoint
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator

/**
 * Turns one queue occurrence into the locator the engine may open. Provider routing, account gates and
 * extractor tiers belong to the host or provider layer; the queue owner only asks for the answer.
 */
interface QueueLocatorResolver {
    suspend fun locate(occurrence: ContentOccurrence): PlaybackLocator
}

/**
 * The single queue owner for a platform whose queue is persisted as a typed [QueueCheckpoint] instead of a
 * legacy Java stream. It is the typed counterpart of [QueueOwnerPort]: the same timeline, metadata, manual
 * kinds, shuffle order and tails, with no second copy kept anywhere else.
 *
 * Every queue method must run inside [withQueueAccess]. The host reaches it through
 * [moe.rukamori.archivetune.shared.data.repository.TypedDelegatingQueueController], which takes that lock.
 */
interface TypedQueueOwnerPort : QueueController {
    suspend fun <T> withQueueAccess(operation: suspend () -> T): T
    suspend fun loadCollection(request: QueueLoadRequest)
    suspend fun capture(): QueueCheckpoint
    suspend fun restore(checkpoint: QueueCheckpoint)

    /**
     * Raises the queue revision to at least [floor] and leaves the queue itself unchanged. A process that
     * starts from an older revision uses it so its next checkpoint is not refused as stale.
     */
    suspend fun raiseRevisionTo(floor: Long)
}

package moe.rukamori.archivetune.shared.domain.repository

import moe.rukamori.archivetune.shared.domain.persistence.QueueCheckpoint
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.PlaybackSourceSnapshot

/**
 * Turns one queue occurrence into the locator the engine may open. Provider routing, account gates and
 * extractor tiers belong to the host or provider layer; the queue owner only asks for the answer.
 */
interface QueueLocatorResolver {
    suspend fun locate(occurrence: ContentOccurrence): PlaybackLocator

    /** Null means this host cannot recover the stream; local files and Telegram never enter this path. */
    suspend fun recover(occurrence: ContentOccurrence, recovery: QueueSourceRecovery): PlaybackLocator? = null
}

/** Refresh is restricted to one still-enabled source; fallback uses the ordinary order minus rejected attempts. */
data class QueueSourceRecovery(
    val refreshSource: AudioSourceType? = null,
    val rejectedSources: Set<AudioSourceType> = emptySet(),
) {
    fun settings(snapshot: PlaybackSourceSnapshot): PlaybackSourceSnapshot = refreshSource?.let {
        snapshot.copy(enabled = snapshot.enabled.intersect(setOf(it)))
    } ?: snapshot

    fun pinned(original: AudioSourceType?): AudioSourceType? =
        refreshSource ?: original?.takeUnless { it in rejectedSources }
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

    /** Turns shuffle on or off over the current timeline. False when this queue cannot be shuffled. */
    suspend fun setShuffle(enabled: Boolean): Boolean

    /**
     * Android's `MusicService.startRadioSeamlessly`: the songs after the current one are replaced by a
     * watch-next queue seeded from it. False when the current song cannot seed radio (no YouTube song
     * playing), as on Android. The queue keeps playing the current song.
     */
    suspend fun startRadioSeamlessly(): Boolean

    /**
     * Raises the queue revision to at least [floor] and leaves the queue itself unchanged. A process that
     * starts from an older revision uses it so its next checkpoint is not refused as stale.
     */
    suspend fun raiseRevisionTo(floor: Long)
}

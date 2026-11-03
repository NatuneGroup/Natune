package moe.rukamori.archivetune.shared.domain.repository

import moe.rukamori.archivetune.shared.domain.persistence.LegacyQueueDocument
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistIds

enum class QueueCollectionKind { FINITE, RADIO }
data class QueueStart(
    val item: ContentRef,
    val occurrenceId: String? = null,
    val setVideoId: String? = null,
    val originalIndex: Int? = null,
)

/**
 * Load the COLLECTION through the existing queue/source owner, not the UI's first-page items.
 * That owner retains complete metadata, continuation/consumed tokens and failed/unknown phase.
 * A finite YouTube source can never silently become RD/Mix, or vice versa.
 */
data class QueueLoadRequest(
    val collection: ContentRequest,
    val kind: QueueCollectionKind,
    val start: QueueStart? = null,
    val playWhenReady: Boolean = true,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
) {
    init {
        require(positionMs >= 0)
        require(start == null || start.item.provider == collection.provider.id)
        if (kind == QueueCollectionKind.FINITE) {
            require(FiniteCollectionStartPolicy.allows(collection)) {
                "Finite YouTube queue needs a non-RD playlist, a search, or a finite browse section"
            }
        }
        require(kind != QueueCollectionKind.RADIO || !shuffle) { "Complete-list shuffle is not server radio shuffle" }
    }
}

/**
 * Decides whether a FINITE load may start from a collection. It never invents a playlist id and never turns a
 * finite browse into radio: a playlist surface still needs its own non-RD id, while search and section browses
 * (whose completion is decided by the provider's continuations) may start without one. An RD id, in either a
 * parent or a section, is endless radio and is refused here.
 */
object FiniteCollectionStartPolicy {
    fun allows(collection: ContentRequest): Boolean {
        if (collection.provider != ContentProvider.YOUTUBE) return true
        collection.parent?.let { return FinitePlaylistIds.isFinite(it.id) }
        return collection.surface == ContentSurface.SEARCH ||
            (collection.section != null && FinitePlaylistIds.isFinite(collection.section.id))
    }
}

/**
 * Whether a finite-loaded collection stays finite: a built-in library collection ends where the user
 * sees it end rather than continuing into a seeded recommendation tail. User playlists and catalogue
 * collections keep their continuation, and an explicit radio action always clears the flag on the
 * committed [QueueState]. The flag itself lives on [QueueState.staysFinite]; this object only classifies
 * the collection a queue is loaded from.
 */
object BuiltInCollectionFinitePolicy {
    private val STAYS_FINITE_SURFACES: Set<ContentSurface> =
        setOf(
            ContentSurface.LIBRARY_SONGS,
            ContentSurface.LIBRARY_LIKED,
            ContentSurface.LIBRARY_MIX,
            ContentSurface.LIBRARY_PODCASTS,
            ContentSurface.DOWNLOADS,
            ContentSurface.HISTORY,
            ContentSurface.STATISTICS,
            ContentSurface.RECAP,
        )

    fun staysFinite(collection: ContentRequest): Boolean = collection.surface in STAYS_FINITE_SURFACES
}

/** Full compatibility state; the original stream is never projected through ContentItem. */
data class LegacyQueueResume(val revision: Long, val document: LegacyQueueDocument, val positionOverrideMs: Long? = null) {
    init { require(revision >= 0 && (positionOverrideMs == null || positionOverrideMs >= 0)) }
}
interface LosslessQueueCheckpointStore {
    suspend fun read(): LegacyQueueResume?
    suspend fun write(value: LegacyQueueResume, expectedRevision: Long?): Boolean
}

/** Additive subtype: existing QueueController implementations/signatures stay unchanged. */
interface ContinuationQueueController : QueueController {
    suspend fun load(request: QueueLoadRequest)
    suspend fun checkpoint(): Boolean
    suspend fun resume(): Boolean
}

/**
 * Boundary onto the SAME existing host queue, not a second queue implementation.
 * Serialize with all player-thread/automatic/restore mutations. Completion means committed,
 * never merely enqueued. Capture/restore must retain metadata, manual kinds, shuffle and tails.
 */
interface QueueOwnerPort : QueueController {
    suspend fun <T> withQueueAccess(operation: suspend () -> T): T
    suspend fun loadCollection(request: QueueLoadRequest)
    suspend fun capture(): LegacyQueueResume
    suspend fun restore(value: LegacyQueueResume)
}

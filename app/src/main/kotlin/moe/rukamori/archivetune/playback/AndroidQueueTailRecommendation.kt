package moe.rukamori.archivetune.playback

import androidx.media3.common.MediaItem
import java.util.TimeZone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.db.DatabaseDao
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.shared.data.recommend.QueueTailRecommendation
import moe.rukamori.archivetune.shared.data.recommend.queueTailSeed
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/**
 * One immutable play-order capture taken on the playback thread. [currentIndex] is the current occurrence's position
 * in [playOrder], never a media-id lookup: duplicate media ids are permitted and a later duplicate must not make
 * already-played occurrences look pending. [manualIndices] marks hand-queued occurrences by position for the same
 * reason.
 */
internal data class AndroidQueueTailSnapshot(
    val playOrder: List<MediaItem>,
    val currentIndex: Int,
    val manualIndices: Set<Int> = emptySet(),
)

/**
 * The retained Android projection over the shared recommendation policy. The owner captures the fetched provider
 * batch and an immutable [AndroidQueueTailSnapshot] on the playback thread, then this adapter converts and ranks
 * off-main. Selected ids map back to the exact original [MediaItem] instances, so URI, tags, extras, playback
 * configuration and source fallback stay untouched.
 *
 * [metadataOf] is the one metadata reader seam: production reads the item's existing tag, while Java-only fixtures
 * supply their metadata map because a MediaItem built without a URI keeps no local-configuration tag.
 */
internal class AndroidQueueTailRecommendation(
    private val recommendation: QueueTailRecommendation,
    private val automaticIds: Set<String> = emptySet(),
    private val metadataOf: (MediaItem) -> MediaMetadata? = { it.metadata },
    private val blockedSongIds: suspend () -> Set<String> = { emptySet() },
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * [automaticIds] is the existing service marker set, snapshotted on the caller's thread. Played and current
     * entries are excluded by their ordered position, so a long-lived queue never feeds a lifetime append count into
     * the budget.
     */
    suspend fun plan(
        candidates: List<MediaItem>,
        snapshot: AndroidQueueTailSnapshot,
    ): List<MediaItem> {
        val pending = synchronized(automaticIds) { automaticIds.toSet() }
        if (candidates.isEmpty()) return emptyList()
        return withContext(computeDispatcher) {
            val originals = linkedMapOf<String, MediaItem>()
            for (item in candidates) {
                if (originals.size >= QueueTailRecommendation.MAX_SCORED_CANDIDATES) break
                if (item.mediaId !in originals) originals[item.mediaId] = item
            }
            val queue = snapshot.playOrder.map { it.toRecommendationOccurrence(metadataOf) }
            val existingTailSize =
                if (snapshot.currentIndex < 0) {
                    0
                } else {
                    (snapshot.currentIndex + 1 until queue.size).count { index ->
                        queue[index].occurrenceId in pending && index !in snapshot.manualIndices
                    }
                }
            val plan =
                recommendation.planTail(
                    candidates = originals.values.map { it.toRecommendationOccurrence(metadataOf) },
                    queue = queue,
                    seed = queueTailSeed(queue.firstOrNull()?.item?.ref),
                    budget = QueueTailRecommendation.DEFAULT_TAIL_BUDGET,
                    existingTailSize = existingTailSize,
                )
            plan.items.mapNotNull { originals[it.occurrenceId] }
        }
    }

    /**
     * Post-ranking eligibility for recommendation candidates only. It re-reads the existing per-song block table, not
     * the 500-row listening window, so a song blocked while scoring suspends cannot be appended. The read and the
     * filtering stay on the adapter's dispatcher.
     */
    suspend fun filterBlockedSongs(items: List<MediaItem>): List<MediaItem> {
        if (items.isEmpty()) return items
        return withContext(computeDispatcher) {
            val blocked = blockedSongIds()
            if (blocked.isEmpty()) items else items.filterNot { it.mediaId in blocked }
        }
    }
}

/**
 * The provider identity retained by a queued MediaItem. A local or Telegram media id keeps its own provider instead
 * of being labelled YouTube; a missing metadata tag leaves credits and duration unknown rather than inferring them.
 */
internal fun MediaItem.toRecommendationOccurrence(
    metadataOf: (MediaItem) -> MediaMetadata? = { it.metadata },
): ContentOccurrence {
    val provider = mediaId.recommendationProvider()
    val canonical = metadataOf(this)?.canonicalMetadata()
    if (canonical == null) {
        return ContentOccurrence(
            occurrenceId = mediaId,
            item =
                ContentItem(
                    ref = ContentRef(provider, ContentKind.SONG, mediaId),
                    title = mediaId,
                ),
        )
    }
    val id = canonical.id.takeIf { it.isNotBlank() } ?: mediaId
    return ContentOccurrence(
        occurrenceId = mediaId,
        item =
            ContentItem(
                ref = ContentRef(provider, ContentKind.SONG, id),
                title = canonical.title,
                durationMs = canonical.duration.takeIf { it > 0 }?.toLong()?.times(1000L),
                explicit = canonical.explicit,
                album = canonical.album?.title,
                artists =
                    canonical.artists.mapNotNull { artist ->
                        artist.id?.takeIf { it.isNotBlank() }?.let { ContentCredit(artist.name, it) }
                    },
                albumId = canonical.album?.id?.takeIf { it.isNotBlank() },
                isVideo = canonical.isMusicVideo,
                isrc = canonical.isrc?.takeIf { it.isNotBlank() },
            ),
    )
}

private fun String.recommendationProvider(): String =
    when {
        startsWith("content://", ignoreCase = true) ||
            startsWith("file://", ignoreCase = true) ||
            startsWith("android.resource://", ignoreCase = true) -> ContentProvider.LOCAL.id
        startsWith("telegram://", ignoreCase = true) -> ContentProvider.TELEGRAM.id
        else -> ContentProvider.YOUTUBE.id
    }

/** One service-owned adapter over the real portable database and the host's wall clock/local zone. */
internal fun androidQueueTailRecommendation(
    database: DatabaseDao,
    automaticIds: Set<String>,
): AndroidQueueTailRecommendation =
    AndroidQueueTailRecommendation(
        recommendation =
            QueueTailRecommendation(
                signals = AndroidRecommendationSignals(database),
                nowMs = System::currentTimeMillis,
                offsetMsAt = { epochMs -> TimeZone.getDefault().getOffset(epochMs).toLong() },
            ),
        automaticIds = automaticIds,
        // Production may read the existing local-configuration tag; fixtures use their own map seam instead.
        metadataOf = { it.metadata },
        blockedSongIds = { withContext(Dispatchers.IO) { database.getBlockedSongIds().toSet() } },
    )

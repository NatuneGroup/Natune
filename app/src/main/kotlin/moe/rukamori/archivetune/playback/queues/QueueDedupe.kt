package moe.rukamori.archivetune.playback.queues

/**
 * Android's insert-path mirror of shared-domain's [QueueDedupePolicy] behind the
 * `preventDuplicateTracksInQueue` setting: an inserted batch keeps one occurrence per media id,
 * a copy of the song already playing is dropped, and every other queued occurrence of an incoming
 * media id is evicted first, so a re-added song lands at its new position instead of appearing
 * twice. Identity is the bare media id — unlike the portable queue's provider-scoped reference, one
 * media id here names the same song whichever streaming source resolves it, and two ids of
 * different schemes (YouTube id, content:// uri, telegram key) never collide.
 */
internal object QueueDedupe {
    /** The media ids of [mediaIds] worth inserting: first per id, never the song now playing. */
    fun incoming(currentMediaId: String?, mediaIds: List<String>): List<String> =
        mediaIds.distinct().filterNot { it == currentMediaId }

    /**
     * Queue indices an insert of [incomingMediaIds] evicts first. The current index always
     * survives; callers remove the returned indices in descending order so earlier removals do not
     * renumber later ones.
     */
    fun evictableIndices(
        queueMediaIds: List<String>,
        currentIndex: Int,
        incomingMediaIds: Collection<String>,
    ): Set<Int> {
        val incoming = incomingMediaIds.toSet()
        return queueMediaIds.mapIndexedNotNullTo(LinkedHashSet()) { index, id ->
            index.takeIf { index != currentIndex && id in incoming }
        }
    }

    /**
     * The surviving incoming items in first-occurrence order: only items whose id [keptIds]
     * admitted, and only one item per id. [keptIds] is a set, so membership alone cannot drop the
     * second copy of an id — the distinctBy is what actually deduplicates the insert.
     */
    fun <T> survivingItems(
        items: List<T>,
        keptIds: Set<String>,
        mediaId: (T) -> String,
    ): List<T> = items.filter { mediaId(it) in keptIds }.distinctBy(mediaId)
}

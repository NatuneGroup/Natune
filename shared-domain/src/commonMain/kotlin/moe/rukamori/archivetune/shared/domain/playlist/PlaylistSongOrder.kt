/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.playlist

import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.library.PlaylistSongSortType
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/**
 * One stored row of a local playlist. [position] is the custom order the user arranges. [addedSeq] records when the
 * occurrence was added, which is the order CREATE_DATE sorts by. The two stay apart, because a custom move must not
 * change the order a song was added in.
 */
data class StoredPlaylistRow(
    val occurrence: ContentOccurrence,
    val position: Int,
    val addedSeq: Long,
)

/**
 * The playlist song orders and the custom move, as the baseline playlist screen applied them. The rules are pure, so
 * every platform runs the same ones. Only the artist collation comes from the host, because the baseline used the
 * device collator at primary strength and common code has no such collator.
 */
object PlaylistSongOrder {
    private const val KEY_SEPARATOR = "\u001F"

    /**
     * The key that groups one song's plays. The listening store writes it and PLAY_TIME reads it, so both sides agree
     * on what counts as the same song. The unit separator cannot appear in a provider name or in the stored ids.
     */
    fun playKey(ref: ContentRef): String = listOf(ref.provider, ref.kind.name, ref.id).joinToString(KEY_SEPARATOR)

    /**
     * The complete playlist in [sort]'s order. PLAY_TIME needs [playTimeMsBySong], the all-time play time keyed by
     * [playKey]. Without it the result is null, and a caller shows no list rather than substituting another order.
     *
     * The order is stable, and a descending order is the reverse of the whole ascending list, as the baseline did it.
     * A custom order is never reversed, because [LibrarySortState.effectiveDescending] is false for it.
     */
    fun ordered(
        rows: List<StoredPlaylistRow>,
        sort: LibrarySortState<PlaylistSongSortType>,
        collation: Comparator<String>,
        playTimeMsBySong: Map<String, Long>? = null,
    ): List<StoredPlaylistRow>? {
        val byPosition = rows.sortedBy { it.position }
        val ascending =
            when (sort.type) {
                PlaylistSongSortType.CUSTOM -> return byPosition
                PlaylistSongSortType.PLAY_TIME -> {
                    val totals = playTimeMsBySong ?: return null
                    byPosition.sortedBy { totals[playKey(it.occurrence.item.ref)] ?: 0L }
                }
                PlaylistSongSortType.CREATE_DATE -> byPosition.sortedBy { it.addedSeq }
                PlaylistSongSortType.NAME -> byPosition.sortedBy { it.occurrence.item.title }
                PlaylistSongSortType.ARTIST -> artistOrder(byPosition, collation)
            }
        return if (sort.effectiveDescending) ascending.reversed() else ascending
    }

    /**
     * The baseline ARTIST order. Songs sort by the artist text under [collation], then each album's songs stay
     * together in the order the albums were first seen. Inside an album they sort by plain artist text.
     * The item subtitle is the artist text the provider reported, which stands in for the baseline's joined
     * artist names.
     */
    private fun artistOrder(
        rows: List<StoredPlaylistRow>,
        collation: Comparator<String>,
    ): List<StoredPlaylistRow> {
        val byCollatedArtist =
            Comparator<StoredPlaylistRow> { left, right ->
                collation.compare(left.occurrence.item.subtitle, right.occurrence.item.subtitle)
            }
        return rows
            .sortedWith(byCollatedArtist)
            .groupBy { it.occurrence.item.album }
            .values
            .flatMap { albumRows -> albumRows.sortedBy { it.occurrence.item.subtitle } }
    }

    /**
     * Places the occurrence [occurrenceId] beside [neighbourOccurrenceId] in the custom order. It goes below the
     * neighbour when [after] is true. [customOrder] lists the occurrence ids by position. The result is the whole
     * new order, so the caller can renumber and write it in one unit. Returns null when a row is missing or when
     * both ids name one row.
     */
    fun moveBeside(
        customOrder: List<String>,
        occurrenceId: String,
        neighbourOccurrenceId: String,
        after: Boolean,
    ): List<String>? {
        if (occurrenceId == neighbourOccurrenceId) return null
        val remaining = customOrder.toMutableList()
        val from = remaining.indexOf(occurrenceId)
        if (from < 0) return null
        remaining.removeAt(from)
        val neighbour = remaining.indexOf(neighbourOccurrenceId)
        if (neighbour < 0) return null
        remaining.add(if (after) neighbour + 1 else neighbour, occurrenceId)
        return remaining
    }
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.queue

/**
 * Identifies the song a queue starts on. The caller resolves the video id and set id first, from the
 * preload item when there is one and otherwise from the endpoint.
 *
 * @property endpointIndex The endpoint's playlist index. It tells duplicates apart only when no set
 *   id is available.
 */
data class FinitePlaylistLocator(
    val videoId: String?,
    val setVideoId: String?,
    val endpointIndex: Int?,
)

/** The items before and after a selected entry. The selected entry itself is never reinserted. */
data class FinitePreloadSplit<T>(
    val prefix: List<T>,
    val suffix: List<T>,
)

object FinitePlaylistSelection {
    /**
     * The index of the one entry the locator names. Without a video id the first entry is selected.
     * A set video id must match exactly; without one, the endpoint index must match the entry's
     * original index. Several matches, or none, return null rather than guessing.
     */
    fun <T> selectedIndex(entries: List<FinitePlaylistEntry<T>>, locator: FinitePlaylistLocator): Int? {
        val videoId = locator.videoId ?: return 0
        val setId = locator.setVideoId?.takeIf(String::isNotBlank)
        val matches = entries.indices.filter { index ->
            val entry = entries[index]
            entry.videoId == videoId &&
                (if (setId != null) entry.setVideoId == setId else locator.endpointIndex != null && entry.originalIndex == locator.endpointIndex)
        }
        return matches.singleOrNull()
    }

    /**
     * The initial selection. While paging continues, null means "load more". Once the list is
     * exhausted, a video id that appears exactly once is the only safe fallback. Anything else is
     * incomplete rather than a guess at a duplicate.
     */
    fun <T> initialSelection(
        entries: List<FinitePlaylistEntry<T>>,
        locator: FinitePlaylistLocator,
        exhausted: Boolean,
    ): Int? {
        selectedIndex(entries, locator)?.let { return it }
        if (!exhausted) return null
        val videoId = locator.videoId ?: return 0
        val matches = entries.indices.filter { entries[it].videoId == videoId }
        if (matches.size != 1) throw IncompleteFinitePlaylistException()
        return matches.single()
    }

    /**
     * Splits [items] around the entry whose key is [selectedKey]. The preload inserts the prefix
     * ahead of the current song and the suffix behind it, so the current song is never restarted.
     * A missing key means the preload no longer matches the list, and that is incomplete.
     */
    fun <T> splitAroundSelection(
        items: List<T>,
        selectedKey: String,
        keyOf: (T) -> String?,
    ): FinitePreloadSplit<T> {
        val index = items.indexOfFirst { keyOf(it) == selectedKey }
        if (index < 0) throw IncompleteFinitePlaylistException()
        return FinitePreloadSplit(items.take(index), items.drop(index + 1))
    }
}

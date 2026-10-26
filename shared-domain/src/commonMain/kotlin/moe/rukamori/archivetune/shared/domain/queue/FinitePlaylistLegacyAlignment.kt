package moe.rukamori.archivetune.shared.domain.queue

/**
 * The restored queue: [entries] in timeline order, and [savedIndices] giving where each saved item
 * landed in that timeline.
 */
data class LegacyFiniteAlignment<T>(
    val entries: List<FinitePlaylistEntry<T>>,
    val savedIndices: List<Int>,
)

object FinitePlaylistLegacyAlignment {
    /**
     * Aligns a saved timeline (from a legacy queue with no occurrence keys) onto the freshly loaded
     * playlist. Only a unique, strictly increasing alignment is recoverable. An anonymous duplicate
     * or an edited source makes the whole alignment fail, and the saved timeline is left untouched.
     * Items the user added by hand are the only ones allowed to go unmatched.
     *
     * @return null when the alignment cannot be proven.
     */
    fun <T> align(
        saved: List<FinitePlaylistSong<T>>,
        source: List<FinitePlaylistEntry<T>>,
        manualIndices: Set<Int> = emptySet(),
    ): LegacyFiniteAlignment<T>? {
        val positions = ArrayList<Int?>(saved.size)
        for ((index, item) in saved.withIndex()) {
            if (index in manualIndices) {
                positions += null
                continue
            }
            val matches = source.indices.filter { sourceIndex ->
                val remote = source[sourceIndex]
                remote.videoId == item.videoId &&
                    (item.setVideoId.isNullOrBlank() || remote.setVideoId == item.setVideoId)
            }
            if (matches.size != 1) return null
            positions += matches.single()
        }
        val known = positions.filterNotNull()
        if (known.isEmpty() || known.zipWithNext().any { (before, after) -> before >= after }) return null

        val merged = mutableListOf<FinitePlaylistEntry<T>>()
        val savedIndices = mutableListOf<Int>()
        var nextSource = 0
        saved.forEachIndexed { index, item ->
            val sourceIndex = positions[index]
            if (sourceIndex != null) {
                merged += source.subList(nextSource, sourceIndex)
                savedIndices += merged.size
                // The saved identity replaces the fresh one, as the Android restore does.
                merged += source[sourceIndex].copy(
                    item = item.item,
                    videoId = item.videoId,
                    setVideoId = item.setVideoId,
                )
                nextSource = sourceIndex + 1
            } else {
                savedIndices += merged.size
                merged += FinitePlaylistEntry(item.item, item.videoId, item.setVideoId, "manual:$index", null)
            }
        }
        merged += source.subList(nextSource, source.size)
        return LegacyFiniteAlignment(merged, savedIndices)
    }
}

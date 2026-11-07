package moe.rukamori.archivetune.playback

/**
 * Keeps the full child list of each Android Auto browse folder between page requests.
 *
 * A car loads a folder one page at a time. Several folders are built from a fresh shuffle or a
 * live network feed, so rebuilding them for page 1+ returned rows that disagreed with page 0 and
 * Android Auto reset the list, snapping the scroll back to the top. Page 0 is what a car asks for
 * when it opens a folder, so it reloads and replaces the snapshot; later pages slice that snapshot.
 */
internal class AutoBrowseChildrenCache<T>(
    private val maxEntries: Int = 16,
) {
    private val entries =
        object : LinkedHashMap<String, List<T>>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<T>>?): Boolean = size > maxEntries
        }

    suspend fun children(
        parentId: String,
        page: Int,
        load: suspend () -> List<T>,
    ): List<T> {
        if (page > 0) synchronized(entries) { entries[parentId] }?.let { return it }
        val loaded = load()
        synchronized(entries) { entries[parentId] = loaded }
        return loaded
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}

/** One Media3 browse page; a negative page or non-positive size means "the whole list". */
internal fun <T> List<T>.autoBrowsePage(
    page: Int,
    pageSize: Int,
): List<T> {
    if (page < 0 || pageSize <= 0) return this
    val from = page.toLong() * pageSize.toLong()
    if (from >= size) return emptyList()
    val to = minOf(from + pageSize, size.toLong()).toInt()
    return subList(from.toInt(), to)
}

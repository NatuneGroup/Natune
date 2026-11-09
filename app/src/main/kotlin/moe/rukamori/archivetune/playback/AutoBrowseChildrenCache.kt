package moe.rukamori.archivetune.playback

import kotlinx.coroutines.CompletableDeferred

/**
 * Keeps the full child list of each Android Auto browse folder between page requests.
 *
 * A car loads a folder one page at a time. Several folders are built from a fresh shuffle or a
 * live network feed, so rebuilding them for page 1+ returned rows that disagreed with page 0 and
 * Android Auto reset the list, snapping the scroll back to the top. A car can request page 0 again
 * while the folder is still open; it is not a reliable signal that the user reopened the folder.
 * Every page uses the same snapshot until relevant content changes or cache eviction.
 */
internal class AutoBrowseChildrenCache<T>(
    private val maxEntries: Int = 16,
) {
    private val lock = Any()
    private val entries =
        object : LinkedHashMap<String, List<T>>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<T>>?): Boolean = size > maxEntries
        }
    // One in-flight load per folder. Concurrent callers await the same Deferred instead of each
    // rebuilding the (possibly shuffled or network-backed) list and overwriting the snapshot.
    private val inFlight = HashMap<String, CompletableDeferred<List<T>>>()

    suspend fun children(
        parentId: String,
        load: suspend () -> List<T>,
    ): List<T> = children(parentId, cacheEmpty = true, load = load)

    suspend fun children(
        parentId: String,
        cacheEmpty: Boolean,
        load: suspend () -> List<T>,
    ): List<T> {
        var owner = false
        var snapshot: List<T>? = null
        var deferred: CompletableDeferred<List<T>>? = null
        synchronized(lock) {
            // Concurrent requests, including repeated page 0 requests, share one folder load.
            val active = inFlight[parentId]?.takeIf { it.isActive }
            val committed = entries[parentId]
            when {
                active != null -> deferred = active
                committed != null -> snapshot = committed
                else -> {
                    val pending = CompletableDeferred<List<T>>()
                    inFlight[parentId] = pending
                    deferred = pending
                    owner = true
                }
            }
        }
        snapshot?.let { return it }
        val pending = deferred ?: error("no in-flight load and no snapshot")
        if (!owner) return pending.await()

        // Only the owner runs load(), once. A failure is shared with every awaiter, then the folder
        // is dropped from inFlight so the next request can retry.
        val loaded =
            try {
                load().toList()
            } catch (t: Throwable) {
                synchronized(lock) {
                    if (inFlight[parentId] === pending) inFlight.remove(parentId)
                }
                pending.completeExceptionally(t)
                throw t
            }
        synchronized(lock) {
            // Invalidation detaches this folder's pending load. Its older callers can finish,
            // but it cannot overwrite a fresh snapshot or remove a newer in-flight request.
            if (inFlight[parentId] === pending) {
                if (cacheEmpty || loaded.isNotEmpty()) entries[parentId] = loaded
                inFlight.remove(parentId)
            }
        }
        // Complete outside the lock: an awaiter may resume immediately and re-enter children().
        pending.complete(loaded)
        return loaded
    }

    /** The committed snapshot, if any, without starting a load. */
    fun snapshot(parentId: String): List<T>? = synchronized(lock) { entries[parentId] }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            inFlight.clear()
        }
    }

    fun invalidate(matches: (String) -> Boolean): Set<String> = synchronized(lock) {
        val affected = (entries.keys + inFlight.keys).filter(matches).toSet()
        affected.forEach { entries.remove(it); inFlight.remove(it) }
        affected
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

package moe.rukamori.archivetune.playback

import kotlinx.coroutines.CompletableDeferred

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
    private val lock = Any()
    private val entries =
        object : LinkedHashMap<String, List<T>>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<T>>?): Boolean = size > maxEntries
        }
    // One in-flight load per folder. Concurrent callers await the same Deferred instead of each
    // rebuilding the (possibly shuffled or network-backed) list and overwriting the snapshot.
    private val inFlight = HashMap<String, CompletableDeferred<List<T>>>()
    // Bumped by clear() so a load that started before a library refresh can never repopulate the
    // cache with the now-stale list. refreshAutomotiveLibrary() clears while older page loads may
    // still be suspended on a network/shuffle rebuild.
    private var generation = 0L

    suspend fun children(
        parentId: String,
        page: Int,
        load: suspend () -> List<T>,
    ): List<T> {
        var owner = false
        var snapshot: List<T>? = null
        var deferred: CompletableDeferred<List<T>>? = null
        var startedGeneration = 0L
        synchronized(lock) {
            // An active load wins over a possibly-stale committed snapshot, so a page 1+ request
            // that races a page 0 reload awaits the new list rather than serving the old one.
            val active = inFlight[parentId]?.takeIf { it.isActive }
            val committed = if (page > 0) entries[parentId] else null
            when {
                active != null -> deferred = active
                committed != null -> snapshot = committed
                else -> {
                    startedGeneration = generation
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
                load()
            } catch (t: Throwable) {
                synchronized(lock) {
                    if (inFlight[parentId] === pending) inFlight.remove(parentId)
                }
                pending.completeExceptionally(t)
                throw t
            }
        synchronized(lock) {
            // A clear() during load() bumped the generation; the result is still returned to the
            // caller that requested it, but it must NOT seed a snapshot: later pages would then
            // read a list built before the library refresh. A page request made after clear()
            // finds no snapshot and reloads under the new generation.
            if (generation == startedGeneration) entries[parentId] = loaded
            if (inFlight[parentId] === pending) inFlight.remove(parentId)
        }
        // Complete outside the lock: an awaiter may resume immediately and re-enter children().
        pending.complete(loaded)
        return loaded
    }

    fun clear() {
        synchronized(lock) {
            generation++
            entries.clear()
            inFlight.clear()
        }
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

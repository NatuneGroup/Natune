package moe.rukamori.archivetune.playback

/** Retain subscribed folder IDs even if their snapshots have been evicted from the bounded cache. */
internal class AutoBrowseFolderUpdates<T> {
    private val cache = AutoBrowseChildrenCache<T>()
    private val requestedParents = mutableSetOf<String>()

    suspend fun children(parentId: String, load: suspend () -> List<T>): List<T> = children(parentId, cacheEmpty = true, load = load)

    suspend fun children(parentId: String, cacheEmpty: Boolean, load: suspend () -> List<T>): List<T> {
        synchronized(requestedParents) { requestedParents.add(parentId) }
        return cache.children(parentId, cacheEmpty, load)
    }

    fun invalidate(
        change: AutoBrowseContentChange,
        hasSubscribers: (String) -> Boolean,
        notify: (String) -> Unit,
    ) {
        val affected = cache.invalidate(change::affects)
        val subscribed = synchronized(requestedParents) {
            requestedParents.removeAll { !hasSubscribers(it) }
            requestedParents.filter(change::affects)
        }
        (affected + subscribed).forEach(notify)
    }

    fun snapshot(parentId: String): List<T>? = cache.snapshot(parentId)

    fun clear() = cache.clear()

    /** Availability changes re-filter the same snapshots, preserving their shuffle order. */
    fun availabilityChanged(hasSubscribers: (String) -> Boolean, notify: (String) -> Unit) {
        val subscribed = synchronized(requestedParents) {
            requestedParents.removeAll { !hasSubscribers(it) }
            requestedParents.toList()
        }
        subscribed.forEach(notify)
    }
}

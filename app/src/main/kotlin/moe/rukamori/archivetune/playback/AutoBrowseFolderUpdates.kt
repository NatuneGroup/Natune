package moe.rukamori.archivetune.playback

/** Retain subscribed folder IDs even if their snapshots have been evicted from the bounded cache. */
internal class AutoBrowseFolderUpdates<T> {
    private val cache = AutoBrowseChildrenCache<T>()
    private val requestedParents = mutableSetOf<String>()

    suspend fun children(parentId: String, load: suspend () -> List<T>): List<T> {
        synchronized(requestedParents) { requestedParents.add(parentId) }
        return cache.children(parentId, load)
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

    fun clear() = cache.clear()
}

package moe.rukamori.archivetune.ui.theme

/**
 * A small access-ordered memo for artwork-derived values.
 *
 * Artwork extraction is expensive (a Coil decode plus a palette pass) and its inputs repeat
 * constantly: the same row scrolls back into view, every player surface asks for the current
 * track, a queue is swiped through twice. Remembering the answer is the whole win, but the
 * memory has to stay honest, so this is bounded and evicts the least recently *used* entry
 * rather than the oldest inserted one.
 */
internal class BoundedArtworkMemo<K : Any, V : Any>(
    private val maxEntries: Int,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)

    fun get(key: K): V? = synchronized(entries) { entries[key] }

    fun put(
        key: K,
        value: V,
    ) {
        synchronized(entries) {
            entries[key] = value
            while (entries.size > maxEntries) {
                val eldest = entries.entries.iterator()
                if (!eldest.hasNext()) return
                eldest.next()
                eldest.remove()
            }
        }
    }
}

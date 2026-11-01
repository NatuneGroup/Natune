package moe.rukamori.archivetune.shared.domain.source

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bounded process-only URL material. Invalidation also retires in-flight writers, even when no entry exists yet.
 * The generation and entries change in one CAS, so a check followed by a stale write cannot resurrect a rejected URL.
 */
class StreamUrlCache<K, V>(private val capacity: Int = 256) {
    class Generation internal constructor()

    private data class State<K, V>(val generation: Generation, val entries: Map<K, V>)
    private val state = MutableStateFlow(State<K, V>(Generation(), emptyMap()))

    init { require(capacity > 0) }

    val generation: Generation get() = state.value.generation
    val size: Int get() = state.value.entries.size
    val keys: Set<K> get() = state.value.entries.keys

    operator fun get(key: K): V? = state.value.entries[key]

    fun putIfCurrent(key: K, value: V, generation: Generation): Boolean {
        while (true) {
            val previous = state.value
            if (previous.generation !== generation) return false
            val entries = LinkedHashMap(previous.entries)
            entries.remove(key)
            entries[key] = value
            while (entries.size > capacity) entries.remove(entries.keys.first())
            if (state.compareAndSet(previous, State(generation, entries))) return true
        }
    }

    fun remove(key: K): V? {
        while (true) {
            val previous = state.value
            val value = previous.entries[key]
            if (state.compareAndSet(previous, State(Generation(), previous.entries - key))) return value
        }
    }

    fun remove(key: K, value: V): Boolean {
        while (true) {
            val previous = state.value
            if (previous.entries[key] != value) return false
            if (state.compareAndSet(previous, State(Generation(), previous.entries - key))) return true
        }
    }

    /** Routine expiry eviction is not a rejection; do not cancel another song's in-flight resolve. */
    fun discard(key: K, value: V): Boolean {
        while (true) {
            val previous = state.value
            if (previous.entries[key] != value) return false
            if (state.compareAndSet(previous, State(previous.generation, previous.entries - key))) return true
        }
    }

    fun invalidateWhere(predicate: (K) -> Boolean) {
        while (true) {
            val previous = state.value
            val next = State(Generation(), previous.entries.filterKeys { !predicate(it) })
            if (state.compareAndSet(previous, next)) return
        }
    }

    fun clear() = invalidateWhere { true }

    fun retainKeys(predicate: (K) -> Boolean) {
        while (true) {
            val previous = state.value
            val entries = previous.entries.filterKeys(predicate)
            if (entries.size == previous.entries.size) return
            if (state.compareAndSet(previous, State(Generation(), entries))) return
        }
    }
}

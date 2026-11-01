package moe.rukamori.archivetune.shared.domain.source

import kotlinx.coroutines.flow.MutableStateFlow

/** One fresh URL per source and song. Becoming ready must not refill this budget. */
class StreamRecoveryTracker<S> {
    private data class Attempts<S>(
        val refreshed: Set<S> = emptySet(),
        val rejected: Set<S> = emptySet(),
        val refreshSource: S? = null,
    )
    private val states = MutableStateFlow<Map<String, Attempts<S>>>(emptyMap())

    /** True requests the source's single refresh; false rejects it and requests the ordinary next source. */
    fun onFailure(song: String, source: S): Boolean {
        while (true) {
            val previous = states.value
            val attempts = previous[song] ?: Attempts()
            val refresh = source !in attempts.refreshed && source !in attempts.rejected
            val next = if (refresh) attempts.copy(refreshed = attempts.refreshed + source, refreshSource = source)
                else attempts.copy(rejected = attempts.rejected + source, refreshSource = null)
            if (states.compareAndSet(previous, previous + (song to next))) return refresh
        }
    }

    fun refreshSource(song: String): S? = states.value[song]?.refreshSource
    fun rejectedSources(song: String): Set<S> = states.value[song]?.rejected.orEmpty()

    fun reject(song: String, source: S) = update(song) {
        it.copy(rejected = it.rejected + source, refreshSource = null)
    }

    fun resolved(song: String) = update(song) { it.copy(refreshSource = null) }

    fun clear(song: String) {
        while (true) {
            val previous = states.value
            if (states.compareAndSet(previous, previous - song)) return
        }
    }

    fun clear() { states.value = emptyMap() }

    fun retainSongs(keep: (String) -> Boolean) {
        while (true) {
            val previous = states.value
            if (states.compareAndSet(previous, previous.filterKeys(keep))) return
        }
    }

    private fun update(song: String, transform: (Attempts<S>) -> Attempts<S>) {
        while (true) {
            val previous = states.value
            val attempts = previous[song] ?: return
            if (states.compareAndSet(previous, previous + (song to transform(attempts)))) return
        }
    }
}

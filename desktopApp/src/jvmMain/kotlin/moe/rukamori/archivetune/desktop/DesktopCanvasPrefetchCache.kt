package moe.rukamori.archivetune.desktop

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.canvas.CanvasSource

/**
 * Canvases resolved ahead for upcoming queue rows, keyed by occurrence id and bounded at [capacity]. The window's
 * resolve loop and its prefetch children run on different dispatcher threads, so every access goes through one lock.
 * An entry belongs to the canvas preference probe it was resolved under (toggles, source, network policy, endpoints
 * and the Spotify catalog identity): when the probe changes the whole cache goes, so a canvas fetched for a signed-out
 * account or a provider the user has since closed is never replayed.
 */
internal class DesktopCanvasPrefetchCache(private val capacity: Int) {
    private val lock = Mutex()
    private val entries = LinkedHashMap<String, DesktopCanvasTarget>()
    private var probe: List<Any?>? = null

    /** Drops every entry when [current] differs from the probe the cache was filled under. */
    suspend fun retainProbe(current: List<Any?>) = lock.withLock { adopt(current) }

    suspend fun contains(occurrenceId: String): Boolean = lock.withLock { occurrenceId in entries }

    /**
     * Removes and returns the entry for [occurrenceId], or null when it is missing, was resolved under another probe,
     * or [allowed] no longer accepts its provider.
     */
    suspend fun take(
        occurrenceId: String,
        current: List<Any?>,
        allowed: (CanvasSource) -> Boolean,
    ): DesktopCanvasTarget? = lock.withLock {
        adopt(current)
        entries.remove(occurrenceId)?.takeIf { hit -> hit.provider?.let(allowed) == true }
    }

    /**
     * Stores [target] resolved under [resolvedUnder]. A prefetch whose generation was cancelled while it resolved, or
     * whose probe is no longer the cache's, is refused so an obsolete lookahead cannot land after the fact.
     */
    suspend fun put(target: DesktopCanvasTarget, resolvedUnder: List<Any?>): Boolean = lock.withLock {
        if (!currentCoroutineContext().isActive || probe != resolvedUnder) return@withLock false
        entries.remove(target.occurrenceId)
        entries[target.occurrenceId] = target
        while (entries.size > capacity) entries.remove(entries.keys.first())
        true
    }

    internal suspend fun size(): Int = lock.withLock { entries.size }

    private fun adopt(current: List<Any?>) {
        if (probe == current) return
        entries.clear()
        probe = current
    }
}

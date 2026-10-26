/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.playback

/**
 * One spectral result is valid only for the engine source it was measured from and the position it was decoded
 * at. Keying on the source string rather than the locator means a changed URL or path can never inherit a verdict.
 */
internal data class SpectralCacheKey(val source: String, val startPositionMs: Long)

/**
 * A bounded, least-recently-used store of conclusive spectral results. The capacity keeps memory fixed however many
 * sources a session opens. Access is guarded by the map's own monitor, because callers arrive on any thread and must
 * never wait on the playback executor.
 */
internal class SourceBoundSpectralCache(private val capacity: Int) {
    init {
        require(capacity > 0) { "the spectral cache needs room for at least one result, was $capacity" }
    }

    private val entries = object : LinkedHashMap<SpectralCacheKey, DesktopSpectralEvidence>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SpectralCacheKey, DesktopSpectralEvidence>?): Boolean =
            size > capacity
    }

    fun get(key: SpectralCacheKey): DesktopSpectralEvidence? = synchronized(entries) { entries[key] }

    fun put(key: SpectralCacheKey, evidence: DesktopSpectralEvidence) {
        synchronized(entries) { entries[key] = evidence }
    }
}

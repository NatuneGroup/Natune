/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.playback

import kotlin.time.TimeSource

/**
 * A non-decreasing time source on the base that [PlaybackSnapshot.sampleMonotonicNanos] uses.
 * Engines stamp snapshots with it and readers interpolate against it, so both sides share one
 * timeline even when wall-clock time jumps.
 */
fun interface MonotonicClock {
    fun nowNanos(): Long
}

/** The platform monotonic clock, measured from its first use in this process. */
object SystemMonotonicClock : MonotonicClock {
    private val origin = TimeSource.Monotonic.markNow()

    override fun nowNanos(): Long = origin.elapsedNow().inWholeNanoseconds
}

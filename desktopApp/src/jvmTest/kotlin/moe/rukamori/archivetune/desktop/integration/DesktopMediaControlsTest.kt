/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration

import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.playback.*

class DesktopMediaControlsTest {
    @Test fun duplicateMediaGetsDistinctOpaqueTrackPaths() {
        assertNotEquals(trackPath("first"), trackPath("second"))
        assertTrue(trackPath("a/b?private").matches(Regex("/org/mpris/MediaPlayer2/track/[a-f0-9]+")))
    }

    @Test fun setPositionChecksOccurrenceAgainOnOwnerThread() {
        val pending = mutableListOf<() -> Unit>()
        val actions = mutableListOf<DesktopTransportAction>()
        var current = sample("first")
        val controls = DesktopMediaControls({ current }, { pending += it }, actions::add)
        controls.setPosition(trackPath("first"), 1_000_000)
        current = sample("second")
        pending.single().invoke()
        assertTrue(actions.isEmpty())
    }

    @Test fun seekClampsAndDoesNotOverflow() {
        val actions = mutableListOf<DesktopTransportAction>()
        val controls = DesktopMediaControls({ sample() }, { it() }, actions::add)
        controls.seek(Long.MAX_VALUE)
        controls.setPosition(trackPath("first"), -1)
        controls.setPosition(trackPath("first"), 500_000)
        assertEquals(listOf<DesktopTransportAction>(DesktopTransportAction.SeekTo("first", 10_000), DesktopTransportAction.SeekTo("first", 500)), actions)
    }

    @Test fun bufferingIsNotReportedAsUserPauseAndSeekedRequiresObservedDiscontinuity() {
        val first = sample().copy(playWhenReady = true, isBuffering = true)
        assertEquals("Playing", first.mprisStatus())
        assertFalse(isSeekDiscontinuity(first, first.copy(positionMs = 4000)))
        assertTrue(isSeekDiscontinuity(first, first.copy(discontinuityGeneration = 1)))
        assertFalse(isSeekDiscontinuity(first, first.copy(occurrenceId = "second", discontinuityGeneration = 1)))
    }

    @Test fun unseekableOrEndedSnapshotsCannotBeScrubbed() {
        val actions = mutableListOf<DesktopTransportAction>()
        var current = sample().copy(isSeekable = false)
        val controls = DesktopMediaControls({ current }, { it() }, actions::add)
        controls.seek(1000)
        current = sample().copy(isEnded = true)
        controls.setPosition(trackPath("first"), 1000)
        assertTrue(actions.isEmpty())
    }

    companion object {
        fun sample(occurrence: String = "first") = PlaybackSnapshot(
            occurrence, "duplicate-media", 1, 0, 1000, 0, 10_000, true, 1f,
            false, false, false, PlaybackOutputRoute.LOCAL,
        )
    }
}

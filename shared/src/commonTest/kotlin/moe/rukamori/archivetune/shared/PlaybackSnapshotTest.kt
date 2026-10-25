/*
 * ArchiveTune KMP independent foundation
 * SPDX-License-Identifier: GPL-3.0-or-later
 * This program is free software: you can redistribute it and/or modify it under
 * the GNU General Public License, version 3 or (at your option) any later version.
 * This program is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/>.
 */
package moe.rukamori.archivetune.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PlaybackSnapshotTest {
    private val epoch = PlaybackEpoch("queue-occurrence-1", 4)
    private fun sample() = PlaybackSnapshot(
        epoch = epoch, positionMillis = 200, observedAtNanos = 1_000_000_000,
        durationMillis = 2_000, speed = 1.5, playRequested = true,
        actuallyAdvancing = true,
    )

    @Test fun advancesUsingMediaSpeedNotIntent() {
        assertEquals(950, sample().positionAt(1_500_000_000))
        assertEquals(950, sample().copy(playRequested = false).positionAt(1_500_000_000))
    }
    @Test fun pauseAndBufferingFreezeDespitePlayRequest() {
        assertEquals(200, sample().copy(actuallyAdvancing = false).positionAt(1_500_000_000))
    }
    @Test fun durationBoundsProjectedPosition() {
        assertEquals(2_000, sample().copy(speed = 8.0).positionAt(1_500_000_000))
        assertEquals(0, sample().copy(positionMillis = 0, durationMillis = 0).positionAt(1_500_000_000))
    }
    @Test fun unknownDurationIsNotZero() {
        assertEquals(950, sample().copy(durationMillis = null).positionAt(1_500_000_000))
    }
    @Test fun staleFutureHiddenAndRemoteSamplesNeverExtrapolate() {
        assertEquals(200, sample().positionAt(2_000_000_001))
        assertEquals(200, sample().positionAt(999_999_999))
        assertEquals(200, sample().positionAt(1_500_000_000, visibleAndResumed = false))
        assertEquals(200, sample().copy(authority = ClockAuthority.REMOTE).positionAt(1_500_000_000))
    }
    @Test fun seekAndRepeatedTrackOccurrencesRejectOldSample() {
        assertNull(sample().positionAt(1_500_000_000, expectedEpoch = epoch.copy(discontinuity = 5)))
        assertNull(sample().positionAt(1_500_000_000, expectedEpoch = epoch.copy(occurrenceId = "queue-occurrence-2")))
    }
    @Test fun freezingReanchorsWithoutSimulatingAnEngine() {
        val frozen = sample().freeze(1_500_000_000)
        assertEquals(950, frozen.positionAt(1_700_000_000))
        assertEquals(false, frozen.actuallyAdvancing)
        assertEquals(true, frozen.playRequested)
        assertEquals(epoch, frozen.epoch)
    }
    @Test fun rejectsInvalidInputRatherThanPropagatingNaN() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0).forEach {
            assertFailsWith<IllegalArgumentException> { sample().copy(speed = it) }
        }
        assertFailsWith<IllegalArgumentException> { sample().copy(positionMillis = -1) }
        assertFailsWith<IllegalArgumentException> { sample().copy(durationMillis = 100) }
        assertFailsWith<IllegalArgumentException> { PlaybackEpoch("", 0) }
        assertFailsWith<IllegalArgumentException> { PlaybackEpoch("occurrence", -1) }
    }
    @Test fun signedMonotonicOriginsAndHugeSpeedsAreBounded() {
        assertEquals(950, sample().copy(observedAtNanos = -1_000_000_000).positionAt(-500_000_000))
        assertEquals(Long.MAX_VALUE, sample().copy(durationMillis = null, speed = Double.MAX_VALUE).positionAt(1_500_000_000))
        assertEquals(200, sample().copy(observedAtNanos = Long.MIN_VALUE).positionAt(Long.MAX_VALUE))
    }
}

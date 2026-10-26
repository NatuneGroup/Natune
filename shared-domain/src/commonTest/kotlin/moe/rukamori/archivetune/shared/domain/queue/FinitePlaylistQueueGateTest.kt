/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.queue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Characterizes the decisions that depend on how far a finite playlist is confirmed. Whole-list actions
 * and radio seeding wait for EXHAUSTED, so an unknown or failed tail never reaches them.
 */
class FinitePlaylistQueueGateTest {
    private val repeatOff = FinitePlaylistGates.REPEAT_MODE_OFF
    private val repeatOne = 1
    private val repeatAll = 2

    @Test
    fun repeatOffMatchesMedia3Zero() {
        assertEquals(0, FinitePlaylistGates.REPEAT_MODE_OFF)
    }

    @Test
    fun wholeListActionsNeedAnExhaustedList() {
        for (phase in FinitePlaylistPhase.entries) {
            assertEquals(
                phase == FinitePlaylistPhase.EXHAUSTED,
                FinitePlaylistGates.wholeListActionAllowed(phase),
                phase.name,
            )
        }
    }

    @Test
    fun recommendationsNeedAnExhaustedListWithAutoLoadAndRepeatOff() {
        assertTrue(FinitePlaylistGates.recommendationsAllowed(FinitePlaylistPhase.EXHAUSTED, true, repeatOff))

        assertFalse(FinitePlaylistGates.recommendationsAllowed(FinitePlaylistPhase.EXHAUSTED, false, repeatOff))
        assertFalse(FinitePlaylistGates.recommendationsAllowed(FinitePlaylistPhase.EXHAUSTED, true, repeatOne))
        assertFalse(FinitePlaylistGates.recommendationsAllowed(FinitePlaylistPhase.EXHAUSTED, true, repeatAll))
        for (phase in listOf(FinitePlaylistPhase.INITIALIZING, FinitePlaylistPhase.UNKNOWN, FinitePlaylistPhase.PAGEABLE, FinitePlaylistPhase.FAILED)) {
            assertFalse(FinitePlaylistGates.recommendationsAllowed(phase, true, repeatOff), phase.name)
        }
    }

    @Test
    fun unknownAndFailedTailsNeverSeedAFiniteRadioQueue() {
        for (phase in FinitePlaylistPhase.entries) {
            for (hasPreload in listOf(true, false)) {
                assertEquals(
                    phase == FinitePlaylistPhase.EXHAUSTED,
                    FinitePlaylistGates.infiniteQueueSeedAllowed(
                        isFiniteQueue = true,
                        phase = phase,
                        hasPreloadItem = hasPreload,
                        hasNextPage = false,
                    ),
                    "${phase.name} preload=$hasPreload",
                )
            }
        }
    }

    @Test
    fun infiniteQueueSeedsFromAPreloadItemOrAnExhaustedTail() {
        assertTrue(
            FinitePlaylistGates.infiniteQueueSeedAllowed(
                isFiniteQueue = false,
                phase = FinitePlaylistPhase.PAGEABLE,
                hasPreloadItem = true,
                hasNextPage = true,
            ),
        )
        assertTrue(
            FinitePlaylistGates.infiniteQueueSeedAllowed(
                isFiniteQueue = false,
                phase = FinitePlaylistPhase.UNKNOWN,
                hasPreloadItem = false,
                hasNextPage = false,
            ),
        )
        assertFalse(
            FinitePlaylistGates.infiniteQueueSeedAllowed(
                isFiniteQueue = false,
                phase = FinitePlaylistPhase.PAGEABLE,
                hasPreloadItem = false,
                hasNextPage = true,
            ),
        )
    }

    @Test
    fun onlyAPageableListHasANextPage() {
        for (phase in FinitePlaylistPhase.entries) {
            assertEquals(phase == FinitePlaylistPhase.PAGEABLE, FinitePlaylistGates.hasNextPage(phase), phase.name)
        }
    }
}

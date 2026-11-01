package moe.rukamori.archivetune.shared.domain.transition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransitionTwoDeckSchedulerTest {
    private val crossfade = TransitionPlan(kind = TransitionKind.CROSSFADE, startFrame = 1_000L, overlapFrames = 500L)

    @Test
    fun unarmedSchedulerPlaysTheActiveDeckAtUnity() {
        val scheduler = TwoDeckTransitionScheduler()
        assertEquals(TransitionDeck.FIRST, scheduler.activeDeck)
        assertEquals(TransitionGains(outgoing = 1f, incoming = 0f), scheduler.gainsAt(0L))
        assertNull(scheduler.armedIncomingDeck)
        assertFalse(scheduler.advanceTo(10_000L))
    }

    @Test
    fun armRefusesTheActiveDeckAndASecondPendingHandoff() {
        val scheduler = TwoDeckTransitionScheduler()
        assertFalse(scheduler.arm(TransitionDeck.FIRST, crossfade))
        assertTrue(scheduler.arm(TransitionDeck.SECOND, crossfade))
        assertFalse(scheduler.arm(TransitionDeck.FIRST, crossfade))
        assertEquals(TransitionDeck.SECOND, scheduler.armedIncomingDeck)
    }

    @Test
    fun armedCrossfadeCommitsExactlyAtTheEndOfTheOverlap() {
        val scheduler = TwoDeckTransitionScheduler()
        assertTrue(scheduler.arm(TransitionDeck.SECOND, crossfade))
        assertFalse(scheduler.advanceTo(1_499L))
        assertEquals(TransitionDeck.FIRST, scheduler.activeDeck)
        assertTrue(scheduler.advanceTo(1_500L))
        assertEquals(TransitionDeck.SECOND, scheduler.activeDeck)
        assertNull(scheduler.armedPlan)
        assertFalse(scheduler.advanceTo(1_500L))
    }

    @Test
    fun gaplessHandoffPromotesTheIncomingDeckAtTheSplitFrame() {
        val gapless = TransitionPlan(kind = TransitionKind.GAPLESS, startFrame = 2_000L, overlapFrames = 0L)
        val scheduler = TwoDeckTransitionScheduler()
        assertTrue(scheduler.arm(TransitionDeck.SECOND, gapless))
        assertEquals(TransitionGains(outgoing = 0f, incoming = 1f), scheduler.gainsAt(2_000L))
        assertFalse(scheduler.advanceTo(1_999L))
        assertTrue(scheduler.advanceTo(2_000L))
        assertEquals(TransitionDeck.SECOND, scheduler.activeDeck)
    }

    @Test
    fun seekDuringAFadeCommitsTheIncomingDeckAtOnce() {
        val scheduler = TwoDeckTransitionScheduler()
        scheduler.arm(TransitionDeck.SECOND, crossfade)
        assertTrue(scheduler.commitIncoming())
        assertEquals(TransitionDeck.SECOND, scheduler.activeDeck)
        assertNull(scheduler.armedIncomingDeck)
        assertFalse(scheduler.commitIncoming())
    }

    @Test
    fun cancelKeepsTheOutgoingDeckAuthoritative() {
        val scheduler = TwoDeckTransitionScheduler()
        scheduler.arm(TransitionDeck.SECOND, crossfade)
        scheduler.cancel()
        assertEquals(TransitionDeck.FIRST, scheduler.activeDeck)
        assertNull(scheduler.armedPlan)
        assertFalse(scheduler.advanceTo(10_000L))
        assertEquals(TransitionGains(outgoing = 1f, incoming = 0f), scheduler.gainsAt(1_200L))
    }

    @Test
    fun aNewHandoffCanBeArmedOntoTheOtherDeckAfterACommit() {
        val scheduler = TwoDeckTransitionScheduler()
        scheduler.arm(TransitionDeck.SECOND, crossfade)
        scheduler.advanceTo(1_500L)
        assertTrue(scheduler.arm(TransitionDeck.FIRST, crossfade.copy(startFrame = 3_000L)))
        assertEquals(TransitionDeck.FIRST, scheduler.armedIncomingDeck)
    }

    @Test
    fun adoptActiveSwapsTheDeckAndDropsAnyArmedHandoff() {
        val scheduler = TwoDeckTransitionScheduler()
        scheduler.arm(TransitionDeck.SECOND, crossfade)

        scheduler.adoptActive(TransitionDeck.SECOND)

        assertEquals(TransitionDeck.SECOND, scheduler.activeDeck)
        assertNull(scheduler.armedIncomingDeck)
        assertNull(scheduler.armedPlan)
        assertEquals(TransitionGains(outgoing = 1f, incoming = 0f), scheduler.gainsAt(1_200L))
        // The deck the engine dropped is armed again as the incoming one.
        assertTrue(scheduler.arm(TransitionDeck.FIRST, crossfade))
    }
}

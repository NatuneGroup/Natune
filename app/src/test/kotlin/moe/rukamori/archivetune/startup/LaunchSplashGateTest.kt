package moe.rukamori.archivetune.startup

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchSplashGateTest {
    @Test
    fun splashReleasesOnFirstCompositionWithoutWaitingForATimerOrARead() = runTest {
        val gate = LaunchSplashGate()
        var released = false
        val waiting =
            async {
                gate.awaitRelease()
                released = true
            }

        runCurrent()
        assertFalse(released)

        gate.contentComposed()
        runCurrent()

        assertTrue(released)
        assertTrue(waiting.isCompleted)
        assertEquals(0L, currentTime)
    }

    @Test
    fun contentThatComposedBeforeTheWaitStillReleasesWithoutHolding() = runTest {
        val gate = LaunchSplashGate()
        gate.contentComposed()

        gate.awaitRelease()

        assertEquals(0L, currentTime)
    }

    @Test
    fun theHoldIsFiniteWhenNoContentEverComposes() = runTest {
        val gate = LaunchSplashGate()
        var released = false
        val waiting =
            async {
                gate.awaitRelease()
                released = true
            }

        advanceTimeBy(LaunchSplashGate.HOLD_BUDGET_MS - 1)
        runCurrent()
        assertFalse(released)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(released)
        assertTrue(waiting.isCompleted)
    }

    @Test
    fun theFallbackBudgetStaysShorterThanTheRemovedTwoSecondReadWait() {
        assertTrue(LaunchSplashGate.HOLD_BUDGET_MS < 2_000L)
    }
}

package moe.rukamori.archivetune.shared.domain.recommend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListeningOutcomeTest {
    @Test
    fun aPlayUnderFiveSecondsIsAQuickSkip() {
        assertEquals(ListeningOutcome.QUICK_SKIP, ListeningOutcome.classify(4_999L, 180_000L))
    }

    @Test
    fun exactlyFiveSecondsIsAnEarlySkipWhenTheRatioIsUnderHalf() {
        assertEquals(ListeningOutcome.EARLY_SKIP, ListeningOutcome.classify(5_000L, 180_000L))
    }

    @Test
    fun aKnownDurationPastThirtySecondsIsPartialEvenWhenTheRatioIsLow() {
        assertEquals(ListeningOutcome.PARTIAL, ListeningOutcome.classify(30_000L, 180_000L))
    }

    @Test
    fun aPlayAtNinetyPercentIsComplete() {
        assertEquals(ListeningOutcome.COMPLETED, ListeningOutcome.classify(162_000L, 180_000L))
    }

    @Test
    fun aShortTrackPlayedToItsEndIsCompleteNotAQuickSkip() {
        assertEquals(ListeningOutcome.COMPLETED, ListeningOutcome.classify(3_000L, 3_000L))
    }

    @Test
    fun aShortTrackEndingWithinTheFiveSecondToleranceIsComplete() {
        assertEquals(ListeningOutcome.COMPLETED, ListeningOutcome.classify(26_000L, 30_000L))
    }

    @Test
    fun aZeroPlayIsAQuickSkipEvenWhenTheTrackIsShort() {
        assertEquals(ListeningOutcome.QUICK_SKIP, ListeningOutcome.classify(0L, 3_000L))
    }

    @Test
    fun anUnknownDurationCanNeverBeComplete() {
        assertEquals(ListeningOutcome.PARTIAL, ListeningOutcome.classify(180_000L, -1L))
        assertEquals(ListeningOutcome.PARTIAL, ListeningOutcome.classify(180_000L, null))
        assertEquals(ListeningOutcome.PARTIAL, ListeningOutcome.classify(180_000L, 0L))
    }

    @Test
    fun anUnknownDurationUnderThirtySecondsIsAnEarlySkip() {
        assertEquals(ListeningOutcome.EARLY_SKIP, ListeningOutcome.classify(29_999L, -1L))
    }

    @Test
    fun anUnknownDurationAtThirtySecondsIsPartial() {
        assertEquals(ListeningOutcome.PARTIAL, ListeningOutcome.classify(30_000L, -1L))
    }

    @Test
    fun anEarlySkipNeedsBothAThirtySecondFloorAndAHalfPlayedRatio() {
        assertEquals(ListeningOutcome.EARLY_SKIP, ListeningOutcome.classify(25_000L, 60_000L))
        assertEquals(ListeningOutcome.PARTIAL, ListeningOutcome.classify(25_000L, 40_000L))
    }

    @Test
    fun aNegativePlayTimeIsTreatedAsZero() {
        assertEquals(ListeningOutcome.QUICK_SKIP, ListeningOutcome.classify(-1L, 180_000L))
    }

    @Test
    fun outcomeWeightsAreSignedAndRankSkipsBelowCompletions() {
        assertTrue(ListeningOutcome.COMPLETED.affinityWeight > ListeningOutcome.PARTIAL.affinityWeight)
        assertTrue(ListeningOutcome.PARTIAL.affinityWeight > ListeningOutcome.EARLY_SKIP.affinityWeight)
        assertTrue(ListeningOutcome.EARLY_SKIP.affinityWeight > ListeningOutcome.QUICK_SKIP.affinityWeight)
        assertTrue(ListeningOutcome.QUICK_SKIP.affinityWeight < 0.0)
    }
}

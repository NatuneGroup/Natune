package moe.rukamori.archivetune.shared.data.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AiRateLimiterTest {
    @Test fun enforcesSpacingAndHourlyCap() = runTest {
        val limiter = AiRateLimiter({ testScheduler.currentTime }, { delay(it) })
        repeat(60) { limiter.withLimit(AiRateLimiter.Feature.LYRICS_TRANSLATION) { } }
        assertEquals(59_000L, testScheduler.currentTime)
        assertFailsWith<AiRateLimitException> { limiter.withLimit(AiRateLimiter.Feature.LYRICS_TRANSLATION) { } }
        advanceTimeBy(3_600_001)
        limiter.withLimit(AiRateLimiter.Feature.LYRICS_TRANSLATION) { }
    }
    @Test fun concurrentReservationsAreSpacedAndFeaturesIndependent() = runTest {
        val limiter = AiRateLimiter({ testScheduler.currentTime }, { delay(it) })
        val times = mutableListOf<Long>()
        coroutineScope {
            repeat(3) { launch { limiter.withLimit(AiRateLimiter.Feature.LYRICS_TRANSLATION) {
                times += testScheduler.currentTime
            } } }
        }
        assertEquals(listOf(0L, 1000L, 2000L), times)
        limiter.withLimit(AiRateLimiter.Feature.LYRICS_ROMANIZATION) { assertEquals(2000L, testScheduler.currentTime) }
    }
    @Test fun failureAndCancellationRefundTheirOwnNonTailReservation() = runTest {
        val limiter = AiRateLimiter({ testScheduler.currentTime }, { delay(it) })
        val first = launch {
            limiter.withLimit(AiRateLimiter.Feature.LYRICS_TRANSLATION) { awaitCancellation() }
        }
        runCurrent()
        advanceTimeBy(1_000)
        limiter.withLimit(AiRateLimiter.Feature.LYRICS_TRANSLATION) { }
        first.cancelAndJoin()
        repeat(59) { limiter.withLimit(AiRateLimiter.Feature.LYRICS_TRANSLATION) { } }
        assertFailsWith<AiRateLimitException> { limiter.withLimit(AiRateLimiter.Feature.LYRICS_TRANSLATION) { } }
        val other = AiRateLimiter({ testScheduler.currentTime }, { delay(it) })
        repeat(65) {
            assertFailsWith<IllegalStateException> {
                other.withLimit(AiRateLimiter.Feature.LYRICS_TRANSLATION) { error("fixture") }
            }
        }
        other.withLimit(AiRateLimiter.Feature.LYRICS_TRANSLATION) { }
    }
}

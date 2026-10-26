package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LyricsHelperCancellationTest {
    @Test
    fun aProviderThrowingCancellationMakesTheHelperRethrowIt() =
        runTest {
            val provider = FakeLyricsProvider("LrcLib") { throw CancellationException("provider cancelled") }
            val helper = helperOf(listOf(provider), backgroundScope)

            assertFailsWith<CancellationException> { helper.getLyricsWithProvider(request()) }
        }

    @Test
    fun anOrdinaryProviderFailureOnlyFailsThatProvider() =
        runTest {
            val failures = mutableListOf<String>()
            val broken = FakeLyricsProvider("LrcLib") { throw IllegalStateException("network down") }
            val helper = helperOf(listOf(broken, answering("Kugou", PLAIN_TEXT)), backgroundScope, failures = failures)

            val result = helper.getLyricsWithProvider(request())

            assertEquals("Kugou", result.providerName)
            assertEquals(listOf("LrcLib"), failures)
        }

    @Test
    fun cancellingTheCallerDuringFanOutLeavesNoProviderRunning() =
        runTest {
            val first = neverAnswering("LrcLib")
            val second = neverAnswering("Kugou")
            val helper = helperOf(listOf(first, second), backgroundScope)

            val caller = launch { helper.getLyricsWithProvider(request()) }
            runCurrent()
            assertEquals(1, first.inFlight)
            assertEquals(1, second.inFlight)

            caller.cancelAndJoin()
            advanceUntilIdle()

            assertEquals(0, first.inFlight)
            assertEquals(0, second.inFlight)
            assertEquals(1, first.cancelled)
            assertEquals(1, second.cancelled)
        }
}

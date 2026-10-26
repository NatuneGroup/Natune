/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsSentinels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LyricsHelperSingleFlightTest {
    @Test
    fun concurrentIdenticalRequestsShareOneProviderCallAndOneResult() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val provider = FakeLyricsProvider("LrcLib") { gate.await(); PLAIN_TEXT }
            val helper = helperOf(listOf(provider), backgroundScope)

            val first = async { helper.getLyricsWithProvider(request()) }
            val second = async { helper.getLyricsWithProvider(request()) }
            runCurrent()
            gate.complete(Unit)

            assertEquals(first.await(), second.await())
            assertEquals(1, provider.calls)
        }

    @Test
    fun differentTracksDoNotShareAFlight() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val provider = FakeLyricsProvider("LrcLib") { gate.await(); PLAIN_TEXT }
            val helper = helperOf(listOf(provider), backgroundScope)

            val first = async { helper.getLyricsWithProvider(request(id = "a")) }
            val second = async { helper.getLyricsWithProvider(request(id = "b")) }
            runCurrent()
            gate.complete(Unit)
            first.await()
            second.await()

            assertEquals(2, provider.calls)
        }

    @Test
    fun aFailedFetchIsRetriedByTheNextCall() =
        runTest {
            var answerLater = false
            val provider = FakeLyricsProvider("LrcLib") { if (answerLater) PLAIN_TEXT else null }
            val helper = helperOf(listOf(provider), backgroundScope)

            assertEquals(LyricsSentinels.NOT_FOUND, helper.getLyrics(request()))
            answerLater = true
            assertEquals(PLAIN_TEXT, helper.getLyrics(request()))
            assertEquals(2, provider.calls)
        }

    @Test
    fun aCancelledWaiterDoesNotCancelTheOthers() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val provider = FakeLyricsProvider("LrcLib") { gate.await(); PLAIN_TEXT }
            val helper = helperOf(listOf(provider), backgroundScope)

            val cancelled = launch { helper.getLyricsWithProvider(request()) }
            val remaining = async { helper.getLyricsWithProvider(request()) }
            runCurrent()
            cancelled.cancelAndJoin()
            gate.complete(Unit)

            assertEquals(PLAIN_TEXT, remaining.await().lyrics)
            assertEquals(1, provider.calls)
            assertEquals(0, provider.cancelled)
        }

    @Test
    fun cancellingTheLastWaiterCancelsTheSharedWorkAndALaterCallStartsAgain() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val provider = FakeLyricsProvider("LrcLib") { gate.await(); PLAIN_TEXT }
            val helper = helperOf(listOf(provider), backgroundScope)

            val only = launch { helper.getLyricsWithProvider(request()) }
            runCurrent()
            only.cancelAndJoin()
            advanceUntilIdle()

            assertEquals(1, provider.cancelled)
            gate.complete(Unit)
            assertEquals(PLAIN_TEXT, helper.getLyrics(request()))
            assertEquals(2, provider.calls)
        }

    @Test
    fun aSharedComputationThatFailsIsRemovedSoTheNextCallRetries() =
        runTest {
            val flight = LyricsSingleFlight<String, Int>(backgroundScope)
            var attempts = 0

            assertFailsWith<IllegalStateException> {
                flight.execute("key") {
                    attempts++
                    throw IllegalStateException("boom")
                }
            }
            assertEquals(7, flight.execute("key") { attempts++; 7 })
            assertEquals(2, attempts)
        }

    @Test
    fun aCompletedComputationIsNotKeptAsAResultByTheFlightItself() =
        runTest {
            val flight = LyricsSingleFlight<String, Int>(backgroundScope)
            var attempts = 0

            flight.execute("key") { attempts++; 1 }
            flight.execute("key") { attempts++; 2 }

            assertEquals(2, attempts)
        }
}

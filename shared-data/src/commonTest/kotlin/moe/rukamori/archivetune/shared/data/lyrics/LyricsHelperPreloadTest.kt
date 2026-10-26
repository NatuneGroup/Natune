package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsHelperPreloadTest {
    private fun upcoming(count: Int): List<LyricsRequest> = (1..count).map { request(id = "next$it") }

    @Test
    fun onlyTheNextThreeTracksAreLoaded() =
        runLyricsTest { scope ->
            val provider = answering("LrcLib", PLAIN_TEXT)
            val helper = helperOf(listOf(provider), scope)
            val manager = LyricsPreloadManager(helper, scope)

            manager.update(upcoming(5))
            advanceUntilIdle()

            assertEquals(setOf("next1", "next2", "next3"), provider.requestedIds.toSet())
            assertEquals(3, provider.calls)
        }

    @Test
    fun theMaximumInFlightPreloadsIsTwoWhenProvidersSuspend() =
        runLyricsTest { scope ->
            val gate = CompletableDeferred<Unit>()
            val provider = FakeLyricsProvider("LrcLib") { gate.await(); PLAIN_TEXT }
            val helper = helperOf(listOf(provider), scope)
            val manager = LyricsPreloadManager(helper, scope, nextCount = 5, maxConcurrent = 2)

            manager.update(upcoming(5))
            runCurrent()
            assertEquals(2, provider.inFlight)

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(2, provider.maxInFlight)
            assertEquals(5, provider.calls)
        }

    @Test
    fun aSongChangeCancelsPreloadsForOtherSongs() =
        runLyricsTest { scope ->
            val gate = CompletableDeferred<Unit>()
            val provider = FakeLyricsProvider("LrcLib") { gate.await(); PLAIN_TEXT }
            val helper = helperOf(listOf(provider), scope)
            val manager = LyricsPreloadManager(helper, scope, nextCount = 5, maxConcurrent = 2)

            manager.update(upcoming(5))
            runCurrent()
            manager.onSongChanged(request(id = "playing-now"))
            advanceUntilIdle()

            assertEquals(2, provider.cancelled)
            assertEquals(2, provider.calls)
        }

    @Test
    fun aSongChangeKeepsThePreloadForTheSongThatIsPlaying() =
        runLyricsTest { scope ->
            val gate = CompletableDeferred<Unit>()
            val provider = FakeLyricsProvider("LrcLib") { gate.await(); PLAIN_TEXT }
            val helper = helperOf(listOf(provider), scope)
            val manager = LyricsPreloadManager(helper, scope, nextCount = 3, maxConcurrent = 2)

            manager.update(upcoming(3))
            runCurrent()
            manager.onSongChanged(request(id = "next1"))
            // runCurrent, not advanceUntilIdle: advancing time would fire the 8 s fan-out timeout on the
            // kept preload too, and that timeout is also a cancellation the counter would record.
            runCurrent()

            assertEquals(1, provider.cancelled)
        }

    @Test
    fun updateCancelsPreloadsThatLeftTheWindow() =
        runLyricsTest { scope ->
            val gate = CompletableDeferred<Unit>()
            val provider = FakeLyricsProvider("LrcLib") { gate.await(); PLAIN_TEXT }
            val helper = helperOf(listOf(provider), scope)
            val manager = LyricsPreloadManager(helper, scope)

            manager.update(listOf(request(id = "a"), request(id = "b"), request(id = "c")))
            runCurrent()
            manager.update(listOf(request(id = "d"), request(id = "e"), request(id = "f")))
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(2, provider.cancelled)
            assertFalse("c" in provider.requestedIds)
            assertTrue(provider.requestedIds.containsAll(listOf("d", "e", "f")))
        }

    @Test
    fun aPreloadAndAForegroundLookupForTheSameTrackShareOneProviderCall() =
        runLyricsTest { scope ->
            val gate = CompletableDeferred<Unit>()
            val provider = FakeLyricsProvider("LrcLib") { gate.await(); PLAIN_TEXT }
            val helper = helperOf(listOf(provider), scope)
            val manager = LyricsPreloadManager(helper, scope)
            val track = request(id = "shared")

            manager.update(listOf(track))
            runCurrent()
            val foreground = async { helper.getLyricsWithProvider(track) }
            runCurrent()
            gate.complete(Unit)

            assertEquals("LrcLib", foreground.await().providerName)
            assertEquals(1, provider.calls)
        }

    @Test
    fun aTrackAlreadyLoadedIsNotPreloadedAgain() =
        runLyricsTest { scope ->
            val provider = answering("LrcLib", PLAIN_TEXT)
            val helper = helperOf(listOf(provider), scope)
            val manager = LyricsPreloadManager(helper, scope)

            manager.update(listOf(request(id = "once")))
            advanceUntilIdle()
            manager.update(listOf(request(id = "once")))
            advanceUntilIdle()

            assertEquals(1, provider.calls)
        }

    @Test
    fun closeCancelsEveryPreloadAndLaterUpdatesAreIgnored() =
        runLyricsTest { scope ->
            val gate = CompletableDeferred<Unit>()
            val provider = FakeLyricsProvider("LrcLib") { gate.await(); PLAIN_TEXT }
            val helper = helperOf(listOf(provider), scope)
            val manager = LyricsPreloadManager(helper, scope)

            manager.update(upcoming(2))
            runCurrent()
            manager.close()
            advanceUntilIdle()
            manager.update(upcoming(2))
            advanceUntilIdle()

            assertEquals(2, provider.cancelled)
            assertEquals(2, provider.calls)
        }
}

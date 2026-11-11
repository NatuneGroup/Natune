package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LyricsProviderCancellationTest {
    private fun cancellingClient(): HttpClient = HttpClient(MockEngine { throw CancellationException("cancelled by test") })

    @Test
    fun lrcLibRethrowsCancellationInsteadOfReturningAFailure() =
        runTest {
            val provider = LrcLibLyricsProvider(cancellingClient())

            assertFailsWith<CancellationException> { provider.getLyrics("id", "Song", "Artist", null, 200) }
        }

    @Test
    fun kuGouRethrowsCancellationInsteadOfReturningAFailure() =
        runTest {
            val provider = KuGouLyricsProvider(cancellingClient())

            assertFailsWith<CancellationException> { provider.getLyrics("id", "Song", "Artist", null, 200) }
        }

    @Test
    fun simpMusicRethrowsCancellationInsteadOfReadingItAsNoLyrics() =
        runTest {
            val provider = SimpMusicLyricsProvider(cancellingClient())

            assertFailsWith<CancellationException> { provider.getLyrics("abc", "Song", "Artist", null, 200) }
        }

    @Test
    fun unisonRethrowsCancellationInsteadOfReturningAFailure() =
        runTest {
            val provider = UnisonLyricsProvider(cancellingClient())

            assertFailsWith<CancellationException> { provider.getLyrics("abc", "Song", "Artist", null, 200) }
        }

    @Test
    fun youLyPlusRethrowsCancellationInsteadOfReturningAFailure() =
        runTest {
            val provider = YouLyPlusLyricsProvider(cancellingClient())

            assertFailsWith<CancellationException> { provider.getLyrics("abc", "Song", "Artist", null, 200) }
        }

    @Test
    fun betterLyricsRethrowsCancellationInsteadOfReturningAFailure() =
        runTest {
            val provider = BetterLyricsProvider(cancellingClient())

            assertFailsWith<CancellationException> { provider.getLyrics("abc", "Song", "Artist", null, 200) }
        }

    @Test
    fun paxsenixAppleMusicRethrowsCancellationFromTheTokenScrape() =
        runTest {
            val provider = PaxsenixAppleMusicLyricsProvider(cancellingClient())

            assertFailsWith<CancellationException> { provider.getLyrics("id", "Song", "Artist", null, 200) }
        }

    @Test
    fun paxsenixAppleMusicTokenRethrowsCancellationInsteadOfReadingItAsNoToken() =
        runTest {
            assertFailsWith<CancellationException> { PaxsenixAppleMusicToken(cancellingClient()).ensureFresh() }
        }

    @Test
    fun cancelledCoroutineNeverReportsAResultForLrcLib() =
        runTest {
            val inRequest = CompletableDeferred<Unit>()
            val neverAnswered = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine {
                        inRequest.complete(Unit)
                        neverAnswered.await()
                        jsonReply("[]")
                    },
                )
            val provider = LrcLibLyricsProvider(client)
            var outcome: Result<String>? = null

            val job = launch { outcome = provider.getLyrics("id", "Song", "Artist", null, 200) }
            inRequest.await()
            job.cancelAndJoin()

            assertNull(outcome)
        }

    @Test
    fun suspendRunCatchingCapturesFailuresAndSuccesses() =
        runTest {
            assertEquals(7, suspendRunCatching { 7 }.getOrThrow())
            assertEquals("boom", suspendRunCatching<Int> { error("boom") }.exceptionOrNull()?.message)
        }

    @Test
    fun suspendRunCatchingRethrowsCancellation() =
        runTest {
            assertFailsWith<CancellationException> {
                suspendRunCatching<Int> { throw CancellationException("stop") }
            }
        }
}

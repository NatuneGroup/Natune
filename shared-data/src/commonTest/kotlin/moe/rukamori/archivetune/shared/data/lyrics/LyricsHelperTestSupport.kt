package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs a lyrics test with a helper scope on the test scheduler, not `backgroundScope`.
 * `advanceUntilIdle` does not drive background coroutines, so under `backgroundScope` the preload and
 * single-flight work would stall until the test body suspended. The scope is cancelled and joined at
 * the end, so no provider call keeps a process-wide permit after its test ends.
 */
internal fun runLyricsTest(block: suspend TestScope.(scope: CoroutineScope) -> Unit): TestResult =
    runTest {
        val job = SupervisorJob()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
        try {
            block(scope)
        } finally {
            job.cancelAndJoin()
        }
    }

internal const val WORD_SYNCED_TTML =
    "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>" +
        "<p begin=\"0s\" end=\"1s\"><span begin=\"0s\" end=\"0.5s\">Hi</span>" +
        "<span begin=\"0.5s\" end=\"1s\">there</span></p></div></body></tt>"

internal const val LINE_SYNCED_LRC = "[00:01.00]first line\n[00:03.00]second line"

internal const val PLAIN_TEXT = "first plain line\nsecond plain line"

/**
 * A scriptable provider. [reply] returns the lyrics, or null for a provider failure. Counters are
 * plain fields because every test runs on a single-threaded test dispatcher.
 */
internal class FakeLyricsProvider(
    override val name: String,
    private val reply: suspend (mediaId: String) -> String?,
) : LyricsProvider {
    var calls = 0
    val requestedIds = mutableListOf<String>()
    var inFlight = 0
    var maxInFlight = 0
    var cancelled = 0

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String?,
    ): Result<String> {
        calls++
        requestedIds += id
        inFlight++
        if (inFlight > maxInFlight) maxInFlight = inFlight
        try {
            val text = reply(id)
            return if (text == null) {
                Result.failure(IllegalStateException("$name has no lyrics"))
            } else {
                Result.success(text)
            }
        } catch (cancellation: CancellationException) {
            cancelled++
            throw cancellation
        } finally {
            inFlight--
        }
    }
}

internal fun request(
    id: String = "vid-1",
    title: String = "Song",
    artist: String = "Artist",
): LyricsRequest =
    LyricsRequest(
        mediaId = id,
        title = title,
        artist = artist,
        album = "Album",
        durationSec = 200,
        isrc = null,
    )

internal fun answering(
    name: String,
    lyrics: String?,
): FakeLyricsProvider = FakeLyricsProvider(name) { lyrics }

internal fun answeringAfter(
    name: String,
    delayMs: Long,
    lyrics: String?,
): FakeLyricsProvider =
    FakeLyricsProvider(name) {
        delay(delayMs)
        lyrics
    }

internal fun neverAnswering(name: String): FakeLyricsProvider =
    FakeLyricsProvider(name) { CompletableDeferred<String?>().await() }

internal fun helperOf(
    providers: List<LyricsProvider>,
    scope: CoroutineScope,
    prioritizeWordSynced: Boolean = false,
    isEnabled: (String) -> Boolean = { true },
    failures: MutableList<String>? = null,
    isAvailable: (String) -> Boolean = { true },
): LyricsHelper =
    LyricsHelper(
        providers = providers,
        isEnabled = isEnabled,
        scope = scope,
        prioritizeWordSynced = { prioritizeWordSynced },
        reportProviderFailure = { providerName, _ -> failures?.add(providerName) },
        isAvailable = isAvailable,
    )

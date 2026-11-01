package moe.rukamori.archivetune.shared.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackCommand
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailureReason
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.playback.PlaybackPort
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueLocatorResolver
import moe.rukamori.archivetune.shared.domain.repository.QueueSourceRecovery
import moe.rukamori.archivetune.shared.domain.repository.TerminalPlaybackAuthentication
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.telegram.TelegramTrackRef

@OptIn(ExperimentalCoroutinesApi::class)
class PortableQueueStreamRecoveryTest {
    @Test
    fun rejectedUrlRefreshesOnceThenFallsThroughWithoutDialogOrAutoSkip() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope, autoSkip = true)
        h.start(playing = false)
        h.observe(positionMs = 45_678L, playing = false)
        runCurrent()
        val revision = h.owner.state.value.revision

        h.port.failures.emit(h.failure())
        runCurrent()
        assertEquals(AudioSourceType.TIDAL, h.locators.recoveries.single().refreshSource)
        assertEquals(45_678L, h.loads.last().startPositionMs)
        assertFalse(h.loads.last().playWhenReady)

        // A new engine failure after READY spends the old budget, rather than refreshing forever.
        h.port.failures.emit(h.failure())
        runCurrent()
        assertEquals(2, h.locators.recoveries.size)
        assertEquals(null, h.locators.recoveries.last().refreshSource)
        assertEquals(setOf(AudioSourceType.TIDAL), h.locators.recoveries.last().rejectedSources)
        assertEquals(AudioSourceType.QOBUZ, h.loads.last().request.locator.source)
        assertEquals(45_678L, h.loads.last().startPositionMs)
        assertFalse(h.loads.last().playWhenReady)
        assertEquals("first", h.owner.state.value.currentOccurrenceId)
        assertEquals(revision, h.owner.state.value.revision)
        assertTrue(h.finalFailures.isEmpty())
    }

    @Test
    fun unresolvedFreshAttemptFallsThroughWithinTheSameFailure() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.locators.refreshAvailable = false
        h.start()
        h.observe(31_000L)
        runCurrent()
        h.port.failures.emit(h.failure())
        runCurrent()
        assertEquals(2, h.locators.recoveries.size)
        assertEquals(AudioSourceType.QOBUZ, h.loads.last().request.locator.source)
        assertEquals(31_000L, h.loads.last().startPositionMs)
        assertTrue(h.finalFailures.isEmpty())
    }

    @Test
    fun exhaustedRecoveryPublishesOneFinalErrorThenUsesTheExistingAutoSkipSetting() = runTest(UnconfinedTestDispatcher()) {
        for (autoSkip in listOf(false, true)) {
            val h = Harness(backgroundScope, autoSkip = autoSkip)
            h.locators.refreshAvailable = false
            h.locators.fallbackAvailable = false
            h.start()
            runCurrent()
            val failed = h.failure()
            h.port.failures.emit(failed)
            runCurrent()
            assertEquals(listOf(failed), h.finalFailures)
            assertEquals(2, h.locators.recoveries.size)
            assertEquals(if (autoSkip) "second" else "first", h.owner.state.value.currentOccurrenceId)
            assertEquals(if (autoSkip) 2 else 1, h.loads.size)
        }
    }

    @Test
    fun staleSameOccurrenceFailureCannotSpendTheReplacementLoadsBudget() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.start()
        runCurrent()
        val old = h.failure()
        h.port.failures.emit(old)
        runCurrent()
        assertNotEquals(old.attemptId, h.loads.last().request.attemptId)
        h.port.failures.emit(old)
        runCurrent()
        assertEquals(1, h.locators.recoveries.size)
        assertEquals(2, h.loads.size)
        assertTrue(h.finalFailures.isEmpty())

        // An explicit replay of this occurrence starts a new budget; a stale callback still does not.
        h.owner.withQueueAccess { h.owner.select("first", 12_000L) }
        h.port.failures.emit(old)
        runCurrent()
        assertEquals(1, h.locators.recoveries.size)
        h.port.failures.emit(h.failure())
        runCurrent()
        assertEquals(AudioSourceType.TIDAL, h.locators.recoveries.last().refreshSource)
        assertTrue(h.locators.recoveries.last().rejectedSources.isEmpty())
    }

    @Test
    fun aFailedReopenAtZeroRetainsThePreviousRequestedPositionAndPausedIntent() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.start(playing = false)
        h.owner.withQueueAccess { h.owner.select("first", 52_000L) }
        h.observe(positionMs = 0L, playing = false)
        runCurrent()
        h.port.failures.emit(h.failure())
        runCurrent()
        assertEquals(52_000L, h.loads.last().startPositionMs)
        assertFalse(h.loads.last().playWhenReady)
    }

    @Test
    fun pauseDuringProviderRefreshIsNotOverriddenByTheRecoveredLoad() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        val answer = CompletableDeferred<PlaybackLocator?>()
        h.locators.pending = answer
        h.start()
        h.observe(positionMs = 26_000L)
        runCurrent()
        h.port.failures.emit(h.failure())
        runCurrent()
        assertEquals(1, h.loads.size)
        h.observe(positionMs = 26_000L, playing = false)
        runCurrent()
        answer.complete(remote(AudioSourceType.TIDAL, "fresh"))
        runCurrent()
        assertEquals(2, h.loads.size)
        assertFalse(h.loads.last().playWhenReady)
        assertEquals(26_000L, h.loads.last().startPositionMs)
    }

    @Test
    fun failuresForAPreviousOccurrenceNeverReachRecoveryOrTheDialog() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope, autoSkip = true)
        h.start()
        runCurrent()
        val old = h.failure()
        h.owner.withQueueAccess { h.owner.select("second", 0L) }
        h.port.failures.emit(old)
        runCurrent()
        assertEquals("second", h.owner.state.value.currentOccurrenceId)
        assertTrue(h.locators.recoveries.isEmpty())
        assertTrue(h.finalFailures.isEmpty())
    }

    @Test
    fun anUnresolvedReplayRetiresThePreviousLoadsRecoveryIdentity() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.start()
        runCurrent()
        val old = h.failure()
        h.locators.locationUnavailable = true
        h.owner.withQueueAccess { h.owner.select("first", 0L) }
        runCurrent()
        assertEquals(PlaybackFailureReason.SOURCE_UNAVAILABLE, h.finalFailures.single().reason)
        h.port.failures.emit(old)
        runCurrent()
        assertTrue(h.locators.recoveries.isEmpty())
        assertEquals(1, h.finalFailures.size)
    }

    @Test
    fun unattributedLegacyFailureDoesNotSpendTheActiveLoadsRecoveryBudget() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.start()
        runCurrent()
        val failed = h.failure().copy(attemptId = null)
        h.port.failures.emit(failed)
        runCurrent()
        assertTrue(h.locators.recoveries.isEmpty())
        assertEquals(listOf(failed), h.finalFailures)
    }

    @Test
    fun localTelegramYoutubeUnknownAndOutputFailuresKeepTheirOriginalErrorPolicy() = runTest(UnconfinedTestDispatcher()) {
        val cases = listOf(
            PlaybackLocator.LocalFile("/saved/song.flac") to PlaybackFailureReason.DECODE_FAILED,
            PlaybackLocator.TelegramFile(TelegramTrackRef(-1L, 2L, 3)) to PlaybackFailureReason.OPEN_FAILED,
            remote(AudioSourceType.YOUTUBE, "youtube") to PlaybackFailureReason.OPEN_FAILED,
            PlaybackLocator.Remote("https://cdn.invalid/unlabelled") to PlaybackFailureReason.OPEN_FAILED,
            remote(AudioSourceType.TIDAL, "output") to PlaybackFailureReason.OUTPUT_FAILED,
        )
        for ((locator, reason) in cases) {
            val h = Harness(backgroundScope, initial = locator)
            h.start()
            runCurrent()
            val failed = h.failure(reason)
            h.port.failures.emit(failed)
            runCurrent()
            assertTrue(h.locators.recoveries.isEmpty(), locator.toString())
            assertEquals(listOf(failed), h.finalFailures)
            assertEquals(1, h.loads.size)
        }
    }

    @Test
    fun terminalAuthenticationFromFreshResolveDoesNotBuyAnAnonymousFallback() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.locators.authenticationRequired = true
        h.start()
        runCurrent()
        val failed = h.failure()
        h.port.failures.emit(failed)
        runCurrent()
        assertEquals(1, h.locators.recoveries.size)
        assertEquals(AudioSourceType.TIDAL, h.locators.recoveries.single().refreshSource)
        assertEquals(listOf(failed), h.finalFailures)
    }

    private class Port : PlaybackPort {
        val commands = mutableListOf<PlaybackCommand>()
        override val snapshots = MutableStateFlow<PlaybackSnapshot?>(null)
        override val failures = MutableSharedFlow<PlaybackFailure>()
        override fun submit(command: PlaybackCommand) { commands += command }
        override fun close() = Unit
    }

    private class Locators(val initial: PlaybackLocator) : QueueLocatorResolver {
        val recoveries = mutableListOf<QueueSourceRecovery>()
        var refreshAvailable = true
        var fallbackAvailable = true
        var authenticationRequired = false
        var locationUnavailable = false
        var pending: CompletableDeferred<PlaybackLocator?>? = null
        override suspend fun locate(occurrence: ContentOccurrence): PlaybackLocator {
            check(!locationUnavailable) { "fixture source unavailable" }
            return initial
        }
        override suspend fun recover(occurrence: ContentOccurrence, recovery: QueueSourceRecovery): PlaybackLocator? {
            recoveries += recovery
            if (authenticationRequired) throw TerminalPlaybackAuthentication()
            pending?.let { return it.await() }
            val refresh = recovery.refreshSource
            return if (refresh != null) {
                if (refreshAvailable) remote(refresh, "fresh") else null
            } else {
                if (fallbackAvailable) remote(AudioSourceType.QOBUZ, "fallback") else null
            }
        }
    }

    private class Harness(
        scope: CoroutineScope,
        autoSkip: Boolean = false,
        initial: PlaybackLocator = remote(AudioSourceType.TIDAL, "old"),
    ) {
        val port = Port()
        val player = MusicPlayerController(port, scope)
        val locators = Locators(initial)
        val owner = PortableQueueOwner(player, scope, ContentRepositoryRouter(emptyList()), locators,
            autoSkipNextOnError = { autoSkip }, autoLoadMoreEnabled = { false })
        val finalFailures = mutableListOf<PlaybackFailure>()
        val loads get() = port.commands.filterIsInstance<PlaybackCommand.Load>()
        private var observation = 0L

        init { scope.launch { owner.failures.collect { finalFailures += it } } }

        suspend fun start(playing: Boolean = true) {
            val song = ContentItem(ContentRef("youtube", ContentKind.SONG, "song"), "Song")
            owner.withQueueAccess {
                owner.replace(listOf(ContentOccurrence("first", song), ContentOccurrence("second", song)),
                    "first", playing)
            }
        }

        fun observe(positionMs: Long, playing: Boolean = true) {
            val loaded = loads.last().request
            port.snapshots.value = PlaybackSnapshot(
                loaded.occurrenceId, loaded.mediaId, loaded.attemptId ?: 1L, 0L, positionMs, ++observation,
                180_000L, true, 1f, playing, false, false, PlaybackOutputRoute.LOCAL,
            )
        }

        fun failure(reason: PlaybackFailureReason = PlaybackFailureReason.OPEN_FAILED): PlaybackFailure {
            val loaded = loads.last().request
            return PlaybackFailure(loaded.occurrenceId, reason, "fixture failure",
                httpStatus = if (reason == PlaybackFailureReason.OUTPUT_FAILED) null else 403,
                attemptId = loaded.attemptId)
        }
    }

    private companion object {
        fun remote(source: AudioSourceType, suffix: String) =
            PlaybackLocator.Remote("https://cdn.invalid/$suffix", source = source)
    }
}

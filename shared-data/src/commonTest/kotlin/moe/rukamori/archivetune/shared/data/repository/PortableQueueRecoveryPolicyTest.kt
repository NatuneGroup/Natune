package moe.rukamori.archivetune.shared.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.persistence.QueueCheckpoint
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackCommand
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailureReason
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.playback.PlaybackPort
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.playback.PreparedPlaintextHandle
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueLocatorResolver
import moe.rukamori.archivetune.shared.domain.repository.QueueSourceRecovery
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.repository.QueueTail
import moe.rukamori.archivetune.shared.domain.repository.UnsupportedContentOperation
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

@OptIn(ExperimentalCoroutinesApi::class)
class PortableQueueRecoveryPolicyTest {
    @Test
    fun aTimeoutRetriesTheSameResolvedUrlWithoutTouchingTheProvider() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.start(2)
        h.observe(positionMs = 12_000L)
        runCurrent()
        h.port.failures.emit(h.failure(status = 408))
        runCurrent()
        assertTrue(h.locators.recoveries.isEmpty())
        assertSame(h.locators.initial, h.loads.last().request.locator)
        assertEquals(12_000L, h.loads.last().startPositionMs)
        assertTrue(h.finalFailures.isEmpty())
    }

    @Test
    fun aServerErrorInvalidatesTransportAndKeepsTheResolvedResource() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.start(2)
        runCurrent()
        h.port.failures.emit(h.failure(status = 500))
        runCurrent()
        assertTrue(h.locators.recoveries.isEmpty())
        assertSame(h.locators.initial, h.loads.last().request.locator)
        assertTrue(h.finalFailures.isEmpty())
    }

    @Test
    fun aRateLimitIsTerminalWithoutARetryOrAReResolve() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.start(2)
        runCurrent()
        val failed = h.failure(status = 429)
        h.port.failures.emit(failed)
        runCurrent()
        assertTrue(h.locators.recoveries.isEmpty())
        assertEquals(1, h.loads.size)
        assertEquals(listOf(failed), h.finalFailures)
    }

    @Test
    fun anUndocumentedDecodeFailureResolvesAFreshLocator() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.start(2)
        runCurrent()
        // Native engines collapse expired leases and refused reads into DECODE_FAILED without evidence.
        // With nothing naming the decode stage, recovery re-resolves instead of replaying the locator.
        h.port.failures.emit(h.failure(reason = PlaybackFailureReason.DECODE_FAILED, status = null))
        runCurrent()
        assertEquals(AudioSourceType.TIDAL, h.locators.recoveries.single().refreshSource)
        assertEquals("https://cdn.invalid/fresh", (h.loads.last().request.locator as PlaybackLocator.Remote).url)
        assertTrue(h.finalFailures.isEmpty())
    }

    @Test
    fun aNamedDecodeStageFailureRebuildsAroundTheSameResource() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.start(2)
        runCurrent()
        // A cause naming the decode stage is evidence the locator itself is still valid.
        h.port.failures.emit(h.failure(
            reason = PlaybackFailureReason.DECODE_FAILED, status = null,
            causes = listOf("MediaCodecDecoderException: codec failed"),
        ))
        runCurrent()
        assertTrue(h.locators.recoveries.isEmpty())
        assertSame(h.locators.initial, h.loads.last().request.locator)
        assertTrue(h.finalFailures.isEmpty())
    }

    @Test
    fun anExpiredRemoteLocatorIsRefreshedInsteadOfRebuilt() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope,
            initial = remote(AudioSourceType.TIDAL, "stale", expiresAtEpochMs = 5_000L))
        h.now = 10_000L
        h.start(2)
        runCurrent()
        // Decode-stage evidence alone would rebuild around the locator, but its window already closed.
        h.port.failures.emit(h.failure(
            reason = PlaybackFailureReason.DECODE_FAILED, status = null,
            causes = listOf("MediaCodecDecoderException: codec failed"),
        ))
        runCurrent()
        assertEquals(AudioSourceType.TIDAL, h.locators.recoveries.single().refreshSource)
        assertEquals("https://cdn.invalid/fresh", (h.loads.last().request.locator as PlaybackLocator.Remote).url)
        assertTrue(h.finalFailures.isEmpty())
    }

    @Test
    fun aConsumedPreparedLocatorIsRepreparedOnAnyRetry() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope,
            initial = PlaybackLocator.PreparedPlaintext(FakePreparedHandle(), AudioSourceType.TIDAL))
        h.start(2)
        runCurrent()
        // One handle is one view: even a same-URL retry resolves a fresh preparation, because the
        // consumed view would only be refused again.
        h.port.failures.emit(h.failure(status = 408))
        runCurrent()
        assertEquals(AudioSourceType.TIDAL, h.locators.recoveries.single().refreshSource)
        assertEquals("https://cdn.invalid/fresh", (h.loads.last().request.locator as PlaybackLocator.Remote).url)
        assertTrue(h.finalFailures.isEmpty())
    }

    @Test
    fun aTokenRejectionSpendsOneReResolveOnAFreshUrl() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.start(2)
        runCurrent()
        h.port.failures.emit(h.failure(status = 401))
        runCurrent()
        assertEquals(AudioSourceType.TIDAL, h.locators.recoveries.single().refreshSource)
        assertEquals(AudioSourceType.TIDAL, h.loads.last().request.locator.source)
        assertTrue(h.finalFailures.isEmpty())
    }

    @Test
    fun twoProviderResolvesExhaustTheFailureBudget() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.start(2)
        runCurrent()
        h.port.failures.emit(h.failure())
        runCurrent()
        h.port.failures.emit(h.failure())
        runCurrent()
        assertEquals(2, h.locators.recoveries.size)
        assertTrue(h.finalFailures.isEmpty())

        // The budget's two re-resolves are spent; the third failure is terminal.
        val failed = h.failure()
        h.port.failures.emit(failed)
        runCurrent()
        assertEquals(2, h.locators.recoveries.size)
        assertEquals(listOf(failed), h.finalFailures)
    }

    @Test
    fun thirtySecondsOfPositionProgressRefundsTheFailureBudget() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.start(2)
        h.observe(positionMs = 0L)
        runCurrent()
        h.port.failures.emit(h.failure())
        runCurrent()
        h.port.failures.emit(h.failure())
        runCurrent()
        assertEquals(2, h.locators.recoveries.size)

        // The song played on for half a minute, so the next rejection earns a fresh budget.
        h.observe(positionMs = 35_000L)
        runCurrent()
        h.port.failures.emit(h.failure())
        runCurrent()
        assertEquals(3, h.locators.recoveries.size)
        assertEquals(AudioSourceType.QOBUZ, h.locators.recoveries.last().refreshSource)
        assertEquals(35_000L, h.loads.last().startPositionMs)
        assertTrue(h.finalFailures.isEmpty())
    }

    @Test
    fun aFailurePastTheBudgetDeadlineIsTerminal() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.start(2)
        runCurrent()
        h.port.failures.emit(h.failure())
        runCurrent()
        assertEquals(1, h.locators.recoveries.size)

        h.now += 31_000L
        val failed = h.failure()
        h.port.failures.emit(failed)
        runCurrent()
        assertEquals(1, h.locators.recoveries.size)
        assertEquals(listOf(failed), h.finalFailures)
    }

    @Test
    fun aSkipThatThrowsReportsTheSameFailureOnlyOnce() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope, autoSkip = true)
        h.locators.refreshAvailable = false
        h.locators.fallbackAvailable = false
        // A finite tail whose provider cannot answer makes the automatic skip throw mid-failure.
        h.restoreWithFiniteTail()
        runCurrent()
        val failed = h.failure()
        h.port.failures.emit(failed)
        runCurrent()
        assertEquals(listOf(failed), h.finalFailures)
        assertTrue(h.advanceFailures.single() is UnsupportedContentOperation)
    }

    @Test
    fun preventDuplicatesMovesAReAddedSongToItsNewPosition() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.preventDuplicates = true
        h.start(3)
        runCurrent()
        val inserted = h.insert("i-b" to "songB", "i-d" to "songD", "i-a" to "songA")
        runCurrent()
        assertEquals(2, inserted)
        // The earlier copy of songB left; the current song's copy was dropped; songB landed after current.
        assertEquals(listOf("a", "i-b", "i-d", "c"), h.owner.state.value.entries.map { it.occurrenceId })
        assertEquals("a", h.owner.state.value.currentOccurrenceId)
    }

    @Test
    fun preventDuplicatesOnAnEmptyQueueDedupesTheBatchItself() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.preventDuplicates = true
        val inserted = h.insert("i-1" to "songA", "i-2" to "songA", "i-3" to "songB")
        runCurrent()
        assertEquals(2, inserted)
        assertEquals(listOf("i-1", "i-3"), h.owner.state.value.entries.map { it.occurrenceId })
        // The empty queue had no current song; the first surviving copy becomes current paused.
        assertEquals("i-1", h.owner.state.value.currentOccurrenceId)
        assertFalse(h.loads.last().playWhenReady)
    }

    @Test
    fun aCopyOfTheCurrentSongIsNotRequeued() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.preventDuplicates = true
        h.start(2)
        runCurrent()
        val revision = h.owner.state.value.revision
        val inserted = h.insert("i-a" to "songA")
        runCurrent()
        assertEquals(0, inserted)
        assertEquals(revision, h.owner.state.value.revision)
        assertEquals(listOf("a", "b"), h.owner.state.value.entries.map { it.occurrenceId })
    }

    @Test
    fun withoutTheSettingDuplicatesInsertAsBefore() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.start(2)
        runCurrent()
        val inserted = h.insert("i-a" to "songA")
        runCurrent()
        assertEquals(1, inserted)
        assertEquals(listOf("a", "i-a", "b"), h.owner.state.value.entries.map { it.occurrenceId })
    }

    @Test
    fun aLockedQueueRefusesRemovalsAndMovesButStillAdds() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.start(3)
        runCurrent()
        h.locked = true
        val revision = h.owner.state.value.revision
        h.owner.withQueueAccess { h.owner.remove(setOf("b")) }
        assertNull(h.owner.withQueueAccess { h.owner.removeUndoable(setOf("b")) })
        assertNull(h.owner.withQueueAccess { h.owner.clearUpcoming() })
        h.owner.withQueueAccess { h.owner.moveNext("b") }
        h.owner.withQueueAccess { h.owner.moveInPlayOrder("b", "a") }
        h.owner.withQueueAccess { h.owner.move("b", "a") }
        runCurrent()
        assertEquals(revision, h.owner.state.value.revision)
        assertEquals(listOf("a", "b", "c"), h.owner.state.value.entries.map { it.occurrenceId })

        // Adding is never gated, and the edits work again after the lock opens.
        assertEquals(1, h.insert("d" to "songD"))
        h.locked = false
        h.owner.withQueueAccess { h.owner.remove(setOf("d")) }
        runCurrent()
        assertEquals(listOf("a", "b", "c"), h.owner.state.value.entries.map { it.occurrenceId })
    }

    private class Port : PlaybackPort {
        val commands = mutableListOf<PlaybackCommand>()
        override val snapshots = MutableStateFlow<PlaybackSnapshot?>(null)
        override val failures = MutableSharedFlow<PlaybackFailure>()
        override fun submit(command: PlaybackCommand) { commands += command }
        override fun close() = Unit
    }

    private class FakePreparedHandle(override val expiresAtEpochMs: Long? = null) : PreparedPlaintextHandle {
        override val mimeType: String = "audio/flac"
        override val codec: String = "flac"
        override val lengthBytes: Long = 1_024L
    }

    private class Locators(val initial: PlaybackLocator) : QueueLocatorResolver {
        val recoveries = mutableListOf<QueueSourceRecovery>()
        var refreshAvailable = true
        var fallbackAvailable = true
        override suspend fun locate(occurrence: ContentOccurrence): PlaybackLocator = initial
        override suspend fun recover(occurrence: ContentOccurrence, recovery: QueueSourceRecovery): PlaybackLocator? {
            recoveries += recovery
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
        var preventDuplicates = false
        var locked = false
        var now = 0L
        val owner = PortableQueueOwner(
            player, scope, ContentRepositoryRouter(emptyList()), locators,
            autoSkipNextOnError = { autoSkip },
            autoLoadMoreEnabled = { false },
            preventDuplicateTracksInQueue = { preventDuplicates },
            queueLocked = { locked },
            monotonicTimeMs = { now },
        )
        val finalFailures = mutableListOf<PlaybackFailure>()
        val advanceFailures = mutableListOf<Throwable>()
        val loads get() = port.commands.filterIsInstance<PlaybackCommand.Load>()
        private var observation = 0L

        init {
            scope.launch { owner.failures.collect { finalFailures += it } }
            scope.launch { owner.automaticAdvanceFailures.collect { advanceFailures += it } }
        }

        /** Loads [count] songs: occurrence "a"/"b"/... playing "songA"/"songB"/..., the first current. */
        suspend fun start(count: Int) {
            owner.withQueueAccess {
                owner.replace(
                    (0 until count).map { index ->
                        ContentOccurrence(letter(index), item("song" + letter(index).uppercase()))
                    },
                    letter(0), true,
                )
            }
        }

        suspend fun restoreWithFiniteTail() {
            val occurrence = ContentOccurrence("first", item("songA"))
            owner.withQueueAccess {
                owner.restore(
                    QueueCheckpoint(
                        QueueState(
                            revision = 1L,
                            entries = listOf(occurrence),
                            currentOccurrenceId = "first",
                            playOrder = listOf("first"),
                            tail = QueueTail(
                                collection = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.SEARCH),
                                continuation = "next-token",
                                kind = QueueCollectionKind.FINITE,
                            ),
                        ),
                        positionMs = 0L,
                    ),
                )
            }
        }

        suspend fun insert(vararg pairs: Pair<String, String>): Int =
            owner.withQueueAccess {
                owner.insert(
                    pairs.map { (id, media) -> ContentOccurrence(id, item(media)) },
                    UserQueuedKind.ADD_TO_QUEUE,
                )
            }

        fun observe(positionMs: Long, playing: Boolean = true) {
            val loaded = loads.last().request
            port.snapshots.value = PlaybackSnapshot(
                loaded.occurrenceId, loaded.mediaId, loaded.attemptId ?: 1L, 0L, positionMs, ++observation,
                180_000L, true, 1f, playing, false, false, PlaybackOutputRoute.LOCAL,
            )
        }

        fun failure(
            reason: PlaybackFailureReason = PlaybackFailureReason.OPEN_FAILED,
            status: Int? = 403,
            causes: List<String> = emptyList(),
        ): PlaybackFailure {
            val loaded = loads.last().request
            return PlaybackFailure(
                loaded.occurrenceId, reason, "fixture failure",
                httpStatus = status, causes = causes, attemptId = loaded.attemptId,
            )
        }
    }

    private companion object {
        fun letter(index: Int) = ('a' + index).toString()

        fun item(mediaId: String) =
            ContentItem(ContentRef("youtube", ContentKind.SONG, mediaId), "Song")

        fun remote(source: AudioSourceType, suffix: String, expiresAtEpochMs: Long? = null) =
            PlaybackLocator.Remote("https://cdn.invalid/$suffix", source = source, expiresAtEpochMs = expiresAtEpochMs)
    }
}

package moe.rukamori.archivetune.shared.data.repository

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.persistence.QueueCheckpoint
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackCommand
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.playback.PlaybackPort
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistPhase
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.*

class PortableInfiniteQueueTest {
    @Test fun aLongFiniteQueuePrefetchesAtThreeRemainingWithoutSkippingThePlayingSong() = runTest {
        val h = Harness(backgroundScope)
        h.playlist = (1..8).map { song("p$it") }
        h.radioReply = { _, _ ->
            ContentPage(listOf(song("duplicate-current", "p6"), song("duplicate-tail", "p8")) +
                (1..6).flatMap { listOf(song("r$it"), song("duplicate-$it", "r$it")) })
        }
        h.enabled.value = true
        h.load()
        runCurrent()
        assertEquals(0, h.radioCalls.size)
        h.owner.withQueueAccess { h.owner.select("p5", 0L) }
        runCurrent()
        assertEquals(0, h.radioCalls.size)
        h.owner.withQueueAccess { h.owner.select("p6", 0L) }
        runCurrent()
        assertEquals("p6", h.owner.state.value.currentOccurrenceId)
        assertEquals((1..8).map { "p$it" } + (1..6).map { "r$it" }, h.ids())
        assertEquals("p6", h.radioCalls.single().first.parent?.id)
        assertFalse(h.owner.infiniteQueueLoading.value)
    }

    @Test fun radioContinuationDeduplicatesMediaButFiniteRepeatedOccurrencesSurvive() = runTest {
        val h = Harness(backgroundScope)
        h.playlist = listOf(song("copy-a", "same"), song("copy-b", "same"), song("p3"))
        h.load()
        h.radioReply = { _, token ->
            if (token == null) ContentPage((1..6).map { song("r$it") }, "next-radio")
            else ContentPage(listOf(song("seen-again", "same"), song("also-seen", "r1")) +
                (7..12).flatMap { listOf(song("r$it"), song("another-$it", "r$it")) })
        }
        h.enabled.value = true
        runCurrent()
        assertEquals(listOf("copy-a", "copy-b", "p3") + (1..6).map { "r$it" }, h.ids())
        h.owner.withQueueAccess { h.owner.select("r5", 0L) }
        runCurrent()
        assertEquals(listOf("copy-a", "copy-b", "p3") + (1..12).map { "r$it" }, h.ids())
        assertEquals(listOf(null, "next-radio"), h.radioCalls.map { it.second })
        assertEquals("r5", h.owner.state.value.currentOccurrenceId)
    }

    @Test fun disablingDuringACancellationIgnoringRequestNeverCommitsItsSongs() = runTest {
        val h = Harness(backgroundScope)
        h.playlist = listOf(song("p1"))
        h.load()
        val response = CompletableDeferred<ContentPage>()
        h.radioReply = { _, _ -> withContext(NonCancellable) { response.await() } }
        h.enabled.value = true
        runCurrent()
        assertTrue(h.owner.infiniteQueueLoading.value)
        // Repeated engine observations cannot start a second request while the first owns its ticket.
        repeat(8) { h.observe("p1", it.toLong()); runCurrent() }
        assertEquals(1, h.radioCalls.size)
        h.enabled.value = false
        runCurrent()
        assertFalse(h.owner.infiniteQueueLoading.value)
        response.complete(ContentPage(listOf(song("stale-radio"))))
        runCurrent()
        assertEquals(listOf("p1"), h.ids())
        assertEquals("p1", h.owner.state.value.currentOccurrenceId)
    }

    @Test fun aPreferenceToggleDecidesUnderTheQueueLockItSharesWithTheAppend() = runTest {
        val h = Harness(backgroundScope)
        h.playlist = listOf(song("p1"))
        h.load()
        val response = CompletableDeferred<ContentPage>()
        h.radioReply = { _, _ -> withContext(NonCancellable) { response.await() } }
        h.enabled.value = true
        runCurrent()
        assertTrue(h.owner.infiniteQueueLoading.value)

        val lockHeld = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch { h.owner.withQueueAccess { lockHeld.complete(Unit); release.await() } }
        lockHeld.await()
        h.enabled.value = false
        runCurrent()
        // The toggle's cancel() waits for the queue lock, so it cannot race the in-flight append's gate writes.
        assertTrue(h.owner.infiniteQueueLoading.value)
        release.complete(Unit)
        holder.join()
        runCurrent()
        assertFalse(h.owner.infiniteQueueLoading.value)
        response.complete(ContentPage(emptyList()))
        runCurrent()
        assertEquals(listOf("p1"), h.ids())
    }

    @Test fun emptyAndFailedRequestsBackOffAcrossPreferenceTogglesAndSuccessResetsTheGate() = runTest {
        val h = Harness(backgroundScope)
        h.playlist = listOf(song("p1"))
        h.radioReply = { _, _ -> error("Offline fixture") }
        h.enabled.value = true
        h.load()
        runCurrent()
        repeat(8) { h.owner.withQueueAccess { h.owner.refreshInfiniteQueue() }; runCurrent() }
        assertEquals(1, h.radioCalls.size)
        h.nowMs = 29_999L
        h.owner.withQueueAccess { h.owner.refreshInfiniteQueue() }
        runCurrent()
        assertEquals(1, h.radioCalls.size)
        h.nowMs = 30_000L
        h.owner.withQueueAccess { h.owner.refreshInfiniteQueue() }
        runCurrent()
        assertEquals(2, h.radioCalls.size)
        h.radioReply = { _, _ -> ContentPage(emptyList()) }
        h.nowMs = 90_000L
        h.owner.withQueueAccess { h.owner.refreshInfiniteQueue() }
        runCurrent()
        assertEquals(3, h.radioCalls.size)
        h.enabled.value = false
        runCurrent()
        h.enabled.value = true
        runCurrent()
        assertEquals(3, h.radioCalls.size)
        h.radioReply = { _, _ -> ContentPage((1..6).map { song("r$it") }) }
        h.nowMs = 210_000L
        h.owner.withQueueAccess { h.owner.refreshInfiniteQueue() }
        runCurrent()
        assertEquals(4, h.radioCalls.size)
        assertEquals(listOf("p1") + (1..6).map { "r$it" }, h.ids())
    }

    @Test fun shuffledThresholdUsesPlayOrderAndRecommendationsAppendAfterItsExistingTail() = runTest {
        val h = Harness(backgroundScope)
        h.playlist = (1..8).map { song("p$it") }
        h.load()
        h.owner.withQueueAccess { assertTrue(h.owner.setShuffle(true)) }
        val original = h.owner.state.value.playOrder
        val target = original[original.size - 3]
        h.radioReply = { _, _ -> ContentPage((1..6).map { song("r$it") }, "radio-next") }
        h.enabled.value = true
        runCurrent()
        assertEquals(0, h.radioCalls.size)
        h.owner.withQueueAccess { h.owner.select(target, 0L) }
        runCurrent()
        assertEquals(original + (1..6).map { "r$it" }, h.owner.state.value.playOrder)
        assertEquals(target, h.owner.state.value.currentOccurrenceId)
        assertTrue(h.owner.state.value.shuffleEnabled)
        h.owner.withQueueAccess { assertTrue(h.owner.setShuffle(false)); assertTrue(h.owner.setShuffle(true)) }
    }

    @Test fun repeatAndUnknownLegacyCompletionRefuseAutomaticRadio() = runTest {
        val h = Harness(backgroundScope)
        val entry = song("legacy")
        h.enabled.value = true
        h.owner.withQueueAccess {
            h.owner.restore(QueueCheckpoint(QueueState(7L, listOf(entry), "legacy", listOf("legacy")), 123L))
            h.owner.refreshInfiniteQueue()
        }
        runCurrent()
        assertEquals(0, h.radioCalls.size)
        h.repeat = QueueRepeatMode.ALL
        h.playlist = listOf(song("finite"))
        h.load()
        h.owner.withQueueAccess { h.owner.refreshInfiniteQueue() }
        runCurrent()
        assertEquals(0, h.radioCalls.size)
        h.repeat = QueueRepeatMode.ONE
        h.observe("finite", 1L, ended = true)
        runCurrent()
        assertEquals(0, h.radioCalls.size)
        assertEquals("finite", h.owner.state.value.currentOccurrenceId)
    }

    @Test fun aCheckpointedExhaustedRadioCanReseedFromCurrentWithoutGuessingFiniteCompletion() = runTest {
        val h = Harness(backgroundScope)
        val entry = song("current")
        val origin = radioQueueRequest(song("old-seed").item.ref)
        h.radioReply = { _, _ -> ContentPage((1..6).map { song("r$it") }) }
        h.owner.withQueueAccess {
            h.owner.restore(QueueCheckpoint(QueueState(
                8L, listOf(entry), "current", listOf("current"),
                tail = QueueTail(origin, null, kind = QueueCollectionKind.RADIO),
            ), 123L))
        }
        h.enabled.value = true
        runCurrent()
        assertEquals("current", h.radioCalls.single().first.parent?.id)
        assertEquals(listOf("current") + (1..6).map { "r$it" }, h.ids())
        assertEquals("current", h.owner.state.value.currentOccurrenceId)
        assertEquals(QueueCollectionKind.RADIO, h.owner.state.value.tail?.kind)
    }

    @Test fun finitePagingRemainsAvailableWithInfinityOffAndCannotBeShuffledIncomplete() = runTest {
        val h = Harness(backgroundScope)
        val entry = song("p1")
        h.playlist = listOf(song("p2"))
        h.owner.withQueueAccess {
            h.owner.restore(QueueCheckpoint(QueueState(
                3L, listOf(entry), "p1", listOf("p1"),
                tail = QueueTail(playlistRequest(), "finite-next", kind = QueueCollectionKind.FINITE),
            ), 0L))
            assertFalse(h.owner.setShuffle(true))
        }
        h.observe("p1", 1L, ended = true)
        runCurrent()
        assertEquals(listOf("p1", "p2"), h.ids())
        assertEquals("p2", h.owner.state.value.currentOccurrenceId)
        assertEquals(0, h.radioCalls.size)
    }

    @Test fun clearAndUndoPreserveThePlayingSongAndInterveningManualAddition() = runTest {
        val h = Harness(backgroundScope)
        h.playlist = listOf(song("p1"), song("p2"), song("p3"))
        h.load()
        h.owner.withQueueAccess {
            h.owner.select("p2", 0L)
            val ticket = assertNotNull(h.owner.clearUpcoming())
            assertEquals(listOf("p2"), h.ids())
            h.owner.insert(listOf(song("manual")), UserQueuedKind.PLAY_NEXT)
            assertTrue(h.owner.undoRemoval(ticket))
        }
        assertEquals(listOf("p1", "p2", "p3", "manual"), h.ids())
        assertEquals("p2", h.owner.state.value.currentOccurrenceId)
        assertEquals(UserQueuedKind.PLAY_NEXT, h.owner.state.value.manualKinds["manual"])
        assertEquals(0, h.radioCalls.size)
    }

    @Test fun clearAlsoDetachesUnloadedFinitePagesInsteadOfRefillingAtTheEnd() = runTest {
        val h = Harness(backgroundScope)
        h.playlist = listOf(song("unloaded"))
        h.owner.withQueueAccess {
            h.owner.restore(QueueCheckpoint(QueueState(
                3L, listOf(song("p1"), song("p2")), "p1", listOf("p1", "p2"),
                tail = QueueTail(playlistRequest(), "owed-finite-page"),
            ), 0L))
            assertNotNull(h.owner.clearUpcoming())
        }
        h.observe("p1", 1L, ended = true)
        runCurrent()
        assertEquals(listOf("p1"), h.ids())
        assertEquals("p1", h.owner.state.value.currentOccurrenceId)
        assertEquals(0, h.radioCalls.size)
    }

    @Test fun clearUpcomingWorksWhileAutoLoadMoreStaysEnabled() = runTest {
        val h = Harness(backgroundScope)
        h.playlist = listOf(song("p1"), song("p2"), song("p3"))
        h.load()
        val response = CompletableDeferred<ContentPage>()
        h.radioReply = { _, _ -> withContext(NonCancellable) { response.await() } }
        h.enabled.value = true
        runCurrent()
        assertTrue(h.owner.infiniteQueueLoading.value)

        // Clearing no longer requires the stored preference to be off, and it leaves the preference alone.
        h.owner.withQueueAccess { assertNotNull(h.owner.clearUpcoming()) }
        runCurrent()
        assertTrue(h.enabled.value)
        assertFalse(h.owner.infiniteQueueLoading.value)
        assertEquals(listOf("p1"), h.ids())

        response.complete(ContentPage((1..6).map { song("r$it") }))
        runCurrent()
        assertEquals(listOf("p1"), h.ids())
        assertEquals(1, h.radioCalls.size)
    }

    @Test fun shuffledMovesChangeOnlyPlayOrderAndReplacementInvalidatesOldUndo() = runTest {
        val h = Harness(backgroundScope)
        h.playlist = (1..6).map { song("p$it") }
        h.load()
        h.owner.withQueueAccess {
            assertTrue(h.owner.setShuffle(true))
            h.owner.moveNext("p6")
            assertEquals("p6", h.owner.state.value.playOrder[1])
            h.owner.moveInPlayOrder("p5", h.owner.state.value.playOrder.first())
            assertEquals("p5", h.owner.state.value.playOrder.first())
            assertEquals((1..6).map { "p$it" }, h.ids())
            val ticket = assertNotNull(h.owner.removeUndoable(setOf("p2")))
            h.owner.replace(listOf(song("replacement")), "replacement", false)
            assertFalse(h.owner.undoRemoval(ticket))
        }
        assertEquals(listOf("replacement"), h.ids())
    }

    @Test fun aBuiltInCollectionReplacementStaysFiniteWhileAnAdHocReplacementGrowsARadioTail() = runTest {
        val h = Harness(backgroundScope)
        h.radioReply = { _, _ -> ContentPage((1..6).map { song("r$it") }) }
        h.enabled.value = true
        h.owner.withQueueAccess {
            h.owner.replace(listOf(song("liked1"), song("liked2")), "liked1", false, staysFinite = true)
        }
        runCurrent()
        h.owner.withQueueAccess { h.owner.select("liked2", 0L) }
        runCurrent()
        assertTrue(h.owner.state.value.staysFinite)
        assertEquals(listOf("liked1", "liked2"), h.ids())
        assertEquals(0, h.radioCalls.size)

        h.owner.withQueueAccess { h.owner.replace(listOf(song("pick")), "pick", false) }
        runCurrent()
        assertFalse(h.owner.state.value.staysFinite)
        assertEquals(listOf("pick") + (1..6).map { "r$it" }, h.ids())
    }

    private class Harness(scope: CoroutineScope) {
        val enabled = MutableStateFlow(false)
        var repeat = QueueRepeatMode.OFF
        var nowMs = 0L
        var playlist: List<ContentOccurrence> = emptyList()
        var radioReply: suspend (ContentRequest, String?) -> ContentPage = { _, _ -> ContentPage(emptyList()) }
        val radioCalls = mutableListOf<Pair<ContentRequest, String?>>()
        val port = RecordingPort()
        private val repository = object : TypedContentRepository {
            override val provider = ContentProvider.YOUTUBE
            override suspend fun page(request: ContentRequest, continuation: String?): ContentPage {
                if (request.surface != ContentSurface.RADIO) return ContentPage(playlist)
                radioCalls += request to continuation
                return radioReply(request, continuation)
            }
            override suspend fun item(ref: ContentRef): ContentItem = error("Unused fixture")
        }
        val owner = PortableQueueOwner(
            player = MusicPlayerController(port, scope),
            scope = scope,
            content = ContentRepositoryRouter(listOf(repository)),
            locators = object : QueueLocatorResolver {
                override suspend fun locate(occurrence: ContentOccurrence): PlaybackLocator =
                    PlaybackLocator.Remote("https://example.test/audio")
            },
            random = Random(7),
            repeatMode = { repeat },
            autoLoadMoreEnabled = { enabled.value },
            autoLoadMoreChanges = enabled,
            monotonicTimeMs = { nowMs },
        )

        suspend fun load() = owner.withQueueAccess {
            owner.loadCollection(QueueLoadRequest(playlistRequest(), QueueCollectionKind.FINITE))
        }
        fun ids() = owner.state.value.entries.map { it.occurrenceId }
        fun observe(id: String, tick: Long, ended: Boolean = false) {
            port.snapshots.value = PlaybackSnapshot(
                occurrenceId = id, mediaId = id, playbackGeneration = 1L, discontinuityGeneration = 0L,
                positionMs = tick, sampleMonotonicNanos = tick, durationMs = 180_000L,
                isSeekable = true, playbackSpeed = 1f, playWhenReady = true, isAudioAdvancing = !ended,
                isBuffering = false, outputRoute = PlaybackOutputRoute.LOCAL, isEnded = ended,
            )
        }
    }

    private class RecordingPort : PlaybackPort {
        override val snapshots = MutableStateFlow<PlaybackSnapshot?>(null)
        override val failures = MutableSharedFlow<PlaybackFailure>()
        override fun submit(command: PlaybackCommand) {}
        override fun close() {}
    }

    companion object {
        private fun song(occurrence: String, media: String = occurrence) = ContentOccurrence(
            occurrence, ContentItem(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, media), media),
        )
        private fun playlistRequest() = ContentRequest(
            ContentProvider.YOUTUBE, ContentSurface.PLAYLIST,
            parent = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PLAYLIST, "PL-fixture"),
        )
    }
}

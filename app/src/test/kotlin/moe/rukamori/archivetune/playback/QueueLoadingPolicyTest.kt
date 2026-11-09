package moe.rukamori.archivetune.playback

import androidx.media3.common.MediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.playback.QueueCheckpointSchedulerTest.Fixture
import moe.rukamori.archivetune.playback.queues.EmptyQueue
import moe.rukamori.archivetune.playback.queues.FinitePlaylistPhase
import moe.rukamori.archivetune.playback.queues.FinitePlaylistSnapshot
import moe.rukamori.archivetune.playback.queues.PlaylistActionTicket
import moe.rukamori.archivetune.playback.queues.PlaylistLoadOwner
import moe.rukamori.archivetune.playback.queues.PlaylistLoadState
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.playback.queues.YouTubePlaylistQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueLoadingPolicyTest {
    @Test
    fun synchronousAutoAndListenTogetherTransitions_cannotStartOldFiniteOrRecommendationLoading() = runTest {
        for (auto in listOf(true, false)) {
            val fixture = Fixture(this)
            val old = fixture.beginFiniteReplacement(FinitePlaylistSnapshot(
                continuation = "old-page", phase = FinitePlaylistPhase.PAGEABLE,
            ))
            fixture.installTimeline(listOf("old"))
            advanceUntilIdle()
            val target = QueueCheckpointTestPlayer(listOf(item("old")))
            val requestedFinite = mutableListOf<YouTubePlaylistQueue>()
            val requestedRecommendations = mutableListOf<Queue>()
            var loading = false
            var transitionsDuringInstallation = 0
            val evaluate = {
                evaluateAcceptedQueueLoading(fixture.queue,
                    allowed = mayStartQueueLoading(false, false, false, fixture.checkpointGate.timelineMutationInProgress),
                    needsFiniteItems = { true },
                    startFinite = { requestedFinite += it; loading = true },
                    considerRecommendations = { requestedRecommendations += it },
                )
            }
            target.changed = {
                fixture.changeTimeline(target.items.map { it.mediaId })
                assertSame(old, fixture.queue) // Media3 invokes this before identity acceptance.
                assertTrue(fixture.checkpointGate.timelineMutationInProgress)
                transitionsDuringInstallation++
                evaluate()
            }
            val install: (Queue?, String?, Boolean, () -> Unit) -> Unit = { queue, title, replay, mutation ->
                installServiceQueueTimeline(fixture.checkpointGate, queue, replay,
                    cancelObsoleteWork = { loading = false; fixture.finishReplacement() },
                    install = mutation,
                    acceptIdentity = {
                        fixture.queue = queue ?: EmptyQueue
                        fixture.queueTitle = title
                        fixture.queue
                    },
                    save = fixture.scheduler::request,
                    evaluateLoading = evaluate,
                )
            }
            val external = listOf(item("external"), item("short-tail"))

            if (auto) {
                queueSessionTimelinePlayer(target.player, fixture.checkpointGate,
                    { _, queue, title, replay, mutation -> install(queue, title, replay, mutation) },
                    fixture.scheduler::request,
                ).setMediaItems(external, 0, 123L)
            } else {
                install(null, null, false) { target.player.setMediaItems(external, 0, 123L) }
            }
            advanceUntilIdle()

            assertEquals(1, transitionsDuringInstallation)
            assertTrue(requestedFinite.isEmpty())
            assertEquals(listOf<Queue>(EmptyQueue), requestedRecommendations)
            assertFalse(loading)
            assertFalse(fixture.checkpointGate.timelineMutationInProgress)
            assertEquals(listOf("external", "short-tail"), fixture.written.last())
        }
    }

    @Test
    fun acceptedFiniteReplacement_isEvaluatedAfterTheMutationInsteadOfTheOldSource() = runTest {
        val fixture = Fixture(this)
        val old = fixture.beginFinitePreload()
        advanceUntilIdle()
        val accepted = YouTubePlaylistQueue(WatchEndpoint(playlistId = "accepted"), snapshot = FinitePlaylistSnapshot(
            continuation = "accepted-page", phase = FinitePlaylistPhase.PAGEABLE,
        ))
        val starts = mutableListOf<YouTubePlaylistQueue>()
        val recommendations = mutableListOf<Queue>()
        val evaluate = {
            evaluateAcceptedQueueLoading(fixture.queue,
                allowed = mayStartQueueLoading(false, false, false, fixture.checkpointGate.timelineMutationInProgress),
                needsFiniteItems = { true }, startFinite = { starts += it },
                considerRecommendations = { recommendations += it },
            )
        }

        installServiceQueueTimeline(fixture.checkpointGate, accepted, false,
            cancelObsoleteWork = { fixture.finishReplacement() },
            install = {
                assertSame(old, fixture.queue)
                fixture.changeTimeline(listOf("accepted-track"))
                evaluate()
            },
            acceptIdentity = { fixture.queue = accepted; accepted },
            save = fixture.scheduler::request,
            evaluateLoading = evaluate,
        )
        advanceUntilIdle()

        assertEquals(listOf(accepted), starts)
        assertEquals(listOf<Queue>(accepted), recommendations)
        assertSame(accepted, fixture.checkpointGate.persistenceQueue)
    }

    @Test
    fun loaderLosingGenerationQueueOrTicketOwnership_clearsTheStateItStillPublishes() = runTest {
        for (loss in listOf("generation", "queue", "ticket")) {
            val owner = PlaylistLoadOwner()
            val publisher = PlaylistLoadStatePublisher()
            var generation = 3L
            var queue: Any = Any()
            val ticket = requireNotNull(owner.begin(generation, queue))
            publisher.publish(ticket)
            var state = PlaylistLoadState(loading = true, showDialog = true)
            val retry = {}
            var retryAction: (() -> Unit)? = retry
            val release = CompletableDeferred<Unit>()
            val loader = launch {
                try {
                    release.await()
                    if (!ticket.isCurrent(generation, queue)) return@launch
                } finally {
                    if (!ticket.isCurrent(generation, queue) && publisher.release(ticket)) {
                        retryAction = null
                        state = PlaylistLoadState()
                    }
                    ticket.finish()
                }
            }
            runCurrent()
            when (loss) {
                "generation" -> generation++
                "queue" -> queue = Any()
                else -> owner.cancel()
            }
            release.complete(Unit)
            loader.join()

            assertFalse(state.loading)
            assertFalse(state.showDialog)
            assertNull(retryAction)
            assertNull(publisher.owner)
        }
    }

    @Test
    fun staleLoaderCannotClearANewerPublishersLoadingOrRetryState() = runTest {
        val owner = PlaylistLoadOwner()
        val publisher = PlaylistLoadStatePublisher()
        val oldQueue = Any()
        var queue = oldQueue
        var generation = 4L
        val old = requireNotNull(owner.begin(generation, queue))
        publisher.publish(old)
        var state = PlaylistLoadState(loading = true, showDialog = true)
        var retryAction: (() -> Unit)? = {}
        val release = CompletableDeferred<Unit>()
        val loader = launch {
            try { release.await() }
            finally {
                if (!old.isCurrent(generation, queue) && publisher.release(old)) {
                    state = PlaylistLoadState()
                    retryAction = null
                }
                old.finish()
            }
        }
        runCurrent()
        queue = Any()
        generation++
        val newer = requireNotNull(owner.begin(generation, queue))
        publisher.publish(newer)
        val newerState = PlaylistLoadState(loading = true, showDialog = false)
        state = newerState
        val newerRetry = {}
        retryAction = newerRetry
        release.complete(Unit)
        loader.join()

        assertSame(newer, publisher.owner)
        assertEquals(newerState, state)
        assertSame(newerRetry, retryAction)
        assertTrue(newer.isCurrent(generation, queue))
        newer.finish()
    }

    @Test
    fun wholeActionLosingQueueOwnership_clearsOnlyItsOwnPublishedState() {
        val publisher = PlaylistLoadStatePublisher()
        val old = PlaylistActionTicket(8L, 12L)
        publisher.publish(old)
        var state = PlaylistLoadState(loading = true, wholeAction = true, showDialog = true)
        assertFalse(old.isCurrent(8L, 13L))
        if (publisher.release(old)) state = PlaylistLoadState()
        assertFalse(state.loading)

        val newer = PlaylistActionTicket(9L, 13L)
        publisher.publish(newer)
        state = PlaylistLoadState(loading = true, wholeAction = true)
        if (publisher.release(old)) state = PlaylistLoadState()
        assertTrue(state.loading)
        assertSame(newer, publisher.owner)
    }

    @Test
    fun everyTransientGuardSuppressesBothKindsOfLoading_andFinitePagingIgnoresRecommendationsToggle() {
        assertTrue(mayStartQueueLoading(false, false, false, false))
        for (guard in 0..3) {
            assertFalse(mayStartQueueLoading(guard == 0, guard == 1, guard == 2, guard == 3))
        }
        val finite = YouTubePlaylistQueue(WatchEndpoint(playlistId = "finite"), snapshot = FinitePlaylistSnapshot(
            continuation = "next", phase = FinitePlaylistPhase.PAGEABLE,
        ))
        var finiteStarts = 0
        var recommendationStarts = 0
        evaluateAcceptedQueueLoading(finite, true, { true }, { finiteStarts++ }, { recommendationStarts++ },
            allowRecommendations = false)
        assertEquals(1, finiteStarts)
        assertEquals(0, recommendationStarts)
    }

    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()
}

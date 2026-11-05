package moe.rukamori.archivetune.shared.data.repository

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.data.recommend.QueueTailRecommendation
import moe.rukamori.archivetune.shared.data.recommend.RecommendationSignalSnapshot
import moe.rukamori.archivetune.shared.data.recommend.RecommendationSignals
import moe.rukamori.archivetune.shared.data.recommend.queueTailSeed
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
import moe.rukamori.archivetune.shared.domain.recommend.DiversityConstraints
import moe.rukamori.archivetune.shared.domain.recommend.ListenFact
import moe.rukamori.archivetune.shared.domain.recommend.ListeningOutcome
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentPage
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueLocatorResolver
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.repository.QueueTail
import moe.rukamori.archivetune.shared.domain.repository.TypedContentRepository
import moe.rukamori.archivetune.shared.domain.repository.radioQueueRequest

private const val TEST_NOW = 1_700_000_000_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L

/**
 * The production path: [PortableQueueOwner] fetches one provider page through [PortableInfiniteQueue], hands it to the
 * real [QueueTailRecommendation] with a fake signal port, and commits only the planner's selection. The fixtures keep
 * the provider page concrete so the assertions prove the owner consumed the plan, not just the pure policy.
 */
class PortableQueueTailRecommendationTest {
    @Test
    fun theProductionTailSkipsARecentProviderFirstSongAndCommitsTheLaterEligibleOne() = runTest {
        val h = Harness(backgroundScope, testScheduler)
        val seed = song("seed", artistId = "seed-artist")
        val recent = song("recent", artistId = "recent-artist")
        val eligible = song("eligible", artistId = "eligible-artist")
        h.playlist = listOf(seed)
        h.radioReply = { _, _ -> ContentPage(listOf(recent, eligible), "next-page") }
        h.signals.snapshot =
            RecommendationSignalSnapshot(facts = listOf(completedFact(recent.item, TEST_NOW - 1_000L)))
        h.load()
        h.enabled.value = true
        runCurrent()

        assertEquals(1, h.signals.calls)
        assertEquals(1, h.radioCalls.size)
        assertEquals(listOf(seed.occurrenceId, eligible.occurrenceId), h.ids())
        assertEquals(listOf(seed.occurrenceId, eligible.occurrenceId), h.playOrder())
        assertEquals("next-page", h.owner.state.value.tail?.continuation)
        assertFalse(h.ids().contains(recent.occurrenceId))
    }

    @Test
    fun savedCompletedFactsOutrankAnUnknownDurationPartialFact() = runTest {
        val h = Harness(backgroundScope, testScheduler)
        val knownSong = song("known-fact", artistId = "known-artist")
        val unknownSong = song("unknown-fact", artistId = "unknown-artist")
        val knownCandidate = song("known-candidate", artistId = "known-artist", albumId = "known-album")
        val unknownCandidate = song("unknown-candidate", artistId = "unknown-artist", albumId = "unknown-album")
        h.playlist = listOf(song("seed", artistId = "seed-artist"))
        h.radioReply = { _, _ -> ContentPage(listOf(unknownCandidate, knownCandidate)) }
        val known = completedFact(knownSong.item, TEST_NOW - DAY_MS)
        // A missing duration can reach PARTIAL at most; it must never be promoted to COMPLETED.
        val unknown =
            ListenFact(
                ref = unknownSong.item.ref,
                artistIds = listOf("unknown-artist"),
                albumId = null,
                playedMs = 180_000L,
                durationMs = null,
                atMs = TEST_NOW - DAY_MS,
            )
        assertEquals(ListeningOutcome.COMPLETED, known.outcome)
        assertEquals(ListeningOutcome.PARTIAL, unknown.outcome)
        h.signals.snapshot = RecommendationSignalSnapshot(facts = listOf(unknown, known))
        h.load()
        h.enabled.value = true
        runCurrent()

        assertEquals(
            listOf(knownCandidate.occurrenceId, unknownCandidate.occurrenceId),
            h.ids().drop(1),
        )
    }

    @Test
    fun theLastSongInPlayOrderSuppliesTheArtistBoundary() = runTest {
        val h = Harness(backgroundScope, testScheduler)
        val first = song("first", artistId = "artist-x")
        val middle = song("middle", artistId = "artist-y")
        val current = song("current", artistId = "artist-x")
        val candidate = song("candidate", artistId = "artist-x")
        h.radioReply = { _, _ -> ContentPage(listOf(candidate)) }
        h.enabled.value = true
        // Storage order ends with artist-x; the shuffled play order ends with artist-y, which is the run that matters.
        h.restore(
            QueueState(
                revision = 3L,
                entries = listOf(first, middle, current),
                currentOccurrenceId = current.occurrenceId,
                playOrder = listOf(current.occurrenceId, first.occurrenceId, middle.occurrenceId),
                phase = FinitePlaylistPhase.UNKNOWN,
                shuffleEnabled = true,
                tail = QueueTail(radioQueueRequest(first.item.ref), "next-page", kind = QueueCollectionKind.RADIO),
            ),
        )
        h.refresh()
        runCurrent()

        assertEquals(1, h.signals.calls)
        val state = h.owner.state.value
        // The stored timeline is untouched and still ends with artist-x; only the play order's last song fenced the
        // candidate. If the planner had read entries order instead, this candidate would have been rejected.
        assertEquals(
            listOf(first.occurrenceId, middle.occurrenceId, current.occurrenceId),
            state.entries.dropLast(1).map { it.occurrenceId },
        )
        assertEquals(current.occurrenceId, state.entries.dropLast(1).last().occurrenceId)
        assertEquals(candidate.occurrenceId, state.entries.last().occurrenceId)
        assertEquals(
            listOf(current.occurrenceId, first.occurrenceId, middle.occurrenceId, candidate.occurrenceId),
            state.playOrder,
        )
    }

    @Test
    fun aManualInsertWaitsForTheInFlightPlanAndKeepsItsKind() = runTest {
        val h = Harness(backgroundScope, testScheduler)
        h.playlist = listOf(song("seed", artistId = "seed-artist"))
        h.radioReply = { _, _ -> ContentPage(listOf(song("planned", artistId = "planned-artist")), "next-page") }
        h.load()
        val hold = CompletableDeferred<Unit>()
        h.signals.hold = hold
        h.enabled.value = true
        runCurrent()
        assertEquals(1, h.signals.calls)
        assertTrue(h.owner.infiniteQueueLoading.value)

        val manual = song("manual")
        val insertion = async {
            h.owner.withQueueAccess { h.owner.insert(listOf(manual), UserQueuedKind.ADD_TO_QUEUE) }
        }
        runCurrent()
        // The plan still owns the queue lock while its signals are suspended, so manual edits queue behind it.
        assertFalse(insertion.isCompleted)
        hold.complete(Unit)
        runCurrent()

        assertTrue(insertion.isCompleted)
        assertEquals(1, insertion.await())
        val state = h.owner.state.value
        assertEquals(listOf("seed", "manual", "planned"), state.entries.map { it.occurrenceId })
        assertEquals(mapOf("manual" to UserQueuedKind.ADD_TO_QUEUE), state.manualKinds)
    }

    @Test
    fun finiteQueuesRepeatAndUnknownLegacyNeverCallThePlanner() = runTest {
        val h = Harness(backgroundScope, testScheduler)
        h.enabled.value = true
        h.owner.withQueueAccess {
            h.owner.replace(listOf(song("liked1"), song("liked2")), "liked2", playWhenReady = false, staysFinite = true)
        }
        runCurrent()
        h.refresh()
        runCurrent()

        h.restore(
            QueueState(
                revision = 4L,
                entries = listOf(song("owed")),
                currentOccurrenceId = "owed",
                playOrder = listOf("owed"),
                phase = FinitePlaylistPhase.EXHAUSTED,
                tail = QueueTail(playlistRequest(), "owed-page", kind = QueueCollectionKind.FINITE),
            ),
        )
        h.refresh()
        runCurrent()

        h.repeat = QueueRepeatMode.ALL
        h.playlist = listOf(song("finite"))
        h.load()
        h.refresh()
        runCurrent()

        h.repeat = QueueRepeatMode.OFF
        h.restore(
            QueueState(
                revision = 6L,
                entries = listOf(song("legacy")),
                currentOccurrenceId = "legacy",
                playOrder = listOf("legacy"),
                phase = FinitePlaylistPhase.UNKNOWN,
            ),
        )
        h.observe("legacy", 1L, ended = true)
        runCurrent()

        val local = song("local", provider = ContentProvider.LOCAL)
        h.restore(
            QueueState(
                revision = 7L,
                entries = listOf(local),
                currentOccurrenceId = local.occurrenceId,
                playOrder = listOf(local.occurrenceId),
                phase = FinitePlaylistPhase.EXHAUSTED,
            ),
        )
        h.refresh()
        runCurrent()

        assertEquals(0, h.signals.calls)
        assertEquals(0, h.radioCalls.size)
    }

    @Test
    fun aPageBeyondOneHundredCandidatesIsBoundedBeforeScoring() = runTest {
        val h = Harness(backgroundScope, testScheduler)
        val seed = song("seed", artistId = "seed-artist")
        h.playlist = listOf(seed)
        val page = (1..150).map { song("c$it", artistId = "artist-$it", albumId = "album-$it") }
        h.radioReply = { _, _ -> ContentPage(page, "next-page") }
        // The newest forty facts are fillers, so the boosted refs are not recency-rejected: only the 100-candidate
        // bound can keep them out of the scored batch.
        val fillers = (1..45).map { completedFact(song("f$it", artistId = "filler-$it").item, TEST_NOW - HOUR_MS) }
        val boosted = (101..110).map { completedFact(page[it - 1].item, TEST_NOW - DAY_MS) }
        h.signals.snapshot = RecommendationSignalSnapshot(facts = fillers + boosted)
        h.load()
        h.enabled.value = true
        runCurrent()

        assertEquals(1, h.signals.calls)
        val appended = h.ids().drop(1)
        assertEquals(10, appended.size)
        assertTrue(appended.all { it.removePrefix("c").toInt() in 1..100 }, "scored beyond the bounded batch: $appended")
        // Capping the scored batch must not discard the page's own provider continuation.
        assertEquals("next-page", h.owner.state.value.tail?.continuation)
    }

    @Test
    fun playedAndManualEntriesDoNotConsumeTheAutomaticTailBudget() = runTest {
        val h = Harness(backgroundScope, testScheduler, constraints = DiversityConstraints(maxTailItems = 11))
        h.playlist = listOf(song("seed", artistId = "seed-artist"))
        val firstPage = (1..10).map { song("a$it", artistId = "artist-a$it", albumId = "album-a$it") }
        val secondPage = (1..20).map { song("b$it", artistId = "artist-b$it", albumId = "album-b$it") }
        val thirdPage = (1..20).map { song("c$it", artistId = "artist-c$it", albumId = "album-c$it") }
        h.radioReply = { _, token ->
            when (token) {
                null -> ContentPage(firstPage, "next-page")
                "next-page" -> ContentPage(secondPage, "next2")
                else -> ContentPage(thirdPage)
            }
        }
        h.load()
        h.enabled.value = true
        runCurrent()
        assertEquals(11, h.ids().size)

        // The planner's seeded order, not the provider page order, is the queue. Pick the seventh actual automatic
        // entry so exactly three of that batch remain ahead; selecting provider id "a7" would not guarantee that.
        val initialBatch = h.owner.state.value.playOrder.drop(1).toSet()
        val initialOrder = h.owner.state.value.playOrder
        val selected = initialOrder[initialOrder.size - 4]
        assertEquals(10, initialBatch.size)
        assertTrue(selected in initialBatch)
        h.enabled.value = false
        runCurrent()
        h.owner.withQueueAccess { h.owner.select(selected, 0L) }
        val afterSelection = h.owner.state.value.playOrder
        val selectedIndex = afterSelection.indexOf(selected)
        assertTrue(selectedIndex >= 0)
        assertEquals(3, afterSelection.drop(selectedIndex + 1).count { it in initialBatch })

        // The manual pair lands between the current seventh automatic song and the remaining three.
        h.owner.withQueueAccess {
            h.owner.insert(listOf(song("manual1"), song("manual2")), UserQueuedKind.ADD_TO_QUEUE)
        }
        h.owner.withQueueAccess { h.owner.select("manual1", 0L) }
        val before = h.ids()
        h.enabled.value = true
        runCurrent()

        val appended = h.ids().drop(before.size)
        // Budget 11 - 3 still-queued automatic songs; played and manual entries are not part of that count.
        assertEquals(8, appended.size)
        assertTrue(appended.all { it.startsWith("b") })

        // A third batch proves a long-lived queue does not accumulate consumed markers into the budget, and that
        // pruning keeps the two still-pending automatic ids accounted for: 11 - 2 leaves nine slots.
        h.enabled.value = false
        runCurrent()
        h.owner.withQueueAccess { h.owner.select(appended[5], 0L) }
        val beforeThird = h.ids()
        h.enabled.value = true
        runCurrent()
        val third = h.ids().drop(beforeThird.size)
        assertEquals(9, third.size)
        assertTrue(third.all { it.startsWith("c") })
        assertEquals(
            mapOf("manual1" to UserQueuedKind.ADD_TO_QUEUE, "manual2" to UserQueuedKind.ADD_TO_QUEUE),
            h.owner.state.value.manualKinds,
        )
    }

    @Test
    fun aLargeManualQueueDoesNotConsumeTheAutomaticTailBudget() = runTest {
        val h = Harness(backgroundScope, testScheduler, constraints = DiversityConstraints(maxTailItems = 12))
        val manual = (1..60).map { song("manual$it", artistId = "manual-artist-$it") }
        val current = manual[56]
        val ordered = manual.take(56) + current + manual.drop(57)
        h.radioReply = { _, _ -> ContentPage((1..20).map { song("fresh$it", artistId = "fresh-artist-$it") }) }
        h.restore(
            QueueState(
                revision = 5L,
                entries = ordered,
                currentOccurrenceId = current.occurrenceId,
                playOrder = ordered.map { it.occurrenceId },
                phase = FinitePlaylistPhase.EXHAUSTED,
                manualKinds = ordered.associate { it.occurrenceId to UserQueuedKind.ADD_TO_QUEUE },
                tail = QueueTail(radioQueueRequest(current.item.ref), "next-page", kind = QueueCollectionKind.RADIO),
            ),
        )
        h.enabled.value = true
        h.refresh()
        runCurrent()

        val appended = h.ids().drop(ordered.size)
        assertEquals(10, appended.size)
        assertTrue(appended.all { it.startsWith("fresh") })
        assertEquals(60, h.owner.state.value.manualKinds.size)
    }

    @Test
    fun anEmptyPlanAdvancesTheContinuationAndExhaustionDropsTheStaleToken() = runTest {
        val h = Harness(backgroundScope, testScheduler)
        h.playlist = listOf(song("seed", artistId = "same-artist"))
        val replies =
            mutableListOf(
                ContentPage(listOf(song("rejected", artistId = "same-artist")), "t1"),
                ContentPage(emptyList(), null),
                ContentPage(listOf(song("accepted", artistId = "other-artist")), null),
            )
        var replyIndex = 0
        h.radioReply = { _, _ -> replies[minOf(replyIndex++, replies.lastIndex)] }
        h.load()
        h.enabled.value = true
        runCurrent()

        // Nothing passed the artist fence, but the page's continuation advanced and the empty result backed off.
        assertEquals(listOf("seed"), h.ids())
        assertEquals("t1", h.owner.state.value.tail?.continuation)
        assertEquals(1, h.radioCalls.size)
        assertNull(h.radioCalls.single().second)
        assertEquals(1, h.signals.calls)
        h.nowMs = 29_999L
        h.refresh()
        runCurrent()
        assertEquals(1, h.radioCalls.size)

        h.nowMs = 30_000L
        h.refresh()
        runCurrent()
        // The exhausted continuation is dropped instead of being retried or reused as a finite completion.
        assertEquals(listOf("seed"), h.ids())
        assertEquals(QueueCollectionKind.RADIO, h.owner.state.value.tail?.kind)
        assertNull(h.owner.state.value.tail?.continuation)
        assertEquals(1, h.signals.calls)

        h.nowMs = 90_000L
        h.refresh()
        runCurrent()
        assertEquals(listOf("seed", "accepted"), h.ids())
        assertNull(h.owner.state.value.tail?.continuation)
        assertEquals(listOf(null, "t1", null), h.radioCalls.map { it.second })
        assertEquals(2, h.signals.calls)
    }

    @Test
    fun anExhaustedInitialPageDoesNotInventARadioTail() = runTest {
        val h = Harness(backgroundScope, testScheduler)
        h.playlist = listOf(song("seed", artistId = "seed-artist"))
        h.radioReply = { _, _ -> ContentPage(emptyList(), null) }
        h.load()
        h.enabled.value = true
        runCurrent()

        // An initial page with no songs and no continuation is exhaustion, not a completed finite source.
        assertEquals(listOf("seed"), h.ids())
        assertNull(h.owner.state.value.tail)
        assertEquals(1, h.radioCalls.size)
        assertEquals(0, h.signals.calls)

        h.nowMs = 29_999L
        h.refresh()
        runCurrent()
        assertEquals(1, h.radioCalls.size)
        h.nowMs = 30_000L
        h.refresh()
        runCurrent()
        assertEquals(2, h.radioCalls.size)
        assertEquals(listOf("seed"), h.ids())
        assertNull(h.owner.state.value.tail)
    }

    @Test
    fun disablingOrClosingWhileSignalsSuspendNeverPublishesThePlan() = runTest {
        val h = Harness(backgroundScope, testScheduler)
        h.playlist = listOf(song("seed", artistId = "seed-artist"))
        h.radioReply = { _, _ -> ContentPage(listOf(song("planned", artistId = "planned-artist"))) }
        h.load()
        val hold = CompletableDeferred<Unit>()
        h.signals.hold = hold
        h.enabled.value = true
        runCurrent()
        assertEquals(1, h.signals.calls)

        h.enabled.value = false
        runCurrent()
        hold.complete(Unit)
        runCurrent()
        assertEquals(listOf("seed"), h.ids())
        assertNull(h.owner.state.value.tail)
        assertFalse(h.owner.infiniteQueueLoading.value)

        val closed = Harness(backgroundScope, testScheduler)
        closed.playlist = listOf(song("seed"))
        closed.radioReply = { _, _ -> ContentPage(listOf(song("planned"))) }
        closed.load()
        val secondHold = CompletableDeferred<Unit>()
        closed.signals.hold = secondHold
        closed.enabled.value = true
        runCurrent()
        assertEquals(1, closed.signals.calls)
        closed.owner.close()
        runCurrent()
        assertEquals(listOf("seed"), closed.ids())
        assertNull(closed.owner.state.value.tail)
        assertFalse(closed.owner.infiniteQueueLoading.value)
    }

    @Test
    fun replacingTheQueueWhileSignalsSuspendLeavesNoStaleRecommendation() = runTest {
        val h = Harness(backgroundScope, testScheduler)
        h.playlist = listOf(song("seed", artistId = "seed-artist"))
        h.radioReply = { _, _ -> ContentPage(listOf(song("stale", artistId = "stale-artist")), "stale-page") }
        h.load()
        val hold = CompletableDeferred<Unit>()
        h.signals.hold = hold
        h.enabled.value = true
        runCurrent()
        assertEquals(1, h.signals.calls)

        val replacement = async {
            h.owner.withQueueAccess {
                h.owner.replace(listOf(song("new1"), song("new2")), "new1", playWhenReady = false)
            }
        }
        runCurrent()
        assertFalse(replacement.isCompleted)
        // The replacement's own refill after the swap finds no candidates, so nothing can resurrect the stale page.
        h.radioReply = { _, _ -> ContentPage(emptyList()) }
        hold.complete(Unit)
        runCurrent()

        assertTrue(replacement.isCompleted)
        assertEquals(listOf("new1", "new2"), h.ids())
        assertFalse(h.ids().contains("stale"))
        assertNull(h.owner.state.value.tail)
    }

    @Test
    fun theSameSeedFactsAndClockProduceTheSameTailOnEveryOwner() = runTest {
        val first = Harness(backgroundScope, testScheduler)
        val second = Harness(backgroundScope, testScheduler)
        for (h in listOf(first, second)) {
            h.playlist = listOf(song("seed", artistId = "seed-artist"))
            h.radioReply = { _, _ ->
                ContentPage((1..12).map { song("t$it", artistId = "artist-t$it", albumId = "album-t$it") }, "next-page")
            }
            h.load()
            h.enabled.value = true
        }
        runCurrent()

        assertEquals(first.ids(), second.ids())
        assertEquals(first.playOrder(), second.playOrder())
        assertEquals(queueTailSeed(song("seed").item.ref), queueTailSeed(song("seed").item.ref))
        assertTrue(queueTailSeed(song("seed").item.ref) != queueTailSeed(song("other").item.ref))
    }

    @Test
    fun providerOrderIsKeptWithoutAnInjectedPlanner() = runTest {
        val h = Harness(backgroundScope, testScheduler, injectRecommendation = false)
        h.playlist = listOf(song("seed", artistId = "seed-artist"))
        h.radioReply = { _, _ ->
            ContentPage(
                listOf(song("provider-first", artistId = "x"), song("provider-second", artistId = "x")),
            )
        }
        h.signals.snapshot = RecommendationSignalSnapshot(facts = listOf(completedFact(song("provider-first").item, TEST_NOW)))
        h.load()
        h.enabled.value = true
        runCurrent()

        assertEquals(0, h.signals.calls)
        assertEquals(listOf("seed", "provider-first", "provider-second"), h.ids())
    }

    private class Harness(
        scope: CoroutineScope,
        testScheduler: TestCoroutineScheduler,
        constraints: DiversityConstraints = DiversityConstraints.Default,
        injectRecommendation: Boolean = true,
    ) {
        val enabled = MutableStateFlow(false)
        val signals = CountedSignals()
        var repeat = QueueRepeatMode.OFF
        var nowMs = 0L
        var playlist: List<ContentOccurrence> = emptyList()
        var radioReply: suspend (ContentRequest, String?) -> ContentPage = { _, _ -> ContentPage(emptyList()) }
        val radioCalls = mutableListOf<Pair<ContentRequest, String?>>()
        private val port = RecordingPort()
        private val repository = object : TypedContentRepository {
            override val provider = ContentProvider.YOUTUBE
            override suspend fun page(request: ContentRequest, continuation: String?): ContentPage {
                if (request.surface != ContentSurface.RADIO) return ContentPage(playlist)
                radioCalls += request to continuation
                return radioReply(request, continuation)
            }
            override suspend fun item(ref: ContentRef): ContentItem = error("Unused fixture")
        }
        val recommendation = QueueTailRecommendation(
            signals = signals,
            nowMs = { TEST_NOW },
            offsetMsAt = { 0L },
            computeDispatcher = UnconfinedTestDispatcher(testScheduler),
            constraints = constraints,
        )
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
            recommendation = recommendation.takeIf { injectRecommendation },
        )

        suspend fun load() = owner.withQueueAccess {
            owner.loadCollection(QueueLoadRequest(playlistRequest(), QueueCollectionKind.FINITE))
        }

        suspend fun restore(state: QueueState) = owner.withQueueAccess {
            owner.restore(QueueCheckpoint(state, 0L))
        }

        suspend fun refresh() = owner.withQueueAccess { owner.refreshInfiniteQueue() }
        fun ids() = owner.state.value.entries.map { it.occurrenceId }
        fun playOrder() = owner.state.value.playOrder
        fun observe(id: String, tick: Long, ended: Boolean = false) {
            port.snapshots.value = PlaybackSnapshot(
                occurrenceId = id, mediaId = id, playbackGeneration = 1L, discontinuityGeneration = 0L,
                positionMs = tick, sampleMonotonicNanos = tick, durationMs = 180_000L,
                isSeekable = true, playbackSpeed = 1f, playWhenReady = true, isAudioAdvancing = !ended,
                isBuffering = false, outputRoute = PlaybackOutputRoute.LOCAL, isEnded = ended,
            )
        }
    }

    private class CountedSignals : RecommendationSignals {
        var snapshot = RecommendationSignalSnapshot()
        var hold: CompletableDeferred<Unit>? = null
        var calls = 0

        override suspend fun snapshot(recommendedRefs: Set<ContentRef>): RecommendationSignalSnapshot {
            calls++
            hold?.await()
            return snapshot
        }
    }

    private class RecordingPort : PlaybackPort {
        override val snapshots = MutableStateFlow<PlaybackSnapshot?>(null)
        override val failures = MutableSharedFlow<PlaybackFailure>()
        override fun submit(command: PlaybackCommand) {}
        override fun close() {}
    }

    companion object {
        private fun song(
            occurrence: String,
            media: String = occurrence,
            artistId: String? = null,
            albumId: String? = null,
            provider: ContentProvider = ContentProvider.YOUTUBE,
        ) = ContentOccurrence(
            occurrence,
            ContentItem(
                ref = ContentRef(provider.id, ContentKind.SONG, media),
                title = occurrence,
                durationMs = 180_000L,
                artists = listOfNotNull(artistId?.let { ContentCredit(name = it, id = it) }),
                albumId = albumId,
            ),
        )

        private fun completedFact(item: ContentItem, atMs: Long) = ListenFact(
            ref = item.ref,
            artistIds = item.artists.mapNotNull { it.id },
            albumId = item.albumId,
            playedMs = item.durationMs ?: 180_000L,
            durationMs = item.durationMs,
            atMs = atMs,
        )

        private fun playlistRequest() = ContentRequest(
            ContentProvider.YOUTUBE,
            ContentSurface.PLAYLIST,
            parent = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PLAYLIST, "PL-fixture"),
        )
    }
}

package moe.rukamori.archivetune.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.shared.data.recommend.QueueTailRecommendation
import moe.rukamori.archivetune.shared.data.recommend.RecommendationSignalSnapshot
import moe.rukamori.archivetune.shared.data.recommend.RecommendationSignals
import moe.rukamori.archivetune.shared.domain.recommend.DiversityConstraints
import moe.rukamori.archivetune.shared.domain.recommend.ListenFact
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

private const val TEST_NOW = 1_700_000_000_000L

/**
 * Exercises the real [AndroidInfiniteQueue] decision path with the real adapter/planner, deterministic page fetches,
 * a fixed clock, a metadata-reader fixture and a Java-only Player/Timeline fake. No network, Room, Media3 renderer or
 * Android runtime resource is involved.
 */
class AndroidInfiniteQueueRecommendationTest {
    @Test
    fun aRecentProviderFirstCandidateIsDroppedAndTheSelectedOriginalItemIsAppended() = runTest {
        val fixture = Fixture()
        val h = Harness(backgroundScope, testScheduler, fixture)
        val seed = h.player.items.single()
        val recent = fixture.item("recent", artistId = "recent-artist")
        val eligible = fixture.item("eligible", artistId = "eligible-artist")
        h.initialPage = listOf(recent, eligible)
        h.signals.value = RecommendationSignalSnapshot(facts = listOf(factFor(fixture, recent, TEST_NOW - 1_000L)))

        h.owner.consider(seed.mediaId)
        runCurrent()

        assertEquals(1, h.signals.calls)
        assertEquals(listOf("seed", "eligible"), h.player.items.map { it.mediaId })
        assertTrue(h.player.items.last() === eligible)
        assertEquals(listOf(eligible), h.marked)
        assertEquals(1, h.saves)
    }

    @Test
    fun aFixedSeedProducesTheSameAppendOnEveryOwner() = runTest {
        val fixture = Fixture()
        val pages = (1..12).map { fixture.item("t$it", artistId = "artist-t$it", albumId = "album-t$it") }
        val first = Harness(backgroundScope, testScheduler, fixture).also { it.initialPage = pages }
        val second = Harness(backgroundScope, testScheduler, fixture).also { it.initialPage = pages }
        first.owner.consider("seed")
        second.owner.consider("seed")
        runCurrent()

        assertEquals(
            first.player.items.drop(1).map { it.mediaId },
            second.player.items.drop(1).map { it.mediaId },
        )
    }

    @Test
    fun aManualArrivalWhileSignalsSuspendStaysAheadAndALiveDuplicateIsExcluded() = runTest {
        val fixture = Fixture()
        val h = Harness(backgroundScope, testScheduler, fixture)
        val seed = h.player.items.single()
        val eligible = fixture.item("eligible", artistId = "eligible-artist")
        val second = fixture.item("second", artistId = "second-artist")
        h.initialPage = listOf(eligible, second)
        val hold = CompletableDeferred<Unit>()
        h.signals.hold = hold
        h.owner.consider(seed.mediaId)
        runCurrent()
        assertEquals(1, h.signals.calls)

        val manual = fixture.item("manual")
        h.player.items.add(manual)
        h.player.items.add(eligible)
        hold.complete(Unit)
        runCurrent()

        assertEquals(listOf("seed", "manual", "eligible", "second"), h.player.items.map { it.mediaId })
        assertTrue(h.player.items[1] === manual)
        assertEquals(listOf(second), h.marked)
    }

    @Test
    fun disablingDuringScoringNeverAppends() = runTest {
        val fixture = Fixture()
        val h = Harness(backgroundScope, testScheduler, fixture)
        h.initialPage = listOf(fixture.item("candidate", artistId = "artist"))
        val hold = CompletableDeferred<Unit>()
        h.signals.hold = hold
        h.owner.consider("seed")
        runCurrent()
        assertEquals(1, h.signals.calls)

        h.enabled = false
        hold.complete(Unit)
        runCurrent()

        assertEquals(listOf("seed"), h.player.items.map { it.mediaId })
        assertTrue(h.marked.isEmpty())
        assertTrue(h.loading.value.not())
    }

    @Test
    fun repeatAllDuringScoringNeverAppends() = runTest {
        val fixture = Fixture()
        val h = Harness(backgroundScope, testScheduler, fixture)
        h.initialPage = listOf(fixture.item("candidate", artistId = "artist"))
        val hold = CompletableDeferred<Unit>()
        h.signals.hold = hold
        h.owner.consider("seed")
        runCurrent()
        h.player.repeat = Player.REPEAT_MODE_ALL
        hold.complete(Unit)
        runCurrent()

        assertEquals(listOf("seed"), h.player.items.map { it.mediaId })
        assertTrue(h.marked.isEmpty())
    }

    @Test
    fun cancellingDuringScoringNeverAppends() = runTest {
        val fixture = Fixture()
        val h = Harness(backgroundScope, testScheduler, fixture)
        h.initialPage = listOf(fixture.item("candidate", artistId = "artist"))
        val hold = CompletableDeferred<Unit>()
        h.signals.hold = hold
        h.owner.consider("seed")
        runCurrent()
        h.owner.cancel()
        runCurrent()

        assertEquals(listOf("seed"), h.player.items.map { it.mediaId })
        assertTrue(h.marked.isEmpty())
        assertTrue(h.loading.value.not())
    }

    @Test
    fun aSongBlockedWhileScoringSuspendsIsNotAppended() = runTest {
        val fixture = Fixture()
        val h = Harness(backgroundScope, testScheduler, fixture)
        val candidate = fixture.item("blocked-later", artistId = "artist")
        h.initialPage = listOf(candidate)
        val hold = CompletableDeferred<Unit>()
        h.signals.hold = hold
        h.owner.consider("seed")
        runCurrent()
        assertEquals(1, h.signals.calls)

        h.blockedSongs += candidate.mediaId
        hold.complete(Unit)
        runCurrent()

        assertEquals(listOf("seed"), h.player.items.map { it.mediaId })
        assertTrue(h.marked.isEmpty())
    }

    @Test
    fun theShuffledPlayOrderFencesTheArtistRunAndCountsFutureAutomatics() = runTest {
        val fixture = Fixture()
        val h = Harness(backgroundScope, testScheduler, fixture, constraints = DiversityConstraints(maxTailItems = 2))
        val current = fixture.item("current", artistId = "current")
        val yLast = fixture.item("y-last", artistId = "artist-y")
        val xLast = fixture.item("x-last", artistId = "artist-x")
        val xAuto = fixture.item("x-auto", artistId = "artist-x")
        h.player.items.clear()
        h.player.items += listOf(current, xAuto, yLast, xLast)
        h.player.currentIndex = 0
        h.player.shuffle = true
        // Shuffled order ends artist-y and places the automatic song before current; the timeline ends artist-x.
        h.player.playOrder = listOf(1, 0, 3, 2)
        h.automaticIds += xAuto.mediaId
        val candidateX = fixture.item("candidate-x", artistId = "artist-x")
        val candidateZ = fixture.item("candidate-z", artistId = "artist-z")
        h.initialPage = listOf(candidateX, candidateZ)
        h.signals.value = RecommendationSignalSnapshot(facts = listOf(tasteFact("artist-x")))

        h.owner.consider("seed")
        runCurrent()

        // Timeline would end artist-x and reject candidate-x. The shuffled order ends artist-y, and the automatic song
        // sits before current, so no automatic slot is pending and both candidates fit the two-slot budget.
        assertEquals(setOf(candidateX, candidateZ), h.marked.toSet())
    }

    @Test
    fun theFixtureMetadataMakesTheRunArtistFenceEffective() = runTest {
        val fixture = Fixture()
        val h = Harness(backgroundScope, testScheduler, fixture, constraints = DiversityConstraints(maxTailItems = 1))
        val current = fixture.item("current", artistId = "current")
        val yLast = fixture.item("y-last", artistId = "artist-y")
        val xLast = fixture.item("x-last", artistId = "artist-x")
        h.player.items.clear()
        h.player.items += listOf(current, yLast, xLast)
        h.player.currentIndex = 0
        h.player.shuffle = true
        h.player.playOrder = listOf(0, 2, 1) // shuffled order ends artist-y, while the timeline ends artist-x.
        val candidateX = fixture.item("candidate-x", artistId = "artist-x")
        val candidateY = fixture.item("candidate-y", artistId = "artist-y")
        h.initialPage = listOf(candidateX, candidateY)
        // Without the fixture metadata the run artist is unknown and this higher-affinity candidate-y would win.
        h.signals.value = RecommendationSignalSnapshot(facts = listOf(tasteFact("artist-y")))

        h.owner.consider("seed")
        runCurrent()

        assertEquals(listOf(candidateX), h.marked)
    }

    @Test
    fun anEmptyPlanPreservesTheRadioSourceAndBacksOffWithoutRefilling() = runTest {
        val fixture = Fixture()
        val h = Harness(backgroundScope, testScheduler, fixture)
        h.initialPage = listOf(fixture.item("rejected", artistId = "seed-artist"))
        h.owner.consider("seed")
        runCurrent()

        assertEquals(listOf("seed"), h.player.items.map { it.mediaId })
        assertEquals(1, h.fetchInitialCalls)
        assertEquals(1, h.signals.calls)
        assertNotNull(h.attached)
        assertTrue(h.marked.isEmpty())

        h.owner.consider("seed")
        runCurrent()
        assertEquals(1, h.fetchInitialCalls)

        h.now = 30_000L
        h.owner.consider("seed")
        runCurrent()
        assertEquals(2, h.fetchInitialCalls)
        assertEquals(listOf("seed"), h.player.items.map { it.mediaId })
    }

    @Test
    fun aTrulyExhaustedPageAppendsNothingAndDoesNotKeepAStalePage() = runTest {
        val fixture = Fixture()
        val h = Harness(backgroundScope, testScheduler, fixture)
        h.initialPage = emptyList()
        h.owner.consider("seed")
        runCurrent()

        assertEquals(listOf("seed"), h.player.items.map { it.mediaId })
        assertEquals(1, h.fetchInitialCalls)
        assertEquals(0, h.signals.calls)
        assertNotNull(h.attached)

        h.now = 30_000L
        h.owner.consider("seed")
        runCurrent()
        assertEquals(2, h.fetchInitialCalls)
        assertEquals(listOf("seed"), h.player.items.map { it.mediaId })
        assertEquals(0, h.signals.calls)
    }

    @Test
    fun aNonRecommendationQueueBypassesThePlanner() = runTest {
        val fixture = Fixture()
        val h = Harness(backgroundScope, testScheduler, fixture)
        h.currentQueue = FakeQueue(pageAvailable = true)
        h.nextPage = listOf(fixture.item("p1", artistId = "x"), fixture.item("p2", artistId = "y"))
        h.owner.consider("seed")
        runCurrent()

        assertEquals(0, h.signals.calls)
        assertEquals(listOf("seed", "p1", "p2"), h.player.items.map { it.mediaId })
        assertTrue(h.marked.isEmpty())
        assertNull(h.attached)
    }

    @Test
    fun pendingAutomaticMarkersLimitTheAppendedTail() = runTest {
        val fixture = Fixture()
        val h = Harness(backgroundScope, testScheduler, fixture, constraints = DiversityConstraints(maxTailItems = 3))
        h.player.items.add(fixture.item("auto1", artistId = "auto1"))
        h.player.items.add(fixture.item("auto2", artistId = "auto2"))
        h.automaticIds.addAll(listOf("auto1", "auto2"))
        h.initialPage = (1..10).map { fixture.item("c$it", artistId = "artist-$it", albumId = "album-$it") }
        h.owner.consider("seed")
        runCurrent()

        // Three slots minus the two automatic songs still ahead leave exactly one recommendation slot.
        assertEquals(1, h.player.items.size - 3)
        assertEquals(1, h.marked.size)
        assertEquals(1, h.signals.calls)
    }

    private class Harness(
        private val scope: CoroutineScope,
        testScheduler: TestCoroutineScheduler,
        private val fixture: Fixture,
        constraints: DiversityConstraints = DiversityConstraints.Default,
    ) {
        val player = FakePlayer()
        var currentQueue: Queue = FakeQueue()
        var enabled = true
        var mayLoad = true
        var now = 0L
        var initialPage: List<MediaItem> = emptyList()
        var nextPage: List<MediaItem> = emptyList()
        var fetchInitialCalls = 0
        var fetchNextCalls = 0
        var attached: Queue? = null
        var saves = 0
        val marked = mutableListOf<MediaItem>()
        val automaticIds = mutableSetOf<String>()
        val blockedSongs = mutableSetOf<String>()
        val signals = CountedSignals()
        val loading = MutableStateFlow(false)

        private val shared =
            QueueTailRecommendation(
                signals = signals,
                nowMs = { TEST_NOW },
                offsetMsAt = { 0L },
                computeDispatcher = UnconfinedTestDispatcher(testScheduler),
                constraints = constraints,
            )
        private val adapter =
            AndroidQueueTailRecommendation(
                recommendation = shared,
                automaticIds = automaticIds,
                metadataOf = fixture.reader,
                blockedSongIds = { blockedSongs },
                computeDispatcher = UnconfinedTestDispatcher(testScheduler),
            )
        val owner =
            AndroidInfiniteQueue(
                scope = { scope },
                player = { player.player },
                queue = { currentQueue },
                enabled = { enabled },
                mayLoad = { mayLoad },
                maySeed = { true },
                isLocal = { false },
                filter = { it },
                attachRadio = { queue ->
                    attached = queue
                    currentQueue = queue
                },
                markAutomatic = { marked.addAll(it) },
                setShuffleOrder = {},
                save = { saves++ },
                loading = loading,
                recommendation = adapter,
                fetchInitial = {
                    fetchInitialCalls++
                    initialPage
                },
                fetchNext = { _, _ ->
                    fetchNextCalls++
                    nextPage
                },
                nowMs = { now },
            )

        init {
            player.items.add(fixture.item("seed", artistId = "seed-artist"))
        }
    }

    private class Fixture {
        private val metadata = IdentityHashMap<MediaItem, MediaMetadata>()
        val reader: (MediaItem) -> MediaMetadata? = { metadata[it] }

        fun item(
            id: String,
            artistId: String? = null,
            albumId: String? = null,
            duration: Int = 180,
        ): MediaItem {
            val item = MediaItem.Builder().setMediaId(id).build()
            metadata[item] =
                MediaMetadata(
                    id = id,
                    title = id,
                    artists = listOfNotNull(artistId?.let { MediaMetadata.Artist(id = it, name = it) }),
                    duration = duration,
                    album = albumId?.let { MediaMetadata.Album(id = it, title = it) },
                )
            return item
        }

        fun metadataOf(item: MediaItem): MediaMetadata = requireNotNull(metadata[item])
    }

    private class CountedSignals : RecommendationSignals {
        var value = RecommendationSignalSnapshot()
        var calls = 0
        var hold: CompletableDeferred<Unit>? = null

        override suspend fun snapshot(recommendedRefs: Set<ContentRef>): RecommendationSignalSnapshot {
            calls++
            hold?.await()
            return value
        }
    }

    private class FakeQueue(
        var pageAvailable: Boolean = false,
    ) : Queue {
        override val preloadItem: MediaMetadata? = null

        override suspend fun getInitialStatus(): Queue.Status = Queue.Status(title = null, items = emptyList(), mediaItemIndex = 0)

        override fun hasNextPage(): Boolean = pageAvailable

        override suspend fun nextPage(): List<MediaItem> = emptyList()
    }

    private class FakePlayer {
        val items = mutableListOf<MediaItem>()
        var currentIndex = 0
        var repeat = Player.REPEAT_MODE_OFF
        var shuffle = false
        var playOrder: List<Int>? = null
        var state = Player.STATE_READY
        var playWhenReady = true
        val timeline: Timeline =
            object : Timeline() {
                override fun getWindowCount(): Int = items.size

                override fun getWindow(
                    windowIndex: Int,
                    window: Timeline.Window,
                    defaultPositionProjectionUs: Long,
                ): Timeline.Window = window

                override fun getPeriodCount(): Int = items.size

                override fun getPeriod(
                    periodIndex: Int,
                    period: Timeline.Period,
                    setIds: Boolean,
                ): Timeline.Period = period

                override fun getIndexOfPeriod(uid: Any): Int = C.INDEX_UNSET

                override fun getUidOfPeriod(periodIndex: Int): Any = Any()

                override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int {
                    if (items.isEmpty()) return C.INDEX_UNSET
                    return if (shuffleModeEnabled) playOrder?.firstOrNull() ?: 0 else 0
                }

                override fun getNextWindowIndex(
                    windowIndex: Int,
                    repeatMode: Int,
                    shuffleModeEnabled: Boolean,
                ): Int {
                    if (!shuffleModeEnabled) {
                        return if (windowIndex + 1 in items.indices) windowIndex + 1 else C.INDEX_UNSET
                    }
                    val order = playOrder ?: return if (windowIndex + 1 in items.indices) windowIndex + 1 else C.INDEX_UNSET
                    val position = order.indexOf(windowIndex)
                    return if (position >= 0 && position + 1 < order.size) order[position + 1] else C.INDEX_UNSET
                }
            }
        val player: Player =
            Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
                when (method.name) {
                    "getCurrentTimeline" -> timeline
                    "getCurrentMediaItemIndex" -> currentIndex
                    "getMediaItemCount" -> items.size
                    "getMediaItemAt" -> items[args!![0] as Int]
                    "getCurrentMediaItem" -> items.getOrNull(currentIndex)
                    "getRepeatMode" -> repeat
                    "getShuffleModeEnabled" -> shuffle
                    "getPlaybackState" -> state
                    "getPlayWhenReady" -> playWhenReady
                    "addMediaItems" -> {
                        val added =
                            when (args!!.size) {
                                1 -> args[0] as Collection<MediaItem>
                                else -> args[1] as Collection<MediaItem>
                            }
                        items.addAll(added)
                        null
                    }
                    "hasNextMediaItem" -> currentIndex < items.lastIndex
                    "seekToNextMediaItem" -> {
                        currentIndex++
                        null
                    }
                    "prepare", "play" -> null
                    "toString" -> "FakePlayer"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    else -> throw AssertionError("Unexpected Player.${method.name}")
                }
            } as Player
    }

    private companion object {
        private fun factFor(fixture: Fixture, item: MediaItem, atMs: Long): ListenFact {
            val metadata = fixture.metadataOf(item)
            return ListenFact(
                ref = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, metadata.id),
                artistIds = metadata.artists.mapNotNull { it.id },
                albumId = metadata.album?.id,
                playedMs = 180_000L,
                durationMs = 180_000L,
                atMs = atMs,
            )
        }

        private fun tasteFact(artistId: String): ListenFact =
            ListenFact(
                ref = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, "taste-$artistId"),
                artistIds = listOf(artistId),
                albumId = null,
                playedMs = 180_000L,
                durationMs = 180_000L,
                atMs = TEST_NOW - 1_000L,
            )
    }
}

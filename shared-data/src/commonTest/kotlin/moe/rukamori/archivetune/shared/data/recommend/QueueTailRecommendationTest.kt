package moe.rukamori.archivetune.shared.data.recommend

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.recommend.DiversityConstraints
import moe.rukamori.archivetune.shared.domain.recommend.ListenFact
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.settings.BlockedSongRef

class QueueTailRecommendationTest {
    @Test
    fun theAdapterPlansFromTypedSignalFacts() = runTest {
        val preferred = dataSong("preferred")
        val other = dataSong("other", artistId = "other-artist")
        val signals = signalsOf(listOf(completed(preferred)))
        val adapter = recommendation(signals)
        val plan = adapter.planTail(listOf(dataOccurrence(preferred), dataOccurrence(other)), emptyList(), seed = 0)
        assertEquals(listOf(preferred.ref, other.ref), plan.items.map { it.item.ref })
    }

    @Test
    fun blockedSongsAndArtistsNeverReachThePlan() = runTest {
        val blockedSong = dataSong("blocked-song")
        val blockedArtist = dataSong("blocked-artist-song", artistId = "danger")
        val allowed = dataSong("allowed")
        val snapshot =
            RecommendationSignalSnapshot(
                facts = emptyList(),
                blockedSongs = setOf(BlockedSongRef(blockedSong.ref.provider, blockedSong.ref.id)),
                blockedArtists = setOf(ContentRef(blockedSong.ref.provider, ContentKind.ARTIST, "danger")),
            )
        val plan =
            recommendation(FakeSignals(snapshot)).planTail(
                listOf(dataOccurrence(blockedSong), dataOccurrence(blockedArtist), dataOccurrence(allowed)),
                emptyList(),
                seed = 0,
                budget = 3,
            )
        assertEquals(listOf(allowed.ref), plan.items.map { it.item.ref })
        assertEquals(2, plan.rejections.blocked)
    }

    @Test
    fun anAlreadyFullTailNeedsNoCandidates() = runTest {
        val song = dataSong("a")
        val plan =
            recommendation(signalsOf(emptyList())).planTail(
                listOf(dataOccurrence(song)),
                emptyList(),
                seed = 0,
                budget = 5,
                existingTailSize = QueueTailLimits.MAX_TAIL_ITEMS,
            )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun theExistingTailSizeReducesTheBudget() = runTest {
        val songs = (1..3).map { dataSong("s$it", artistId = "artist-$it") }
        val constraints = DiversityConstraints(maxTailItems = 5)
        val plan =
            recommendation(signalsOf(emptyList()), constraints).planTail(
                songs.map { dataOccurrence(it) },
                emptyList(),
                seed = 0,
                budget = 5,
                existingTailSize = 4,
            )
        assertEquals(1, plan.items.size)
    }

    @Test
    fun anAllBlockedTailIsEmptyRatherThanAThrow() = runTest {
        val song = dataSong("a")
        val snapshot =
            RecommendationSignalSnapshot(
                blockedSongs = setOf(BlockedSongRef(song.ref.provider, song.ref.id)),
            )
        val plan = recommendation(FakeSignals(snapshot)).planTail(listOf(dataOccurrence(song)), emptyList(), seed = 0)
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.rejections.blocked)
    }

    @Test
    fun aReplayFromTheRecentWindowIsRejected() = runTest {
        val justPlayed = dataSong("just-played")
        val signals = signalsOf(listOf(completed(justPlayed, atMs = DATA_TEST_NOW - 1_000L)))
        val plan = recommendation(signals).planTail(listOf(dataOccurrence(justPlayed)), emptyList(), seed = 0)
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.rejections.recency)
    }

    @Test
    fun theSameSeedProducesTheSameTail() = runTest {
        val songs = (1..6).map { dataSong("s$it", artistId = "artist-$it") }
        val adapter = recommendation(signalsOf(emptyList()))
        val first = adapter.planTail(songs.map { dataOccurrence(it) }, emptyList(), seed = 11)
        val second = adapter.planTail(songs.map { dataOccurrence(it) }, emptyList(), seed = 11)
        assertEquals(first.items.map { it.occurrenceId }, second.items.map { it.occurrenceId })
    }

    @Test
    fun rankCandidatesFiltersBlocksAndRespectsItsLimit() = runTest {
        val blocked = dataSong("blocked")
        val allowed = dataSong("allowed")
        val snapshot =
            RecommendationSignalSnapshot(
                blockedSongs = setOf(BlockedSongRef(blocked.ref.provider, blocked.ref.id)),
            )
        val ranked =
            recommendation(FakeSignals(snapshot)).rankCandidates(
                listOf(dataOccurrence(blocked), dataOccurrence(allowed)),
                seed = 0,
                limit = 1,
            )
        assertEquals(listOf(allowed.ref), ranked.map { it.item.ref })
    }

    @Test
    fun rankCandidatesUsesTheTasteProfileOrder() = runTest {
        val preferred = dataSong("preferred")
        val other = dataSong("other", artistId = "other-artist")
        val ranked =
            recommendation(signalsOf(listOf(completed(preferred)))).rankCandidates(
                listOf(dataOccurrence(other), dataOccurrence(preferred)),
                seed = 0,
                limit = 2,
            )
        assertEquals(listOf(preferred.ref, other.ref), ranked.map { it.item.ref })
    }

    @Test
    fun theAdapterForwardsTheHostsInMemoryRecommendedRefs() = runTest {
        val song = dataSong("a")
        val signals = signalsOf(emptyList())
        val recommended = setOf(song.ref)
        val adapter =
            QueueTailRecommendation(
                signals = signals,
                nowMs = { DATA_TEST_NOW },
                recommendedRefs = { recommended },
                computeDispatcher = UnconfinedTestDispatcher(),
            )
        adapter.planTail(listOf(dataOccurrence(song)), emptyList(), seed = 0)
        assertEquals(recommended, signals.lastRecommendedRefs)
    }

    private fun recommendation(
        signals: RecommendationSignals,
        constraints: DiversityConstraints = DiversityConstraints.Default,
    ): QueueTailRecommendation =
        QueueTailRecommendation(
            signals = signals,
            nowMs = { DATA_TEST_NOW },
            offsetMsAt = { 0L },
            computeDispatcher = UnconfinedTestDispatcher(),
            constraints = constraints,
        )

    private fun completed(item: ContentItem, atMs: Long = DATA_TEST_NOW): ListenFact =
        ListenFact(
            ref = item.ref,
            artistIds = item.artists.mapNotNull { it.id },
            albumId = item.albumId,
            playedMs = item.durationMs ?: 180_000L,
            durationMs = item.durationMs,
            atMs = atMs,
        )

    private fun signalsOf(facts: List<ListenFact>): FakeSignals =
        FakeSignals(RecommendationSignalSnapshot(facts))

    private class FakeSignals(private val value: RecommendationSignalSnapshot) : RecommendationSignals {
        var lastRecommendedRefs: Set<ContentRef> = emptySet()

        override suspend fun snapshot(recommendedRefs: Set<ContentRef>): RecommendationSignalSnapshot {
            lastRecommendedRefs = recommendedRefs
            return value
        }
    }
}

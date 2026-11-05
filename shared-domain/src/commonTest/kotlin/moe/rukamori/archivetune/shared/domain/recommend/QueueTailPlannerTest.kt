package moe.rukamori.archivetune.shared.domain.recommend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.repository.ContentItem

class QueueTailPlannerTest {
    private fun tail(item: ContentItem, exploratory: Boolean = false) =
        TailCandidate(testOccurrence(item), exploratory = exploratory)

    @Test
    fun aTrackInTheRecentWindowIsDropped() {
        val recent = testSong("recent")
        val allowed = testSong("allowed")
        val recentRefs = listOf(recent.ref) + (1..39).map { testSong("other-$it").ref }
        val plan =
            QueueTailPlanner.plan(
                listOf(tail(recent), tail(allowed)),
                emptyList(),
                recentRefs,
                emptyProfile(),
                budget = 2,
                seed = 0,
            )
        assertEquals(listOf(allowed.ref), plan.items.map { it.item.ref })
        assertEquals(1, plan.rejections.recency)
    }

    @Test
    fun aTrackOlderThanTheRecentWindowIsAllowed() {
        val allowed = testSong("allowed")
        val recentRefs = (1..40).map { testSong("other-$it").ref }
        val plan =
            QueueTailPlanner.plan(
                listOf(tail(allowed)),
                emptyList(),
                recentRefs,
                emptyProfile(),
                budget = 1,
                seed = 0,
            )
        assertEquals(listOf(allowed.ref), plan.items.map { it.item.ref })
    }

    @Test
    fun twoTracksInARowByOneArtistAreRejectedAtTheSecondSlot() {
        val first = testSong("a", artistId = "x")
        val second = testSong("b", artistId = "x")
        val plan =
            QueueTailPlanner.plan(
                listOf(tail(first), tail(second)),
                emptyList(),
                emptyList(),
                emptyProfile(),
                budget = 2,
                seed = 0,
            )
        assertEquals(1, plan.items.size)
        assertEquals(1, plan.rejections.consecutiveArtist)
    }

    @Test
    fun anArtistCanReturnAfterTheRollingWindowMovesPastIt() {
        val first = testSong("a1", artistId = "x")
        val other = testSong("b1", artistId = "y")
        val third = testSong("c1", artistId = "z")
        val returning = testSong("a2", artistId = "x")
        val facts =
            listOf(
                completedFact(first),
                partialFact(other),
                partialFact(other, atMs = TEST_NOW - 1_000L),
                partialFact(third),
                partialFact(third, atMs = TEST_NOW - 1_000L),
                partialFact(returning),
            )
        val plan =
            QueueTailPlanner.plan(
                listOf(tail(first), tail(other), tail(third), tail(returning)),
                emptyList(),
                emptyList(),
                profileWith(facts),
                budget = 4,
                seed = 0,
                constraints = DiversityConstraints(artistCap = 1, artistWindow = 3),
            )
        assertEquals(4, plan.items.size)
        assertTrue(plan.items.any { it.item.ref == returning.ref })
    }

    @Test
    fun aSecondTrackFromOneAlbumIsRejectedInTheWindow() {
        val first = testSong("a", artistId = "x", albumId = "album")
        val second = testSong("b", artistId = "y", albumId = "album")
        val plan =
            QueueTailPlanner.plan(
                listOf(tail(first), tail(second)),
                emptyList(),
                emptyList(),
                profileWith(listOf(completedFact(first), completedFact(second))),
                budget = 2,
                seed = 0,
            )
        assertEquals(1, plan.items.size)
        assertEquals(1, plan.rejections.albumWindow)
    }

    @Test
    fun anArtistIsCappedAtTwoTracksInTheRollingWindow() {
        val first = testSong("a1", artistId = "x")
        val other = testSong("b1", artistId = "y")
        val second = testSong("a2", artistId = "x")
        val third = testSong("c1", artistId = "z")
        val blocked = testSong("a3", artistId = "x")
        val facts =
            listOf(
                partialFact(first),
                partialFact(first, atMs = TEST_NOW - 1_000L),
                partialFact(other),
                partialFact(other, atMs = TEST_NOW - 1_000L),
                partialFact(second),
                partialFact(third),
                partialFact(blocked),
            )
        val plan =
            QueueTailPlanner.plan(
                listOf(tail(first), tail(other), tail(second), tail(third), tail(blocked)),
                emptyList(),
                emptyList(),
                profileWith(facts),
                budget = 5,
                seed = 0,
                constraints = DiversityConstraints(maxConsecutiveSameArtist = 2, artistCap = 2, artistWindow = 10),
            )
        assertEquals(4, plan.items.size)
        assertEquals(1, plan.rejections.artistWindow)
    }

    @Test
    fun allCandidatesRejectedReturnsAnEmptyPlanWithoutThrowing() {
        val playing = testOccurrence(testSong("playing", artistId = "x"))
        val sameArtist = testSong("next", artistId = "x")
        val plan =
            QueueTailPlanner.plan(
                listOf(tail(sameArtist)),
                listOf(playing),
                emptyList(),
                emptyProfile(),
                budget = 1,
                seed = 0,
            )
        assertTrue(plan.isEmpty)
        assertTrue(plan.rejections.consecutiveArtist >= 1)
    }

    @Test
    fun aCandidateBelowTheAffinityFloorIsRejectedWhenAnotherFits() {
        val skipped = testSong("skipped")
        val completed = testSong("completed")
        val plan =
            QueueTailPlanner.plan(
                listOf(tail(skipped), tail(completed)),
                emptyList(),
                emptyList(),
                profileWith(listOf(quickSkipFact(skipped), completedFact(completed))),
                budget = 2,
                seed = 0,
            )
        assertEquals(listOf(completed.ref), plan.items.map { it.item.ref })
        assertEquals(1, plan.rejections.negativeAffinity)
        assertFalse(plan.affinityFloorRelaxed)
    }

    @Test
    fun theAffinityFloorRelaxesWhenNothingElseFits() {
        val skipped = testSong("skipped")
        val plan =
            QueueTailPlanner.plan(
                listOf(tail(skipped)),
                emptyList(),
                emptyList(),
                profileWith(listOf(quickSkipFact(skipped))),
                budget = 1,
                seed = 0,
            )
        assertEquals(listOf(skipped.ref), plan.items.map { it.item.ref })
        assertTrue(plan.affinityFloorRelaxed)
        assertEquals(0, plan.rejections.negativeAffinity)
    }

    @Test
    fun mmrPrefersADiverseTrackOverANearDuplicateOfTheFirstPick() {
        val first = testSong("a", artistId = "shared", albumId = "album")
        val duplicate = testSong("b", artistId = "shared", albumId = "album")
        val diverse = testSong("c", artistId = "solo", albumId = "solo-album")
        val plan =
            QueueTailPlanner.plan(
                listOf(tail(first), tail(duplicate), tail(diverse)),
                emptyList(),
                emptyList(),
                profileWith(listOf(completedFact(first), partialFact(diverse))),
                budget = 2,
                seed = 0,
                constraints = DiversityConstraints(maxConsecutiveSameArtist = 2, artistCap = 10, albumCap = 10),
            )
        assertEquals(listOf(first.ref, diverse.ref), plan.items.map { it.item.ref })
    }

    @Test
    fun oneExplorationSlotInFiveTakesAnExploratoryCandidate() {
        val exploit = (1..5).map { testSong("exploit-$it", artistId = "artist-$it") }
        val explore = (1..3).map { testSong("explore-$it", artistId = "explore-artist-$it") }
        val facts = exploit.map { completedFact(it) } + explore.map { partialFact(it) }
        val plan =
            QueueTailPlanner.plan(
                exploit.map { tail(it) } + explore.map { tail(it, exploratory = true) },
                emptyList(),
                emptyList(),
                profileWith(facts),
                budget = 5,
                seed = 0,
            )
        assertEquals(5, plan.items.size)
        assertEquals(1, plan.exploreSlotsFilled)
        val exploreRefs = explore.map { it.ref }
        assertTrue(plan.items[4].item.ref in exploreRefs)
    }

    @Test
    fun explorationFallsBackToTheExploitListWhenNothingIsMarked() {
        val songs = (1..5).map { testSong("s$it", artistId = "artist-$it") }
        val plan =
            QueueTailPlanner.plan(
                songs.map { tail(it) },
                emptyList(),
                emptyList(),
                profileWith(songs.map { completedFact(it) }),
                budget = 5,
                seed = 0,
            )
        assertEquals(5, plan.items.size)
        assertEquals(0, plan.exploreSlotsFilled)
    }

    @Test
    fun theTailLengthIsBoundedIndependentlyOfTheProviderPage() {
        val songs = (1..5).map { testSong("s$it", artistId = "artist-$it") }
        val plan =
            QueueTailPlanner.plan(
                songs.map { tail(it) },
                emptyList(),
                emptyList(),
                profileWith(songs.map { completedFact(it) }),
                budget = 10,
                seed = 0,
                constraints = DiversityConstraints(maxTailItems = 3),
            )
        assertEquals(3, plan.items.size)
    }

    @Test
    fun aZeroBudgetProducesNoTail() {
        val plan = QueueTailPlanner.plan(emptyList(), emptyList(), emptyList(), emptyProfile(), 0, 0)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun aCandidateAlreadyInTheQueueOrAlreadyOfferedIsNotRepeated() {
        val queued = testSong("queued")
        val duplicate = testSong("duplicate")
        val fresh = testSong("fresh")
        val plan =
            QueueTailPlanner.plan(
                listOf(tail(duplicate), tail(duplicate), tail(fresh)),
                listOf(testOccurrence(queued), testOccurrence(duplicate)),
                emptyList(),
                emptyProfile(),
                budget = 3,
                seed = 0,
            )
        assertEquals(listOf(fresh.ref), plan.items.map { it.item.ref })
        assertEquals(2, plan.rejections.duplicate)
    }

    @Test
    fun thePlanIsStableForOneSeedAndDoesNotDependOnInputOrder() {
        val songs = (1..6).map { testSong("s$it", artistId = "artist-$it") }
        val profile = emptyProfile()
        val first = QueueTailPlanner.plan(songs.map { tail(it) }, emptyList(), emptyList(), profile, 6, seed = 3)
        val second =
            QueueTailPlanner.plan(
                songs.reversed().map { tail(it) },
                emptyList(),
                emptyList(),
                profile,
                6,
                seed = 3,
            )
        assertEquals(first.items.map { it.occurrenceId }, second.items.map { it.occurrenceId })
    }

    @Test
    fun thePlannerDoesNotMutateItsInputs() {
        val candidates = listOf(tail(testSong("a")), tail(testSong("b")))
        val queue = listOf(testOccurrence(testSong("queued")))
        val recent = listOf(testSong("played").ref)
        val candidateCopy = candidates.toList()
        val queueCopy = queue.toList()
        val recentCopy = recent.toList()
        QueueTailPlanner.plan(candidates, queue, recent, emptyProfile(), 2, 0)
        assertEquals(candidateCopy, candidates)
        assertEquals(queueCopy, queue)
        assertEquals(recentCopy, recent)
    }
}

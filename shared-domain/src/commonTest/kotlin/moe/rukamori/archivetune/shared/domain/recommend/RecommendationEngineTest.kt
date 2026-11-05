package moe.rukamori.archivetune.shared.domain.recommend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecommendationEngineTest {
    @Test
    fun theEngineFacadeRanksThroughTheProfile() {
        val played = testSong("played")
        val other = testSong("other")
        val profile = profileWith(listOf(completedFact(played)))
        val ranked = RecommendationEngine.rank(listOf(testOccurrence(other), testOccurrence(played)), profile, 2, 7)
        assertEquals(listOf(played.ref, other.ref), ranked.map { it.item.ref })
    }

    @Test
    fun theEngineFacadePlansThroughTheTailPlanner() {
        val song = testSong("a")
        val plan =
            RecommendationEngine.planTail(
                listOf(TailCandidate(testOccurrence(song))),
                emptyList(),
                emptyList(),
                emptyProfile(),
                budget = 1,
                seed = 0,
            )
        assertEquals(listOf(song.ref), plan.items.map { it.item.ref })
    }

    @Test
    fun anEmptyEnginePlanIsAnOrdinaryEmptyResult() {
        val plan = RecommendationEngine.planTail(emptyList(), emptyList(), emptyList(), emptyProfile(), 3, 0)
        assertTrue(plan.isEmpty)
    }
}

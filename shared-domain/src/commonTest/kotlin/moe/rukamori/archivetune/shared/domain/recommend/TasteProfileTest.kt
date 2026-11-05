package moe.rukamori.archivetune.shared.domain.recommend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

class TasteProfileTest {
    @Test
    fun aCompletedPlayRaisesSongAffinityToTheClamp() {
        val song = testSong("a")
        val profile = profileWith(listOf(completedFact(song)))
        assertEquals(TasteProfile.MAX_AFFINITY, profile.songAffinity(song.ref), 1e-9)
    }

    @Test
    fun aQuickSkipMakesAffinityNegative() {
        val song = testSong("a")
        val profile = profileWith(listOf(quickSkipFact(song)))
        assertEquals(TasteProfile.MIN_AFFINITY, profile.songAffinity(song.ref), 1e-9)
    }

    @Test
    fun oneQuickSkipAfterTenCompletionsStillLeavesPositiveAffinity() {
        val song = testSong("a")
        val facts = List(10) { completedFact(song, atMs = TEST_NOW - it * 1_000L) } + quickSkipFact(song)
        val profile = profileWith(facts)
        assertTrue(profile.songAffinity(song.ref) > 0.0)
        assertEquals(TasteProfile.MAX_AFFINITY, profile.songAffinity(song.ref), 1e-9)
    }

    @Test
    fun affinityHalvesAfterOneHalfLife() {
        val song = testSong("a")
        val profile = profileWith(listOf(completedFact(song, atMs = TEST_NOW - TasteProfile.HALF_LIFE_MS)))
        assertEquals(0.5, profile.songAffinity(song.ref), 1e-9)
    }

    @Test
    fun affinityQuartersAfterTwoHalfLives() {
        val song = testSong("a")
        val profile = profileWith(listOf(completedFact(song, atMs = TEST_NOW - 2 * TasteProfile.HALF_LIFE_MS)))
        assertEquals(0.25, profile.songAffinity(song.ref), 1e-9)
    }

    @Test
    fun aRecommendedListenCountsHalfAsMuchAsADeliberatePlay() {
        val song = testSong("a")
        val profile = profileWith(listOf(completedFact(song, wasRecommended = true)))
        assertEquals(0.5, profile.songAffinity(song.ref), 1e-9)
    }

    @Test
    fun artistAffinityAggregatesEverySongByThatArtist() {
        val first = testSong("a", artistId = "same")
        val second = testSong("b", artistId = "same")
        val profile = profileWith(listOf(completedFact(first), completedFact(second)))
        val artist = ContentRef(first.ref.provider, ContentKind.ARTIST, "same")
        assertEquals(TasteProfile.MAX_AFFINITY, profile.artistAffinity(artist), 1e-9)
    }

    @Test
    fun itemAffinityWeightsSongArtistAndAlbumOnlyWhenPresent() {
        val played = testSong("played", artistId = "shared", albumId = "album")
        val candidate = testSong("candidate", artistId = "shared", albumId = "album")
        val profile = profileWith(listOf(completedFact(played)))
        assertEquals(0.5, profile.itemAffinity(candidate), 1e-9)
    }

    @Test
    fun aRareContextUsesTheHigherContextWeight() {
        val song = testSong("a")
        val rare = profileWith(listOf(completedFact(song)))
        assertEquals(TasteProfile.RARE_CONTEXT_WEIGHT, rare.contextWeight, 1e-9)
        val common = profileWith(List(20) { completedFact(song, atMs = TEST_NOW - it * 1_000L) })
        assertEquals(TasteProfile.NORMAL_CONTEXT_WEIGHT, common.contextWeight, 1e-9)
    }

    @Test
    fun aRareContextCanOutrankTheLongTermProfile() {
        val morningSong = testSong("morning", artistId = "morning-artist")
        val daySong = testSong("day", artistId = "day-artist")
        val facts =
            listOf(
                completedFact(daySong, atMs = TEST_NOW - DAY_MS),
                completedFact(morningSong, atMs = TEST_NOW - TasteProfile.HALF_LIFE_MS),
            )
        val contextOf: (ListenFact) -> ListeningContext = { fact ->
            if (fact.ref == daySong.ref) ListeningContext(13, false) else ListeningContext(8, false)
        }
        val rare = profileWith(facts, context = ListeningContext(8, false), contextOf = contextOf)
        assertEquals(morningSong.ref, firstRef(rare, daySong, morningSong))

        val filler =
            List(20) {
                quickSkipFact(testSong("filler-$it", artistId = "filler"), atMs = TEST_NOW - 20 * DAY_MS)
            }
        val common = profileWith(facts + filler, context = ListeningContext(8, false), contextOf = contextOf)
        assertEquals(TasteProfile.NORMAL_CONTEXT_WEIGHT, common.contextWeight, 1e-9)
        assertEquals(daySong.ref, firstRef(common, daySong, morningSong))
    }

    @Test
    fun aDifferentHourBucketChangesTheOrder() {
        val morningSong = testSong("morning", artistId = "morning-artist")
        val daySong = testSong("day", artistId = "day-artist")
        val facts =
            listOf(
                completedFact(daySong, atMs = TEST_NOW - DAY_MS),
                completedFact(morningSong, atMs = TEST_NOW - TasteProfile.HALF_LIFE_MS),
            )
        val contextOf: (ListenFact) -> ListeningContext = { fact ->
            if (fact.ref == daySong.ref) ListeningContext(13, false) else ListeningContext(8, false)
        }
        val morning = profileWith(facts, context = ListeningContext(8, false), contextOf = contextOf)
        val night = profileWith(facts, context = ListeningContext(23, false), contextOf = contextOf)
        assertEquals(morningSong.ref, firstRef(morning, daySong, morningSong))
        assertEquals(daySong.ref, firstRef(night, daySong, morningSong))
    }

    @Test
    fun rankIsDeterministicForAFixedSeedAndIndependentOfInputOrder() {
        val candidates = (1..6).map { testOccurrence(testSong("s$it")) }
        val profile = emptyProfile()
        val first = profile.rank(candidates, 6, seed = 1)
        val second = profile.rank(candidates.reversed(), 6, seed = 1)
        assertEquals(first.map { it.occurrence.occurrenceId }, second.map { it.occurrence.occurrenceId })
        assertEquals("s1", first.first().occurrence.item.ref.id)
    }

    @Test
    fun aDifferentSeedReordersEqualScores() {
        val candidates = (1..6).map { testOccurrence(testSong("s$it")) }
        val profile = emptyProfile()
        assertNotEquals(
            profile.rank(candidates, 6, seed = 1).map { it.occurrence.item.ref.id },
            profile.rank(candidates, 6, seed = 2).map { it.occurrence.item.ref.id },
        )
    }

    private fun firstRef(profile: TasteProfile, vararg songs: ContentItem) =
        profile.rank(songs.map { testOccurrence(it) }, songs.size, 0).first().item.ref

    @Test
    fun rankRespectsItsLimitAndRejectsNonPositiveLimits() {
        val candidates = (1..4).map { testOccurrence(testSong("s$it")) }
        val profile = emptyProfile()
        assertEquals(2, profile.rank(candidates, 2, 0).size)
        assertTrue(profile.rank(candidates, 0, 0).isEmpty())
        assertTrue(profile.rank(emptyList(), 2, 0).isEmpty())
    }
}

package moe.rukamori.archivetune.shared.domain.recommend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiversityPolicyTest {
    @Test
    fun theSameCreditedSongHasFullSimilarity() {
        val left = testSong("a", artistId = "x", albumId = "album")
        val right = testSong("b", artistId = "x", albumId = "album")
        assertEquals(1.0, DiversityPolicy.similarity(left, right), 1e-9)
    }

    @Test
    fun disjointMetadataHasNoSimilarity() {
        val left = testSong("a", artistId = "x", albumId = "a")
        val right = testSong("b", artistId = "y", albumId = "b")
        assertEquals(0.0, DiversityPolicy.similarity(left, right), 1e-9)
    }

    @Test
    fun similarityIsTheJaccardOverArtistAndAlbumFeatures() {
        val left = testSong("a", artistId = "x", albumId = "a")
        val right = testSong("b", artistId = "x", albumId = "b")
        assertEquals(1.0 / 3.0, DiversityPolicy.similarity(left, right), 1e-9)
    }

    @Test
    fun anUnidentifiedPairHasNoSimilarityRatherThanFullSimilarity() {
        val left = testSong("a", artistId = null, albumId = null)
        val right = testSong("b", artistId = null, albumId = null)
        assertEquals(0.0, DiversityPolicy.similarity(left, right), 1e-9)
    }

    @Test
    fun recentRefsReturnsTheNewestFirstWindow() {
        val oldest = testSong("oldest")
        val middle = testSong("middle")
        val newest = testSong("newest")
        val facts =
            listOf(
                completedFact(oldest, atMs = TEST_NOW - 3 * DAY_MS),
                completedFact(newest, atMs = TEST_NOW - DAY_MS),
                completedFact(middle, atMs = TEST_NOW - 2 * DAY_MS),
            )
        assertEquals(listOf(newest.ref, middle.ref), DiversityPolicy.recentRefs(facts, 2))
    }

    @Test
    fun aZeroRecencyWindowReturnsNoRefs() {
        assertTrue(DiversityPolicy.recentRefs(listOf(completedFact(testSong("a"))), 0).isEmpty())
    }
}

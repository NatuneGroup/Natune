package moe.rukamori.archivetune.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `getSongsByIds` binds one SQLite variable per id, and the player cache's key set is
 * unbounded. A 1 GiB cache easily holds more ids than Android 11 and older accept in a statement,
 * which used to kill the process from `viewModelScope`.
 */
class CachePlaylistBatchingTest {
    @Test
    fun aCacheSizedIdSetIsSplitIntoStatementSizedBatches() {
        val cached = (1..4_500).mapTo(linkedSetOf()) { "song-$it" }

        val batches = pureCacheSongBatches(cached, downloadedIds = emptySet())

        assertEquals(5, batches.size)
        assertTrue(batches.all { it.size <= 900 })
        assertEquals(cached.toList(), batches.flatten())
    }

    @Test
    fun downloadedIdsAreDroppedBeforeTheBatchesAreBuilt() {
        val cached = (1..1_000).mapTo(linkedSetOf()) { "song-$it" }
        val downloaded = (1..50).map { "song-$it" }.toSet()

        val batches = pureCacheSongBatches(cached, downloaded)

        assertEquals(listOf(900, 50), batches.map { it.size })
        assertTrue(batches.flatten().none { it in downloaded })
    }

    @Test
    fun anEmptyCacheAsksTheDatabaseForNothing() {
        assertTrue(pureCacheSongBatches(emptySet(), emptySet()).isEmpty())
        assertTrue(pureCacheSongBatches(setOf("song"), setOf("song")).isEmpty())
    }
}

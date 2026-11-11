package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.coroutines.test.runTest
import io.ktor.utils.io.errors.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsHelperCacheTest {
    @Test
    fun theSixteenEntryCacheEvictsTheLeastRecentlyUsedEntry() =
        runTest {
            val cache = LyricsBoundedCache<String, Int>(16)
            (0 until 16).forEach { cache.put("k$it", it) }
            assertEquals(0, cache.get("k0"))

            cache.put("k16", 16)

            // Checked before any further get, because a get also refreshes recency.
            assertEquals(16, cache.size())
            assertEquals("k16", cache.keysLeastRecentFirst().last())
            assertEquals(null, cache.get("k1"))
            assertEquals(0, cache.get("k0"))
        }

    @Test
    fun aRepeatedPutRefreshesRecencyWithoutGrowingTheCache() =
        runTest {
            val cache = LyricsBoundedCache<String, Int>(2)
            cache.put("a", 1)
            cache.put("b", 2)
            cache.put("a", 3)

            assertEquals(listOf("b", "a"), cache.keysLeastRecentFirst())
            assertEquals(3, cache.get("a"))
        }

    @Test
    fun theHelperCacheForgetsTheLeastRecentlyUsedTrack() =
        runTest {
            var calls = 0
            val provider = FakeLyricsProvider("LrcLib") { calls++; PLAIN_TEXT }
            val helper = helperOf(listOf(provider), backgroundScope)

            (0..16).forEach { helper.getLyrics(request(id = "id$it")) }
            assertEquals(17, calls)

            helper.getLyrics(request(id = "id0"))
            assertEquals(18, calls)

            helper.getLyrics(request(id = "id16"))
            assertEquals(18, calls)
        }

    @Test
    fun aCachedAnswerAvoidsASecondProviderCall() =
        runTest {
            val provider = answering("LrcLib", PLAIN_TEXT)
            val helper = helperOf(listOf(provider), backgroundScope)

            helper.getLyrics(request())
            helper.getLyrics(request())

            assertEquals(1, provider.calls)
        }

    @Test
    fun forceRefreshBypassesTheCache() =
        runTest {
            val provider = answering("LrcLib", PLAIN_TEXT)
            val helper = helperOf(listOf(provider), backgroundScope)

            helper.getLyrics(request())
            helper.getLyrics(request(), forceRefresh = true)

            assertEquals(2, provider.calls)
        }

    @Test
    fun clearCacheForcesARefetch() =
        runTest {
            val provider = answering("LrcLib", PLAIN_TEXT)
            val helper = helperOf(listOf(provider), backgroundScope)

            helper.getLyrics(request())
            helper.clearCache()
            helper.getLyrics(request())

            assertEquals(2, provider.calls)
        }

    @Test
    fun aNotFoundAnswerIsNotCachedSoTheNextPlayRetries() =
        runTest {
            val provider = answering("LrcLib", null)
            val helper = helperOf(listOf(provider), backgroundScope)

            helper.getLyrics(request())
            helper.getLyrics(request())

            assertEquals(2, provider.calls)
        }

    @Test
    fun getAllLyricsReplaysTheStoredResultsWithoutCallingProviders() =
        runTest {
            val provider = answering("LrcLib", PLAIN_TEXT)
            val helper = helperOf(listOf(provider), backgroundScope)
            val first = mutableListOf<LyricsResult>()
            val replayed = mutableListOf<LyricsResult>()

            helper.getAllLyrics(request()) { first += it }
            helper.getAllLyrics(request()) { replayed += it }

            assertEquals(1, provider.calls)
            assertEquals(first, replayed)
        }

    @Test
    fun getAllLyricsCachesSiblingResultsAfterAnOrdinaryNoLyricsMiss() =
        runTest {
            val miss = FailedLyricsProvider("No lyrics", IllegalStateException("No lyrics"))
            val sibling = answering("Kugou", PLAIN_TEXT)
            val helper = helperOf(listOf(miss, sibling), backgroundScope)
            val first = mutableListOf<LyricsResult>()
            val replayed = mutableListOf<LyricsResult>()

            helper.getAllLyrics(request(), forceRefresh = true) { first += it }
            helper.getAllLyrics(request()) { replayed += it }

            assertEquals(listOf(LyricsResult("Kugou", PLAIN_TEXT)), first)
            assertEquals(first, replayed)
            assertEquals(1, miss.calls)
            assertEquals(1, sibling.calls)
        }

    @Test
    fun getAllLyricsRetriesAfterATransientProviderFailure() =
        runTest {
            val unavailable = FailedLyricsProvider("No connection", IOException("network timeout"))
            val sibling = answering("Kugou", PLAIN_TEXT)
            val helper = helperOf(listOf(unavailable, sibling), backgroundScope)
            val first = mutableListOf<LyricsResult>()
            val retried = mutableListOf<LyricsResult>()

            helper.getAllLyrics(request(), forceRefresh = true) { first += it }
            helper.getAllLyrics(request()) { retried += it }

            assertEquals(listOf(LyricsResult("Kugou", PLAIN_TEXT)), first)
            assertEquals(first, retried)
            assertEquals(2, unavailable.calls)
            assertEquals(2, sibling.calls)
        }

    @Test
    fun theWordSyncedUpgradeMemoRemembersASettledRowUntilItChanges() =
        runTest {
            val helper = helperOf(listOf(answering("LrcLib", PLAIN_TEXT)), backgroundScope, prioritizeWordSynced = true)

            assertTrue(helper.shouldAttemptWordSyncedUpgrade("id", PLAIN_TEXT, isRemoteSource = true))
            helper.noteWordSyncedUpgradeSettled("id", PLAIN_TEXT)

            assertFalse(helper.shouldAttemptWordSyncedUpgrade("id", PLAIN_TEXT, isRemoteSource = true))
            assertTrue(helper.shouldAttemptWordSyncedUpgrade("id", "a different plain row", isRemoteSource = true))
        }

    @Test
    fun theWordSyncedUpgradeMemoIsBoundedToSixtyFourTracks() =
        runTest {
            val helper = helperOf(listOf(answering("LrcLib", PLAIN_TEXT)), backgroundScope, prioritizeWordSynced = true)

            (0..64).forEach { helper.noteWordSyncedUpgradeSettled("id$it", PLAIN_TEXT) }

            assertTrue(helper.shouldAttemptWordSyncedUpgrade("id0", PLAIN_TEXT, isRemoteSource = true))
            assertFalse(helper.shouldAttemptWordSyncedUpgrade("id64", PLAIN_TEXT, isRemoteSource = true))
        }

    @Test
    fun theWordSyncedUpgradeIsNotAskedWhenTheRowIsAlreadyWordSyncedOrNotRemote() =
        runTest {
            val helper = helperOf(listOf(answering("LrcLib", PLAIN_TEXT)), backgroundScope, prioritizeWordSynced = true)

            assertFalse(helper.shouldAttemptWordSyncedUpgrade("id", WORD_SYNCED_TTML, isRemoteSource = true))
            assertFalse(helper.shouldAttemptWordSyncedUpgrade("id", PLAIN_TEXT, isRemoteSource = false))
        }
}

private class FailedLyricsProvider(
    override val name: String,
    private val failure: Throwable,
) : LyricsProvider {
    var calls = 0

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String?,
    ): Result<String> {
        calls++
        return Result.failure(failure)
    }
}

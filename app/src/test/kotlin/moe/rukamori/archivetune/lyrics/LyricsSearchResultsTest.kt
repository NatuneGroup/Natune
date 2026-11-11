package moe.rukamori.archivetune.lyrics

import android.content.Context
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.modules.ModuleLyricsProviders
import moe.rukamori.archivetune.utils.NetworkConnectivityObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LyricsSearchResultsTest {
    @Test
    fun aReturnedFailureKeepsSuccessfulSiblingResultsOutOfTheCache() = runTest {
        val failure = IOException("Tidal network failure")
        val provider = TestProvider(Result.failure(failure))
        val results = LyricsSearchResults()
        val sibling = LyricsResult("sibling", "[00:01.00]Hello")
        results.add(sibling)
        val emitted = mutableListOf<String>()
        var reported: Throwable? = null

        provider.getAllLyrics("id", "title", "artist", null, 180, onFailure = {
            reported = it
            results.failed()
        }, callback = emitted::add)

        assertSame(failure, reported)
        assertEquals(emptyList<String>(), emitted)
        assertNull(results.cacheableResults())

        provider.answer = Result.success("[00:02.00]Recovered")
        val retry = LyricsSearchResults()
        retry.add(sibling)
        provider.getAllLyrics("id", "title", "artist", null, 180, onFailure = { retry.failed() }) {
            retry.add(LyricsResult(provider.name, it))
        }
        assertEquals(listOf(sibling, LyricsResult(provider.name, "[00:02.00]Recovered")), retry.cacheableResults())
        assertEquals(2, provider.calls)
    }

    @Test
    fun aFailureAfterEmittingLyricsStillPreventsCaching() = runTest {
        val results = LyricsSearchResults()
        val emitted = mutableListOf<String>()
        val provider = object : LyricsProvider {
            override val name = "partial provider"
            override fun isEnabled(context: Context) = true
            override suspend fun getLyrics(id: String, title: String, artist: String, album: String?, duration: Int) =
                Result.success("Hello")

            override suspend fun getAllLyrics(
                id: String, title: String, artist: String, album: String?, duration: Int,
                onFailure: (Throwable) -> Unit, callback: (String) -> Unit,
            ) {
                callback("Hello")
                onFailure(IOException("second request failed"))
            }
        }

        provider.getAllLyrics("id", "title", "artist", null, 180, onFailure = { results.failed() }) {
            results.add(LyricsResult(provider.name, it))
            emitted += it
        }

        assertEquals(listOf("Hello"), emitted)
        assertNull(results.cacheableResults())
    }

    @Test
    fun noLyricsFailureCallbackAndSuccessfulSiblingRemainCacheable() = runTest {
        val miss = TestProvider(Result.failure(IllegalStateException("No lyrics")))
        val sibling = TestProvider(Result.success("[00:01.00]Found"), name = "sibling")
        val helper = helperFor(listOf(miss, sibling))
        val emitted = mutableListOf<LyricsResult>()
        try {
            helper.getAllLyrics("id", "Track", "Artist", null, 180, forceRefresh = true, callback = emitted::add)

            assertEquals(listOf(LyricsResult("sibling", "[00:01.00]Found")), emitted)
            assertEquals(1, miss.calls)
            assertEquals(1, sibling.calls)

            val replayed = mutableListOf<LyricsResult>()
            helper.getAllLyrics("id", "Track", "Artist", null, 180, callback = replayed::add)
            assertEquals(emitted, replayed)
            assertEquals(1, miss.calls)
            assertEquals(1, sibling.calls)
        } finally {
            helper.clearCache()
            helper.providerOverrideForTests = null
            helper.networkAvailableForTests = null
        }
    }

    @Test
    fun transientProviderFailureDoesNotCacheSuccessfulSiblingResults() = runTest {
        val unavailable = TestProvider(Result.failure(IOException("network timeout")))
        val sibling = TestProvider(Result.success("[00:01.00]Found"), name = "sibling")
        val helper = helperFor(listOf(unavailable, sibling))
        try {
            val emitted = mutableListOf<LyricsResult>()
            helper.getAllLyrics("id", "Track", "Artist", null, 180, forceRefresh = true, callback = emitted::add)
            assertEquals(listOf(LyricsResult("sibling", "[00:01.00]Found")), emitted)

            val retried = mutableListOf<LyricsResult>()
            helper.getAllLyrics("id", "Track", "Artist", null, 180, callback = retried::add)
            assertEquals(emitted, retried)
            assertEquals(2, unavailable.calls)
            assertEquals(2, sibling.calls)
        } finally {
            helper.clearCache()
            helper.providerOverrideForTests = null
            helper.networkAvailableForTests = null
        }
    }

    @Test
    fun anEmptySearchIsNotCached() {
        assertNull(LyricsSearchResults().cacheableResults())
    }

    private fun helperFor(providers: List<LyricsProvider>): LyricsHelper {
        val context = RuntimeEnvironment.getApplication()
        val moduleRepository = moe.rukamori.archivetune.modules.ModuleRepository(context)
        return LyricsHelper(context, NetworkConnectivityObserver(context), ModuleLyricsProviders(moduleRepository)).apply {
            providerOverrideForTests = providers
            networkAvailableForTests = true
        }
    }

    private class TestProvider(
        var answer: Result<String>,
        override val name: String = "test provider",
    ) : LyricsProvider {
        var calls = 0
        override fun isEnabled(context: Context) = true
        override suspend fun getLyrics(id: String, title: String, artist: String, album: String?, duration: Int): Result<String> {
            calls++
            return answer
        }
    }
}

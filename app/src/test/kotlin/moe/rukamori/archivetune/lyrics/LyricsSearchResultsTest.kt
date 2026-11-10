package moe.rukamori.archivetune.lyrics

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

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
    fun anEmptySearchIsNotCached() {
        assertNull(LyricsSearchResults().cacheableResults())
    }

    private class TestProvider(var answer: Result<String>) : LyricsProvider {
        override val name = "test provider"
        var calls = 0
        override fun isEnabled(context: Context) = true
        override suspend fun getLyrics(id: String, title: String, artist: String, album: String?, duration: Int): Result<String> {
            calls++
            return answer
        }
    }
}

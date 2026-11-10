package moe.rukamori.archivetune.lyrics

import moe.rukamori.archivetune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

class LyricsFetchOutcomeTest {
    @Test
    fun anUnreachableProviderIsAFailedLookup() {
        assertEquals(ProviderLyricsAnswer.FAILED, IOException("reset").toProviderLyricsAnswer())
        assertEquals(ProviderLyricsAnswer.FAILED, UnknownHostException("offline").toProviderLyricsAnswer())
        assertEquals(ProviderLyricsAnswer.FAILED, SocketTimeoutException().toProviderLyricsAnswer())
    }

    @Test
    fun aWrappedNetworkErrorIsStillAFailedLookup() {
        val wrapped = IllegalStateException("request failed", RuntimeException(SocketTimeoutException()))
        assertEquals(ProviderLyricsAnswer.FAILED, wrapped.toProviderLyricsAnswer())
    }

    @Test
    fun aCancelledRequestIsAFailedLookup() {
        assertEquals(ProviderLyricsAnswer.FAILED, CancellationException("timed out").toProviderLyricsAnswer())
    }

    @Test
    fun aProviderErrorThatIsNotANetworkErrorIsItsAnswer() {
        assertEquals(ProviderLyricsAnswer.MISSING, IllegalStateException("no match").toProviderLyricsAnswer())
        assertEquals(ProviderLyricsAnswer.MISSING, RuntimeException(IllegalArgumentException()).toProviderLyricsAnswer())
    }

    @Test
    fun aNetworkErrorBuriedTooDeepIsNotFollowed() {
        var error: Throwable = IOException("deep")
        repeat(8) { error = RuntimeException(error) }
        assertEquals(ProviderLyricsAnswer.MISSING, error.toProviderLyricsAnswer())
    }

    @Test
    fun aMissIsConclusiveOnlyWhenSomeProviderAnswered() {
        assertTrue(isConclusiveMiss(listOf(ProviderLyricsAnswer.FAILED, ProviderLyricsAnswer.MISSING)))
        assertFalse(isConclusiveMiss(listOf(ProviderLyricsAnswer.FAILED, ProviderLyricsAnswer.FAILED)))
        assertFalse(isConclusiveMiss(emptyList()))
    }

    @Test
    fun aRefetchReplacesStoredLyricsOnlyWhenItFoundSome() {
        assertEquals(
            LyricsRefetchResult.Updated,
            LyricsResult(providerName = "LrcLib", lyrics = "[00:01.00]hi").toRefetchResult(),
        )
        assertEquals(
            LyricsRefetchResult.NotFound,
            LyricsResult(providerName = "", lyrics = LYRICS_NOT_FOUND).toRefetchResult(),
        )
        assertEquals(LyricsRefetchResult.Unavailable, LyricsResult.Unavailable.toRefetchResult())
    }

    @Test
    fun onlyAnUnfinishedRefetchHasNoMessage() {
        assertEquals(null, LyricsRefetchResult.Updated.messageResId)
        assertTrue(LyricsRefetchResult.NotFound.messageResId != null)
        assertTrue(LyricsRefetchResult.Unavailable.messageResId != LyricsRefetchResult.NotFound.messageResId)
    }
}

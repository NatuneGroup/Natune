package moe.rukamori.archivetune.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LyricsProviderHttpTest {
    @Test
    fun aTransportExceptionReachesTheClassifier() = runBlocking {
        val failure = IOException("connection reset")
        withResponse(failure = failure) { client ->
            val result = lookup(client)
            val error = result.exceptionOrNull()!!
            assertTrue(error is IOException)
            assertEquals(failure.message, error.message)
            assertEquals(ProviderLyricsAnswer.FAILED, error.toProviderLyricsAnswer())
        }
    }

    @Test
    fun retryableHttpResponsesReachTheClassifierAsFailures() = runBlocking {
        for (status in listOf(429, 500, 502, 503, 599)) {
            withResponse(status, "temporary failure") { client ->
                assertEquals(ProviderLyricsAnswer.FAILED, lookup(client).exceptionOrNull()!!.toProviderLyricsAnswer())
            }
        }
    }

    @Test
    fun ktorResponseExceptionsAreClassifiedByStatusToo() = runBlocking {
        for (status in listOf(429, 500, 503, 404)) {
            withResponse(status, expectSuccess = true) { client ->
                val expected = if (status == 404) ProviderLyricsAnswer.MISSING else ProviderLyricsAnswer.FAILED
                val error = IllegalStateException("wrapped provider failure", lookup(client).exceptionOrNull())
                assertEquals(expected, error.toProviderLyricsAnswer())
            }
        }
    }

    @Test
    fun aNotFoundResponseOrEmptySuccessfulBodyIsAMiss() = runBlocking {
        for ((status, body) in listOf(404 to "not found", 200 to "", 200 to "  \n", 204 to "")) {
            withResponse(status, body) { client ->
                assertNull(LyricsProviderHttp.get(URL, client = client))
                assertEquals(ProviderLyricsAnswer.MISSING, lookup(client).exceptionOrNull()!!.toProviderLyricsAnswer())
            }
        }
    }

    @Test
    fun aGenuineNoLyricsPayloadIsStillAMiss() = runBlocking {
        withResponse(body = "{\"lyrics\":null}") { client ->
            assertEquals(ProviderLyricsAnswer.MISSING, lookup(client).exceptionOrNull()!!.toProviderLyricsAnswer())
        }
    }

    @Test
    fun realLrcRedProviderDistinguishesMissAndTransientHttpFailure() = runBlocking {
        withResponse(body = "{\"hits\":[]}") { client ->
            LyricsProviderHttp.setClientForTests(client)
            try {
                val result = withContext(Dispatchers.IO) {
                    LrcRedLyricsProvider.getLyrics("id", "title", "artist", null, 180)
                }
                assertEquals(ProviderLyricsAnswer.MISSING, result.exceptionOrNull()!!.toProviderLyricsAnswer())
            } finally {
                LyricsProviderHttp.setClientForTests(null)
            }
        }

        withResponse(503, "temporary failure") { client ->
            LyricsProviderHttp.setClientForTests(client)
            try {
                val result = withContext(Dispatchers.IO) {
                    LrcRedLyricsProvider.getLyrics("id", "title", "artist", null, 180)
                }
                assertEquals(ProviderLyricsAnswer.FAILED, result.exceptionOrNull()!!.toProviderLyricsAnswer())
            } finally {
                LyricsProviderHttp.setClientForTests(null)
            }
        }
    }

    @Test
    fun realBiniProviderDistinguishesMissAndTransientHttpFailure() = runBlocking {
        withResponse(body = "{\"results\":[]}") { client ->
            LyricsProviderHttp.setClientForTests(client)
            try {
                val result = withContext(Dispatchers.IO) {
                    BiniLyricsProvider.getLyrics("id", "title", "artist", null, 180)
                }
                assertEquals(ProviderLyricsAnswer.MISSING, result.exceptionOrNull()!!.toProviderLyricsAnswer())
            } finally {
                LyricsProviderHttp.setClientForTests(null)
            }
        }

        withResponse(429, "rate limited") { client ->
            LyricsProviderHttp.setClientForTests(client)
            try {
                val result = withContext(Dispatchers.IO) {
                    BiniLyricsProvider.getLyrics("id", "title", "artist", null, 180)
                }
                assertEquals(ProviderLyricsAnswer.FAILED, result.exceptionOrNull()!!.toProviderLyricsAnswer())
            } finally {
                LyricsProviderHttp.setClientForTests(null)
            }
        }
    }

    @Test
    fun anAnsweredRequestKeepsLyricsAndNonRetryableStatuses() = runBlocking {
        withResponse(body = "[00:01.00]Hello") { client ->
            assertEquals("[00:01.00]Hello", lookup(client).getOrThrow())
        }
        for (status in listOf(401, 403, 404)) {
            withResponse(status) { client ->
                val response = LyricsProviderHttp.getWithStatus(URL, client = client)
                assertEquals(status, response.status)
                assertNull(response.body)
            }
        }
    }

    private suspend fun lookup(client: HttpClient): Result<String> {
        LyricsProviderHttp.setClientForTests(client)
        return try {
            LrcRedLyricsProvider.getLyrics("id", "title", "artist", null, 180, isrc = "USAAA1234567")
        } finally {
            LyricsProviderHttp.setClientForTests(null)
        }
    }

    private suspend fun withResponse(
        status: Int = 200,
        body: String = "",
        failure: IOException? = null,
        expectSuccess: Boolean = false,
        block: suspend (HttpClient) -> Unit,
    ) {
        val client = HttpClient(OkHttp) {
            this.expectSuccess = expectSuccess
            engine {
                config {
                    addInterceptor { chain ->
                        if (failure != null) throw failure
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(status)
                            .message("test response")
                            .body(body.toResponseBody())
                            .build()
                    }
                }
            }
        }
        try {
            block(client)
        } finally {
            client.close()
        }
    }

    private companion object {
        const val URL = "https://lyrics.invalid/test"
    }
}

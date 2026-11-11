package moe.rukamori.archivetune.musixmatch

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class MusixmatchFailureTest {
    @Test
    fun tokenFailuresReachTheAllResultsCallback() = runBlocking {
        for (status in listOf(429, 500, 503, 599)) withResponse(status) { assertFailedSearch() }
        withResponse(failure = IOException("DNS failure")) { assertFailedSearch() }
    }

    @Test
    fun macroFailuresReachTheAllResultsCallback() = runBlocking {
        for (status in listOf(429, 503)) withResponse(status, tokenSucceeds = true) { assertFailedSearch() }
    }

    @Test
    fun anOrdinaryMacroMissIsStillSilent() = runBlocking {
        for (status in listOf(200, 404)) withResponse(status, tokenSucceeds = true) {
            val failures = mutableListOf<Throwable>()
            Musixmatch.getAllLyrics("Track", "Artist", onFailure = failures::add) { fail("Unexpected lyrics") }
            assertTrue(failures.isEmpty())
        }
    }

    private suspend fun assertFailedSearch() {
        val failures = mutableListOf<Throwable>()
        Musixmatch.getAllLyrics("Track", "Artist", onFailure = failures::add) { fail("Unexpected lyrics") }
        assertEquals(1, failures.size)
        assertTrue(failures.single() is IOException)
    }

    private suspend fun withResponse(
        status: Int = 200,
        tokenSucceeds: Boolean = false,
        failure: IOException? = null,
        block: suspend () -> Unit,
    ) {
        val client = HttpClient(OkHttp) {
            engine { config { addInterceptor { chain ->
                if (failure != null) throw failure
                val isToken = tokenSucceeds && chain.request().url.encodedPath.endsWith("token.get")
                val body = if (isToken) """{"message":{"header":{"status_code":200},"body":{"user_token":"test-token"}}}""" else ""
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(if (isToken) 200 else status).message("test response").body(body.toResponseBody()).build()
            } } }
        }
        Musixmatch.clientForTests = client
        Musixmatch.resetTokenForTests()
        try { block() } finally { Musixmatch.clientForTests = null; Musixmatch.resetTokenForTests(); client.close() }
    }
}

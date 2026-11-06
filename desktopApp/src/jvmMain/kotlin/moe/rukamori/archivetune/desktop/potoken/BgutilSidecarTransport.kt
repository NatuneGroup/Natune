package moe.rukamori.archivetune.desktop.potoken

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The two loopback endpoints of the helper, kept behind an interface so lifecycle and cache tests need no socket. */
internal interface BgutilSidecarTransport {
    suspend fun ping(baseUrl: String): Boolean

    suspend fun fetchPot(baseUrl: String, contentBinding: String): String?
}

/**
 * The real loopback transport. Every request carries its own timeout, so a hung helper can never hold a resolve
 * beyond the caller's tier, and a non-2xx answer is an honest null rather than a made-up token.
 */
internal class HttpBgutilSidecarTransport(
    connectTimeoutMs: Long = 1_500L,
    private val pingTimeoutMs: Long = 2_000L,
    private val potTimeoutMs: Long = 12_000L,
) : BgutilSidecarTransport {
    private val client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override suspend fun ping(baseUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    HttpRequest.newBuilder(URI.create("$baseUrl/ping"))
                        .timeout(Duration.ofMillis(pingTimeoutMs))
                        .GET()
                        .build()
                client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }

    override suspend fun fetchPot(baseUrl: String, contentBinding: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val body =
                    buildJsonObject {
                        put("content_binding", contentBinding)
                    }.toString()
                val request =
                    HttpRequest.newBuilder(URI.create("$baseUrl/get_pot"))
                        .timeout(Duration.ofMillis(potTimeoutMs))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) return@withContext null
                parsePotToken(response.body())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
}

/** Reads the `poToken` field of a `/get_pot` answer; anything else (HTML, an error object, a blank value) is null. */
internal fun parsePotToken(body: String): String? =
    runCatching {
        Json.parseToJsonElement(body).jsonObject["poToken"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
    }.getOrNull()

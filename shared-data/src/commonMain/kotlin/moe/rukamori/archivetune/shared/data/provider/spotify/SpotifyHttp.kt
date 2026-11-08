package moe.rukamori.archivetune.shared.data.provider.spotify

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import moe.rukamori.archivetune.shared.data.provider.ProviderRequestTimeout

class SpotifyProtocolFailure(val status: Int? = null) : IllegalStateException("SPOTIFY_PROTOCOL_FAILURE")

class SpotifyReply(val status: Int, val bytes: ByteArray, val location: String?, val cookies: List<String>) {
    fun json(): JsonObject = try {
        Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: throw SpotifyProtocolFailure(status)
    } catch (_: IllegalArgumentException) {
        throw SpotifyProtocolFailure(status)
    }
}

/** Bounded requests with no automatic redirects and no credential-bearing URL/body in a failure. */
class SpotifyHttp(client: HttpClient) {
    private val http = client.config { followRedirects = false; expectSuccess = false }

    suspend fun exchange(
        url: String,
        method: HttpMethod = HttpMethod.Get,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): SpotifyReply = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
        try {
            val uri = Url(url)
            require(uri.protocol.name == "https" && uri.host.isNotBlank() &&
                uri.user.isNullOrEmpty() && uri.password.isNullOrEmpty())
            http.prepareRequest(url) {
                this.method = method
                headers.forEach { (name, value) -> header(name, value) }
                body?.let { setBody(it) }
            }.execute { response ->
                val channel = response.bodyAsChannel()
                val out = SpotifyByteOutput()
                val chunk = ByteArray(8192)
                try {
                    while (true) {
                        val count = channel.readAvailable(chunk, 0, chunk.size)
                        if (count < 0) break
                        if (count == 0) { yield(); continue }
                        if (out.size() > MAX_BODY_BYTES - count) throw SpotifyProtocolFailure()
                        out.write(chunk.copyOf(count))
                    }
                    SpotifyReply(response.status.value, out.toByteArray(),
                        response.headers["Location"], response.headers.getAll("Set-Cookie").orEmpty())
                } finally {
                    channel.cancel(null)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SpotifyProtocolFailure) {
            throw failure
        } catch (_: Exception) {
            throw SpotifyProtocolFailure()
        }
    } ?: throw ProviderRequestTimeout()

    fun close() = http.close()

    companion object {
        const val REQUEST_TIMEOUT_MS = 12_000L
        const val MAX_BODY_BYTES = 2 * 1024 * 1024

        fun url(base: String, parameters: Map<String, String>): String =
            URLBuilder(base).apply { parameters.forEach { (name, value) -> this.parameters.append(name, value) } }.buildString()
    }
}

fun JsonObject.spotifyText(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

fun JsonObject.spotifyObject(name: String): JsonObject? = this[name] as? JsonObject

/** Profile lookup used only with the caller's account token. */
suspend fun spotifyProfileId(client: HttpClient, bearer: String): String? {
    val http = SpotifyHttp(client)
    return try {
        val reply = http.exchange("https://api.spotify.com/v1/me", headers = mapOf("Authorization" to "Bearer $bearer"))
        if (reply.status in 200..299) reply.json().spotifyText("id") else null
    } finally { http.close() }
}

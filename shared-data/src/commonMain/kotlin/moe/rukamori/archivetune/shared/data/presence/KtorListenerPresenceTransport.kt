package moe.rukamori.archivetune.shared.data.presence

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceTransport

/**
 * Posts listener-presence payloads to the configured service. A blank endpoint or one that is not HTTPS leaves
 * [available] false, and nothing is sent. The client must carry no cookies and no auth header. Failures are returned
 * and never retried: the next playback tick sends a fresh payload. HTTP 429 is dropped the same way.
 */
class KtorListenerPresenceTransport(
    private val client: HttpClient,
    apiUrl: String,
) : ListenerPresenceTransport {
    private val origin = apiUrl.trim().trimEnd('/')

    override val available: Boolean = origin.startsWith(HTTPS_PREFIX) && origin.length > HTTPS_PREFIX.length

    override suspend fun send(payloadJson: String): Result<Unit> {
        if (!available) return Result.failure(IllegalStateException("Listener presence is not configured"))
        return try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) { post(payloadJson) }
                ?: Result.failure(IllegalStateException("Listener presence timed out"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private suspend fun post(payloadJson: String): Result<Unit> {
        val response: HttpResponse =
            client.post(origin + HEARTBEAT_PATH) {
                contentType(ContentType.Application.Json)
                setBody(payloadJson)
            }
        val code = response.status.value
        return when {
            response.status.isSuccess() -> Result.success(Unit)
            code == 429 -> Result.failure(IllegalStateException("Listener presence rate limited"))
            else -> Result.failure(IllegalStateException("Listener presence rejected ($code)"))
        }
    }

    private companion object {
        const val HTTPS_PREFIX = "https://"
        const val HEARTBEAT_PATH = "/api/heartbeat"
        const val REQUEST_TIMEOUT_MS = 10_000L
    }
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.feedback

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.rukamori.archivetune.shared.domain.feedback.FeedbackTransport

/**
 * Transport for the opt-in feedback service, ported from the Android `FeedbackApi`. A blank endpoint or ingest key
 * leaves [available] false and nothing is sent. The ingest key is public by nature: the server lets it create
 * reports and never read, list or delete them.
 *
 * Status handling matches the Android source: 429 is rate limiting, other 4xx surface the server's `error` text,
 * and anything else is reported as the service being unavailable. Failures are returned, never retried.
 */
class KtorFeedbackTransport(
    private val client: HttpClient,
    apiUrl: String,
    ingestKey: String,
) : FeedbackTransport {
    private val endpoint = apiUrl.trim().trimEnd('/') + PATH
    private val key = ingestKey.trim()

    override val available: Boolean = apiUrl.isNotBlank() && ingestKey.isNotBlank()

    override suspend fun send(envelopeJson: String): Result<Unit> {
        if (!available) return Result.failure(IllegalStateException("Feedback service not configured"))
        return try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) { post(envelopeJson) }
                ?: Result.failure(IllegalStateException("Feedback service timed out"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private suspend fun post(envelopeJson: String): Result<Unit> {
        val response: HttpResponse =
            client.post(endpoint) {
                header("X-Ingest-Key", key)
                contentType(ContentType.Application.Json)
                setBody(envelopeJson)
            }
        val code = response.status.value
        return when {
            response.status.isSuccess() -> Result.success(Unit)
            code == 429 -> Result.failure(FeedbackRejection("Rate limited, try again later"))
            code in 400..499 ->
                Result.failure(
                    FeedbackRejection(serverError(response.bodyTextOrNull()) ?: "Report rejected ($code)"),
                )
            else -> Result.failure(FeedbackRejection("Service unavailable ($code)"))
        }
    }

    /** The server's `error` string for a rejected request; the raw body is never shown. */
    private fun serverError(body: String?): String? =
        runCatching { Json.parseToJsonElement(body.orEmpty()).jsonObject["error"]?.jsonPrimitive?.content }
            .getOrNull()
            ?.takeIf(String::isNotBlank)

    private suspend fun HttpResponse.bodyTextOrNull(): String? =
        try {
            bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Exception) {
            null
        }

    /** The server accepted the transport but refused the payload or key; the review shows this text verbatim. */
    class FeedbackRejection(message: String) : Exception(message)

    private companion object {
        const val PATH = "/api/report"
        const val REQUEST_TIMEOUT_MS = 60_000L
    }
}

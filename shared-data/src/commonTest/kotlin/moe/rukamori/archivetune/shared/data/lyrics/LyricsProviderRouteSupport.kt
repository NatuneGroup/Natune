package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/** A canned answer for one route. Routes absent from the map answer 404 with an empty body. */
internal class CannedReply(
    val body: String,
    val status: HttpStatusCode = HttpStatusCode.OK,
    val contentType: String = "application/json",
)

/** Wraps [text] as a JSON string literal, so an XML document can sit inside a JSON envelope. */
internal fun jsonStringLiteral(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** Records every request and answers from [routes], keyed by [routeOf] (the request path by default). */
internal fun routedClient(
    seen: MutableList<HttpRequestData>,
    routes: Map<String, CannedReply>,
    routeOf: (HttpRequestData) -> String = { it.url.encodedPath },
): HttpClient =
    HttpClient(
        MockEngine { request ->
            seen += request
            val reply = routes[routeOf(request)] ?: CannedReply(body = "", status = HttpStatusCode.NotFound)
            respond(
                content = reply.body,
                status = reply.status,
                headers = headersOf(HttpHeaders.ContentType, reply.contentType),
            )
        },
    )

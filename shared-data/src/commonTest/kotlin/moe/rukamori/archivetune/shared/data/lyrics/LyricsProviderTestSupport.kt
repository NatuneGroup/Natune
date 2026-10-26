package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal fun MockRequestHandleScope.jsonReply(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = body,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

@OptIn(ExperimentalEncodingApi::class)
internal fun base64Of(text: String): String = Base64.Default.encode(text.encodeToByteArray())

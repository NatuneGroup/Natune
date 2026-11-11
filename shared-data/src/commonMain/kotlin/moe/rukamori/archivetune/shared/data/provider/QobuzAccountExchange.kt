package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import moe.rukamori.archivetune.shared.data.bytesource.Md5
import moe.rukamori.archivetune.shared.domain.settings.QobuzTokenEntry

/** One explicit sign-in. Only the returned token/app pair is saved; no password-backed refresh. */
class QobuzAccountExchange(client: HttpClient,
    apiBase: String = "https://www.qobuz.com/api.json/0.2") {
    private val http = ProviderHttp(client)
    private val api = endpoint(apiBase)

    suspend fun signIn(email: String, password: String, appId: String, appSecret: String): QobuzTokenEntry? {
        require(email.isNotBlank() && email.length <= 320 && password.isNotEmpty() && password.length <= 4096)
        require(appId.isNotBlank() && appId.length <= 64 && appId.all { it in '0'..'9' })
        require(Regex("^[a-f0-9]{32}$").matches(appSecret))
        val reply = http.exchange("$api/user/login", params = mapOf(
            "app_id" to appId, "email" to email.trim(),
            "password" to Md5.hexDigest(password.encodeToByteArray()),
        ), headers = mapOf("X-App-Id" to appId))
        if (reply.status == 400 || reply.status == 401) return null
        if (reply.status !in 200..299) throw ProviderHttpFailure(reply.status)
        val root = try { Json.parseToJsonElement(reply.body).jsonObject }
            catch (_: Exception) { throw ProviderProtocolFailure() }
        val token = (root["user_auth_token"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: throw ProviderProtocolFailure()
        return QobuzTokenEntry(token = token, userId = root.obj("user")?.text("id").orEmpty(),
            appId = appId, appSecret = appSecret, label = email.trim())
    }
}

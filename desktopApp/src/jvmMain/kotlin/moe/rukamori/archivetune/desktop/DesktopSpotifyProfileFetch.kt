package moe.rukamori.archivetune.desktop

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import moe.rukamori.archivetune.spotify.SpotifyHashProvider

private const val GQL_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
private const val REST_ME_URL = "https://api.spotify.com/v1/me"
private const val WEB_PLAYER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/**
 * Reads the profile of the account behind [token]: the web player's GraphQL profile first, with the previous query hash
 * as a fallback, then the public REST profile. Null when neither answers.
 */
internal suspend fun fetchSpotifyProfile(http: HttpClient, token: String): SpotifyAccountProfile? =
    profileFromGraphQl(http, token) ?: profileFromRest(http, token)

private suspend fun profileFromGraphQl(http: HttpClient, token: String): SpotifyAccountProfile? {
    val primary = SpotifyHashProvider.getHash("profileAttributes")
    val hashes = listOfNotNull(primary, SpotifyHashProvider.getPreviousHash("profileAttributes")?.takeIf { it != primary })
    for (hash in hashes) {
        val body = buildJsonObject {
            put("variables", JsonObject(emptyMap()))
            put("operationName", "profileAttributes")
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", hash)
                }
            }
        }
        val text = spotifyRequest {
            http.post(GQL_URL) {
                header("Authorization", "Bearer $token")
                header("Accept", "application/json")
                header("app-platform", "WebPlayer")
                header("Origin", "https://open.spotify.com")
                header("Referer", "https://open.spotify.com/")
                header("User-Agent", WEB_PLAYER_USER_AGENT)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.takeIf { it.status.isSuccess() }?.bodyAsText()
        } ?: continue
        val profile = parseObject(text)?.child("data")?.child("me")?.child("profile") ?: continue
        val userId = profile.text("uri")?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: continue
        return SpotifyAccountProfile(userId, profile.text("name")?.takeIf { it.isNotBlank() })
    }
    return null
}

private suspend fun profileFromRest(http: HttpClient, token: String): SpotifyAccountProfile? {
    val text = spotifyRequest {
        http.get(REST_ME_URL) {
            header("Authorization", "Bearer $token")
            header("Accept", "application/json")
        }.takeIf { it.status.isSuccess() }?.bodyAsText()
    } ?: return null
    val json = parseObject(text) ?: return null
    val userId = json.text("id")?.takeIf { it.isNotBlank() } ?: return null
    return SpotifyAccountProfile(
        userId,
        json.text("display_name")?.takeIf { it.isNotBlank() },
        json.text("product")?.takeIf { it.isNotBlank() },
    )
}

private suspend fun spotifyRequest(block: suspend () -> String?): String? =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

private fun parseObject(text: String): JsonObject? =
    try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    }

private fun JsonObject.child(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

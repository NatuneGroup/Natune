package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.spotify.SpotifyHashProvider

/** A bounded, private profile read; the token is never installed in the catalog singleton. */
internal suspend fun desktopSpotifyProfileId(client: HttpClient, token: String): String? {
    val http = DesktopSpotifyHttp(client)
    return try {
        http.profileId(token)
    } finally {
        http.close()
    }
}

private suspend fun DesktopSpotifyHttp.profileId(token: String): String? {
    val primary = SpotifyHashProvider.getHash("profileAttributes")
    val hashes = listOfNotNull(primary, SpotifyHashProvider.getPreviousHash("profileAttributes")?.takeIf { it != primary })
    for (hash in hashes) {
        val body = buildJsonObject {
            put("operationName", "profileAttributes")
            put("variables", buildJsonObject {})
            put("extensions", buildJsonObject {
                put("persistedQuery", buildJsonObject { put("version", 1); put("sha256Hash", hash) })
            })
        }
        try {
            val reply = exchange("https://api-partner.spotify.com/pathfinder/v2/query", HttpMethod.Post,
                mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json",
                    "app-platform" to "WebPlayer", "Origin" to "https://open.spotify.com",
                    "Referer" to "https://open.spotify.com/"), body.toString().encodeToByteArray())
            if (reply.status in 200..299) {
                reply.json().spotifyObject("data")?.spotifyObject("me")?.spotifyObject("profile")
                    ?.spotifyText("uri")?.substringAfterLast(':')?.takeIf { it.isNotBlank() }?.let { return it }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }
    return try {
        val reply = exchange("https://api.spotify.com/v1/me",
            headers = mapOf("Authorization" to "Bearer $token"))
        if (reply.status in 200..299) reply.json().spotifyText("id") else null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

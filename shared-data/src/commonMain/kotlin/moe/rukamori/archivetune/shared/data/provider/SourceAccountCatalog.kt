package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.settings.QobuzTokenEntry

/** Resolves the real user id before a saved Qobuz credential can admit a personal byte stream. */
class KtorQobuzAccountIdentity(client: HttpClient) {
    private val http = ProviderHttp(client)

    suspend fun userId(entry: QobuzTokenEntry): String? {
        val root = http.json("https://www.qobuz.com/api.json/0.2/user/get",
            mapOf("app_id" to entry.appId, "user_auth_token" to entry.token),
            mapOf("X-App-Id" to entry.appId, "X-User-Auth-Token" to entry.token)).jsonObject
        val user = root.obj("user") ?: root.obj("data") ?: root
        return user.text("id")?.takeIf { it != "0" && (entry.userId.isBlank() || it == entry.userId) }
    }
}

/** Android's Amazon source chooser searches iTunes suggestions; their ids are never Amazon playback ids. */
class KtorAmazonSourceSuggestions(client: HttpClient) {
    private val http = ProviderHttp(client)

    suspend fun search(query: String, limit: Int = 8): List<ContentItem> {
        if (query.trim().length < 2 || limit <= 0) return emptyList()
        val root = http.json("https://itunes.apple.com/search", mapOf("term" to query.trim(),
            "media" to "music", "entity" to "song", "limit" to limit.coerceAtMost(50).toString())).jsonObject
        return root.array("results").orEmpty().mapNotNull { value ->
            val row = value as? JsonObject ?: return@mapNotNull null
            val id = row.text("trackId") ?: return@mapNotNull null
            val title = row.text("trackName") ?: return@mapNotNull null
            val artist = row.text("artistName") ?: return@mapNotNull null
            ContentItem(ContentRef(ContentProvider.AMAZON.id, ContentKind.SONG, id), title,
                subtitle = artist,
                artworkUri = row.text("artworkUrl100")?.replace("/100x100bb.jpg", "/200x200bb.jpg"),
                durationMs = row.long("trackTimeMillis")?.takeIf { it > 0 },
                album = row.text("collectionName"), artists = listOf(ContentCredit(artist)))
        }.distinctBy { it.ref }.take(limit)
    }
}

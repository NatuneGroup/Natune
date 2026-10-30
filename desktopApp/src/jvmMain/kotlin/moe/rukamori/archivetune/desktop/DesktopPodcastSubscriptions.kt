package moe.rukamori.archivetune.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

internal interface DesktopPodcastSubscriptions {
    suspend fun read(): List<ContentItem>
    suspend fun write(shows: List<ContentItem>)
}

/** Device-local follows, explicitly labelled as such by the mounted shared podcast pages. */
internal class FileDesktopPodcastSubscriptions(private val file: Path) : DesktopPodcastSubscriptions {
    override suspend fun read(): List<ContentItem> = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext emptyList()
        require(Files.size(file) <= MAX_FILE_BYTES) { "Podcast subscriptions file is too large" }
        decodePodcastSubscriptions(Files.readString(file))
    }

    override suspend fun write(shows: List<ContentItem>) = withContext(Dispatchers.IO) {
        require(shows.size <= MAX_PODCAST_SUBSCRIPTIONS)
        val bytes = encodePodcastSubscriptions(shows).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES)
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, ".podcasts-", ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        Unit
    }

    private companion object {
        const val MAX_FILE_BYTES = 1024 * 1024L
    }
}

internal const val MAX_PODCAST_SUBSCRIPTIONS = 256

internal fun encodePodcastSubscriptions(shows: List<ContentItem>): String = buildJsonArray {
    shows.distinctBy { it.ref }.take(MAX_PODCAST_SUBSCRIPTIONS).forEach { show ->
        require(show.ref.provider == ContentProvider.YOUTUBE.id && show.ref.kind == ContentKind.PODCAST)
        add(buildJsonObject {
            put("id", show.ref.id)
            put("title", show.title)
            put("author", show.subtitle)
            show.artworkUri?.let { put("artwork", it) }
        })
    }
}.toString()

internal fun decodePodcastSubscriptions(raw: String): List<ContentItem> {
    val rows = Json.parseToJsonElement(raw) as? JsonArray ?: error("Invalid podcast subscriptions")
    require(rows.size <= MAX_PODCAST_SUBSCRIPTIONS)
    return rows.map { element ->
        val row = element as? JsonObject ?: error("Invalid podcast subscription")
        fun value(name: String) = (row[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = value("id")?.takeIf(String::isNotBlank) ?: error("Missing podcast id")
        val title = value("title")?.takeIf(String::isNotBlank) ?: error("Missing podcast title")
        ContentItem(
            ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PODCAST, id),
            title,
            subtitle = value("author").orEmpty(),
            artworkUri = value("artwork"),
        )
    }.distinctBy { it.ref }
}

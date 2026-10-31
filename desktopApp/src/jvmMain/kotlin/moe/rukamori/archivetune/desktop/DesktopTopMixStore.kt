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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.shared.data.provider.PERSONAL_MIX_LIMIT
import moe.rukamori.archivetune.shared.data.provider.PERSONAL_MIX_SONG_LIMIT
import moe.rukamori.archivetune.shared.domain.repository.ContentItem

internal data class DesktopSavedMix(
    val id: String,
    val title: String,
    val description: String,
    val songs: List<ContentItem>,
)
internal data class DesktopMixRecord(val id: String, val title: String, val description: String, val songIds: List<String>)
internal data class DesktopMixManifest(val generatedAt: Long, val mixes: List<DesktopMixRecord>)

internal class DesktopTopMixStore(private val file: Path) {
    suspend fun read(): DesktopMixManifest = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext DesktopMixManifest(0L, emptyList())
        require(Files.size(file) <= MAX_BYTES)
        decodeDesktopMixManifest(Files.readString(file))
    }

    suspend fun write(manifest: DesktopMixManifest) = withContext(Dispatchers.IO) {
        val bytes = encodeDesktopMixManifest(manifest).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, ".top-mixes-", ".tmp")
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

    private companion object { const val MAX_BYTES = 1024 * 1024L }
}

internal fun encodeDesktopMixManifest(manifest: DesktopMixManifest): String {
    require(manifest.generatedAt >= 0 && manifest.mixes.size <= PERSONAL_MIX_LIMIT)
    return buildJsonObject {
        put("generatedAt", manifest.generatedAt)
        put("mixes", buildJsonArray {
            manifest.mixes.forEach { mix ->
                require(mix.id.isNotBlank() && mix.title.isNotBlank())
                require(mix.songIds.isNotEmpty() && mix.songIds.size <= PERSONAL_MIX_SONG_LIMIT)
                add(buildJsonObject {
                    put("id", mix.id); put("title", mix.title); put("description", mix.description)
                    put("songs", JsonArray(mix.songIds.distinct().map { JsonPrimitive(it) }))
                })
            }
        })
    }.toString()
}

internal fun decodeDesktopMixManifest(raw: String): DesktopMixManifest {
    val root = Json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid mix manifest")
    val time = (root["generatedAt"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
        ?: error("Invalid generation time")
    val rows = root["mixes"] as? JsonArray ?: error("Missing mixes")
    require(rows.size <= PERSONAL_MIX_LIMIT)
    val mixes = rows.map { element ->
        val row = element as? JsonObject ?: error("Invalid mix")
        fun value(name: String) = (row[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = value("id")?.takeIf(String::isNotBlank) ?: error("Missing mix id")
        val title = value("title")?.takeIf(String::isNotBlank) ?: error("Missing mix title")
        val songs = row["songs"] as? JsonArray ?: error("Missing mix songs")
        require(songs.isNotEmpty() && songs.size <= PERSONAL_MIX_SONG_LIMIT)
        DesktopMixRecord(id, title, value("description").orEmpty(), songs.map {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.takeIf(String::isNotBlank)
                ?: error("Invalid mix song")
        }.distinct())
    }
    require(mixes.distinctBy { it.id }.size == mixes.size)
    return DesktopMixManifest(time, mixes)
}

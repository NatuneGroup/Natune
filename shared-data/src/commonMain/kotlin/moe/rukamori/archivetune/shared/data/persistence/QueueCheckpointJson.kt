package moe.rukamori.archivetune.shared.data.persistence

import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.persistence.QueueCheckpoint
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistPhase
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.*

/**
 * Explicit versioning and strict decode: corrupt or unknown files are never reset or silently truncated.
 * Shuffle, manual kinds and the paging tail are optional keys inside version 1. A checkpoint written before
 * they existed has none of them and decodes to the defaults that queue always had.
 */
object QueueCheckpointJson {
    fun encode(checkpoint: QueueCheckpoint): String = buildJsonObject {
        val queue = checkpoint.queue
        put("version", 1)
        put("revision", queue.revision)
        put("positionMs", checkpoint.positionMs)
        put("current", queue.currentOccurrenceId?.let(::JsonPrimitive) ?: JsonNull)
        put("phase", queue.phase.name)
        put("order", JsonArray(queue.playOrder.map(::JsonPrimitive)))
        put("entries", JsonArray(queue.entries.map(ContentJson::encode)))
        put("shuffleEnabled", queue.shuffleEnabled)
        put("manualKinds", buildJsonObject {
            queue.manualKinds.forEach { (occurrenceId, kind) -> put(occurrenceId, kind.name) }
        })
        put("tail", queue.tail?.let { encodeTail(it) } ?: JsonNull)
    }.toString()

    fun decode(raw: String): QueueCheckpoint {
        require(raw.length <= 16_000_000) { "Queue checkpoint exceeds size limit" }
        val root = Json.parseToJsonElement(raw).jsonObject
        require(root.getValue("version").jsonPrimitive.int == 1) { "Unsupported queue checkpoint version" }
        val entries = root.getValue("entries").jsonArray
        require(entries.size <= 100_000) { "Queue checkpoint exceeds entry limit" }
        val queue = QueueState(
            revision = root.getValue("revision").jsonPrimitive.long,
            entries = entries.map { ContentJson.decode(it.jsonObject) },
            currentOccurrenceId = root.nullableString("current"),
            playOrder = root.getValue("order").jsonArray.map { it.stringValue() },
            phase = FinitePlaylistPhase.valueOf(root.string("phase")),
            shuffleEnabled = root.optionalBoolean("shuffleEnabled"),
            manualKinds = root.manualKinds(),
            tail = root.optionalObject("tail")?.let { decodeTail(it) },
        )
        return QueueCheckpoint(queue, root.getValue("positionMs").jsonPrimitive.long)
    }

    /** A paging tail keeps the exact request that issued its continuation, so the token never meets another query. */
    private fun encodeTail(tail: QueueTail): JsonObject = buildJsonObject {
        put("collection", encodeRequest(tail.collection))
        put("continuation", tail.continuation?.let(::JsonPrimitive) ?: JsonNull)
        put("consumedTokens", JsonArray(tail.consumedTokens.sorted().map(::JsonPrimitive)))
        put("kind", tail.kind.name)
    }

    private fun decodeTail(entry: JsonObject): QueueTail = QueueTail(
        collection = decodeRequest(entry.getValue("collection").jsonObject),
        continuation = entry.nullableString("continuation"),
        consumedTokens = entry.getValue("consumedTokens").jsonArray.map { it.stringValue() }.toSet(),
        kind = QueueCollectionKind.valueOf(entry.string("kind")),
    )

    private fun encodeRequest(request: ContentRequest): JsonObject = buildJsonObject {
        put("provider", request.provider.id)
        put("surface", request.surface.id)
        put("text", request.text?.let(::JsonPrimitive) ?: JsonNull)
        put("parent", request.parent?.let { encodeRef(it) } ?: JsonNull)
        put("section", request.section?.let { section ->
            buildJsonObject {
                put("id", section.id)
                put("params", section.params?.let(::JsonPrimitive) ?: JsonNull)
            }
        } ?: JsonNull)
        put("filter", request.filter.name)
    }

    private fun decodeRequest(entry: JsonObject): ContentRequest = ContentRequest(
        provider = ContentProvider.fromId(entry.string("provider")),
        surface = ContentSurface.fromId(entry.string("surface")),
        text = entry.nullableString("text"),
        parent = entry.optionalObject("parent")?.let { decodeRef(it) },
        section = entry.optionalObject("section")?.let { section ->
            ContentSection(section.string("id"), section.nullableString("params"))
        },
        filter = ContentSearchFilter.valueOf(entry.string("filter")),
    )

    private fun encodeRef(ref: ContentRef): JsonObject = buildJsonObject {
        put("provider", ref.provider)
        put("kind", ref.kind.name)
        put("id", ref.id)
    }

    private fun decodeRef(entry: JsonObject): ContentRef =
        ContentRef(entry.string("provider"), ContentKind.valueOf(entry.string("kind")), entry.string("id"))

    private fun JsonElement.stringValue(): String = jsonPrimitive.let {
        require(it.isString)
        it.content
    }

    private fun JsonObject.string(name: String): String = getValue(name).stringValue()

    private fun JsonObject.nullableString(name: String): String? =
        getValue(name).takeUnless { it is JsonNull }?.stringValue()

    private fun JsonObject.optionalObject(name: String): JsonObject? =
        this[name]?.takeUnless { it is JsonNull }?.jsonObject

    /** Absent means false, which is what every checkpoint written before shuffle support meant. */
    private fun JsonObject.optionalBoolean(name: String): Boolean =
        this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.let { primitive ->
            require(!primitive.isString)
            primitive.boolean
        } ?: false

    /** Absent means no manual songs. Each present value must be a known [UserQueuedKind] name. */
    private fun JsonObject.manualKinds(): Map<String, UserQueuedKind> =
        this["manualKinds"]?.jsonObject?.mapValues { (_, value) -> UserQueuedKind.valueOf(value.stringValue()) }
            ?: emptyMap()
}

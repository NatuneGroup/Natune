package moe.rukamori.archivetune.shared.data.persistence

import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.songmetadata.canonicalSong

internal object ContentJson {
    fun encode(occurrence: ContentOccurrence): JsonObject = buildJsonObject {
        put("occurrence", occurrence.occurrenceId)
        put("setVideoId", occurrence.setVideoId?.let(::JsonPrimitive) ?: JsonNull)
        val item = occurrence.item.canonicalSong()
        put("provider", item.ref.provider); put("kind", item.ref.kind.name); put("id", item.ref.id)
        put("title", item.title); put("subtitle", item.subtitle); put("explicit", item.explicit)
        put("artwork", item.artworkUri?.let(::JsonPrimitive) ?: JsonNull)
        put("durationMs", item.durationMs?.let(::JsonPrimitive) ?: JsonNull)
        put("album", item.album?.let(::JsonPrimitive) ?: JsonNull)
        put("artists", JsonArray(item.artists.map { credit ->
            buildJsonObject { put("name", credit.name); put("id", credit.id?.let(::JsonPrimitive) ?: JsonNull) }
        }))
        put("albumId", item.albumId?.let(::JsonPrimitive) ?: JsonNull)
    }
    /**
     * `album`, the artist credits and `albumId` are optional, so a document written before they existed still decodes
     * with those fields unknown.
     */
    fun decode(entry: JsonObject): ContentOccurrence = ContentOccurrence(entry.string("occurrence"),
        ContentItem(ContentRef(entry.string("provider"), ContentKind.valueOf(entry.string("kind")), entry.string("id")),
            entry.string("title"), entry.string("subtitle"), entry.nullableString("artwork"),
            entry["durationMs"]?.jsonPrimitive?.longOrNull, entry.getValue("explicit").jsonPrimitive.boolean,
            entry.optionalString("album"),
            artists = entry.optionalArray("artists")?.map { credit ->
                ContentCredit(credit.jsonObject.string("name"), credit.jsonObject.optionalString("id"))
            }.orEmpty(),
            albumId = entry.optionalString("albumId")),
        entry.nullableString("setVideoId"))

    private fun JsonObject.optionalArray(name: String): JsonArray? =
        this[name]?.takeUnless { it is JsonNull }?.jsonArray
    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.let { require(it.isString); it.content }
    private fun JsonObject.nullableString(name: String): String? =
        getValue(name).takeUnless { it is JsonNull }?.jsonPrimitive?.let { require(it.isString); it.content }
    private fun JsonObject.optionalString(name: String): String? =
        this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.let { require(it.isString); it.content }
}

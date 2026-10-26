/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.settings.*

/** Private Keychain payload, not a backup format or another key registry. Kind AND name are identity. */
internal object IosPreferencePayload {
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_ENTRIES = 10_000
    private const val MAX_STRING_BYTES = 1024 * 1024

    fun encode(snapshot: PreferenceSnapshot): ByteArray {
        require(snapshot.keys.size <= MAX_ENTRIES)
        var budget = 0L
        fun string(value: String): JsonPrimitive {
            val size = value.encodeToByteArray().size
            require(size <= MAX_STRING_BYTES)
            budget += size
            require(budget <= MAX_BYTES)
            return JsonPrimitive(value)
        }
        val rows = snapshot.keys.sortedWith(compareBy({ it.name }, { it.kind.name })).map { key ->
            buildJsonObject {
                put("name", string(key.name))
                put("kind", key.kind.name)
                put("value", when (key) {
                    is BooleanPreferenceKey -> JsonPrimitive(requireNotNull(snapshot[key]))
                    is StringPreferenceKey -> string(requireNotNull(snapshot[key]))
                    is IntPreferenceKey -> JsonPrimitive(requireNotNull(snapshot[key]).toString())
                    is LongPreferenceKey -> JsonPrimitive(requireNotNull(snapshot[key]).toString())
                    is FloatPreferenceKey -> JsonPrimitive(requireNotNull(snapshot[key]).toRawBits().toString())
                    is DoublePreferenceKey -> JsonPrimitive(requireNotNull(snapshot[key]).toRawBits().toString())
                    is StringSetPreferenceKey -> {
                        val values = requireNotNull(snapshot[key])
                        require(values.size <= MAX_ENTRIES)
                        JsonArray(values.sorted().map(::string))
                    }
                })
            }
        }
        return buildJsonObject {
            put("version", 1)
            put("entries", JsonArray(rows))
        }.toString().encodeToByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): PreferenceSnapshot {
        require(bytes.size <= MAX_BYTES)
        val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        require(root.keys == setOf("version", "entries") && root.getValue("version").jsonPrimitive.int == 1)
        val rows = root.getValue("entries").jsonArray
        require(rows.size <= MAX_ENTRIES)
        fun JsonElement.text(): String = jsonPrimitive.let {
            require(it.isString && it.content.encodeToByteArray().size <= MAX_STRING_BYTES)
            it.content
        }
        val entries = rows.map { row ->
            val entry = row.jsonObject
            require(entry.keys == setOf("name", "kind", "value"))
            val name = entry.getValue("name").text()
            val value = entry.getValue("value")
            val pair: Pair<PreferenceKey<*>, Any> = when (entry.getValue("kind").text()) {
                "BOOLEAN" -> BooleanPreferenceKey(name) to value.jsonPrimitive.let {
                    require(!it.isString); requireNotNull(it.booleanOrNull)
                }
                "STRING" -> StringPreferenceKey(name) to value.text()
                "INT" -> IntPreferenceKey(name) to value.text().toInt()
                "LONG" -> LongPreferenceKey(name) to value.text().toLong()
                "FLOAT" -> FloatPreferenceKey(name) to Float.fromBits(value.text().toInt())
                "DOUBLE" -> DoublePreferenceKey(name) to Double.fromBits(value.text().toLong())
                "STRING_SET" -> StringSetPreferenceKey(name) to value.jsonArray.let { values ->
                    require(values.size <= MAX_ENTRIES)
                    values.map { it.text() }.also { require(it.distinct().size == it.size) }.toSet()
                }
                else -> error("Unsupported iOS preference kind")
            }
            pair
        }
        require(entries.map { it.first }.distinct().size == entries.size)
        return PreferenceSnapshot.of(*entries.toTypedArray())
    }
}

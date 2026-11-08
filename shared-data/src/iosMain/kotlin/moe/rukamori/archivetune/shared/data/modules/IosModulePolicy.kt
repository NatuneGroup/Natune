package moe.rukamori.archivetune.shared.data.modules

import kotlinx.serialization.json.*

/** Codes carry no provider exception, script source, URL or credential text into the UI. */
enum class IosModuleIssue {
    INVALID_PACKAGE, UNSUPPORTED_CONTRACT, INVALID_CONFIGURATION, EXECUTION_FAILED, UNSUPPORTED_STREAM,
}

class IosModuleFailure(val issue: IosModuleIssue) : IllegalArgumentException(issue.name)

internal fun moduleRequire(condition: Boolean, issue: IosModuleIssue = IosModuleIssue.INVALID_PACKAGE) {
    if (!condition) throw IosModuleFailure(issue)
}

internal val iosModuleFeatures = setOf("fetch@1", "settings@1", "streamResolution@1", "packagedIcon@1", "qualityOptions@1")
internal val iosAudioCapabilities = setOf("searchTracks", "getTrackStreamUrl")
private val booleanTypes = setOf("boolean", "switch", "toggle", "checkbox")
private val selectTypes = setOf("select", "selector", "dropdown")
private val textTypes = setOf("text", "string", "url", "password")

internal fun validateModuleMetadata(metadata: JsonObject): JsonObject {
    for ((key, limit) in listOf("id" to 128, "name" to 256, "version" to 128)) {
        val value = metadata[key] as? JsonPrimitive
        moduleRequire(value?.isString == true && value.content.isNotBlank() && value.content.length <= limit)
    }
    moduleRequire(metadata.getValue("id").jsonPrimitive.content.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}")))
    val unsupported = listOf("signedSession", "permissions", "serviceHealth", "searchBehavior", "urlHandler")
    moduleRequire(unsupported.none { it in metadata } && metadata["authentication"]?.jsonPrimitive?.booleanOrNull != true,
        IosModuleIssue.UNSUPPORTED_CONTRACT)
    val types = when (val raw = metadata["type"]) {
        null -> emptyList()
        is JsonPrimitive -> listOf(raw.content)
        is JsonArray -> raw.map { it.jsonPrimitive.content }
        else -> throw IosModuleFailure(IosModuleIssue.INVALID_PACKAGE)
    }
    moduleRequire(types.all { it == "audio_provider" }, IosModuleIssue.UNSUPPORTED_CONTRACT)
    val capabilities = (metadata["capabilities"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
    moduleRequire(capabilities.toSet() == iosAudioCapabilities && capabilities.size == 2, IosModuleIssue.UNSUPPORTED_CONTRACT)
    val features = when (val raw = metadata["requiredRuntimeFeatures"]) {
        null -> emptyList()
        is JsonPrimitive -> { moduleRequire(raw.isString); listOf(raw.content) }
        is JsonArray -> raw.map { value ->
            moduleRequire(value is JsonPrimitive && value.isString)
            value.jsonPrimitive.content
        }
        else -> throw IosModuleFailure(IosModuleIssue.INVALID_PACKAGE)
    }
    moduleRequire(features.size <= 32 && features.all { it in iosModuleFeatures }, IosModuleIssue.UNSUPPORTED_CONTRACT)
    val version = metadata["minAppVersion"]?.jsonPrimitive?.content.orEmpty()
    if (version.isNotBlank()) {
        moduleRequire(version.matches(Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")))
        val order = version.split('.').map(String::toInt).zip(listOf(15, 1, 0))
            .firstOrNull { it.first != it.second }
        moduleRequire(order == null || order.first < order.second, IosModuleIssue.UNSUPPORTED_CONTRACT)
    }
    val fields = when (val raw = metadata["settings"]) {
        null -> JsonObject(emptyMap())
        is JsonObject -> raw
        is JsonArray -> JsonObject(raw.associate { value ->
            val field = value.jsonObject
            field.getValue("key").jsonPrimitive.content to JsonObject(field - "key")
        }.also { moduleRequire(it.size == raw.size) })
        else -> throw IosModuleFailure(IosModuleIssue.INVALID_PACKAGE)
    }.toMutableMap()
    (metadata["qualityOptions"] as? JsonArray)?.let { options ->
        moduleRequire("quality" !in fields && options.isNotEmpty())
        fields["quality"] = buildJsonObject {
            put("type", "select"); put("label", metadata["qualityLabel"] ?: JsonPrimitive("Quality"))
            put("options", options); put("defaultValue", metadata["defaultQuality"] ?: optionValue(options.first()))
        }
    }
    moduleRequire(fields.size <= 128)
    for ((key, raw) in fields) {
        moduleRequire(key.isNotBlank() && key.length <= 128 && raw is JsonObject)
        val field = raw.jsonObject
        val type = field["type"]?.jsonPrimitive?.content ?: "text"
        moduleRequire(type in booleanTypes + selectTypes + textTypes + setOf("number", "integer"), IosModuleIssue.UNSUPPORTED_CONTRACT)
        moduleRequire((field["label"]?.jsonPrimitive?.content?.length ?: 0) <= 256)
        val options = (field["options"] as? JsonArray).orEmpty()
        moduleRequire(options.size <= 256 && (type !in selectTypes || options.isNotEmpty()))
        for (flag in listOf("secret", "required")) moduleRequire(flag !in field || field[flag]?.jsonPrimitive?.booleanOrNull != null)
        val min = field["min"]?.jsonPrimitive?.doubleOrNull
        val max = field["max"]?.jsonPrimitive?.doubleOrNull
        moduleRequire(("min" !in field || min?.isFinite() == true) && ("max" !in field || max?.isFinite() == true))
        moduleRequire(min == null || max == null || min <= max)
        moduleRequire(moduleSettingAccepts(field, field["defaultValue"] ?: field["value"] ?: JsonNull, required = false))
    }
    return JsonObject(metadata + ("settings" to JsonObject(fields)))
}

internal fun optionValue(option: JsonElement): JsonElement = (option as? JsonObject)?.get("value") ?: option

internal fun moduleSettingAccepts(field: JsonObject, value: JsonElement, required: Boolean = true): Boolean {
    if (value == JsonNull) return !required || field["required"]?.jsonPrimitive?.booleanOrNull != true
    val primitive = value as? JsonPrimitive ?: return false
    val type = field["type"]?.jsonPrimitive?.content ?: "text"
    return when (type) {
        in booleanTypes -> !primitive.isString && primitive.booleanOrNull != null
        in selectTypes -> (field["options"] as? JsonArray).orEmpty().any { optionValue(it) == value }
        "number", "integer" -> {
            val number = primitive.doubleOrNull
            !primitive.isString && number != null && number.isFinite() &&
                (type != "integer" || number % 1.0 == 0.0) &&
                (field["min"]?.jsonPrimitive?.doubleOrNull?.let { number >= it } ?: true) &&
                (field["max"]?.jsonPrimitive?.doubleOrNull?.let { number <= it } ?: true)
        }
        in textTypes -> primitive.isString && primitive.content.length <= 8192 &&
            (!required || field["required"]?.jsonPrimitive?.booleanOrNull != true || primitive.content.isNotBlank())
        else -> false
    }
}

internal fun moduleSettings(metadata: JsonObject, values: JsonObject): JsonObject {
    val fields = metadata.getValue("settings").jsonObject
    moduleRequire(values.keys.all { it in fields }, IosModuleIssue.INVALID_CONFIGURATION)
    return JsonObject(fields.mapValues { (key, raw) ->
        val field = raw.jsonObject
        val value = values[key] ?: field["defaultValue"] ?: field["value"] ?: JsonNull
        moduleRequire(moduleSettingAccepts(field, value), IosModuleIssue.INVALID_CONFIGURATION)
        JsonObject(field + ("value" to value))
    })
}

/** API-v1 uses seconds; millisecond records are also accepted without weakening duration matching. */
internal fun moduleDurationMs(row: JsonObject): Long? {
    val millis = (row["durationMs"] as? JsonPrimitive)?.longOrNull
    if (millis != null && millis in 1..86_400_000L) return millis
    val seconds = (row["duration"] as? JsonPrimitive)?.doubleOrNull ?: return null
    return seconds.takeIf { it.isFinite() && it > 0.0 && it <= 86_400.0 }?.let { (it * 1000.0).toLong() }
}

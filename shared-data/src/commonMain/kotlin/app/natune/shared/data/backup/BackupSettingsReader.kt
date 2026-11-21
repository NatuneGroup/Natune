package app.natune.shared.data.backup

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import app.natune.shared.domain.backup.BackupArchiveException
import app.natune.shared.domain.backup.BackupArchiveProblem
import app.natune.shared.domain.settings.BooleanPreferenceKey
import app.natune.shared.domain.settings.DoublePreferenceKey
import app.natune.shared.domain.settings.FloatPreferenceKey
import app.natune.shared.domain.settings.IntPreferenceKey
import app.natune.shared.domain.settings.LongPreferenceKey
import app.natune.shared.domain.settings.PersistedPreferenceKeys
import app.natune.shared.domain.settings.PreferenceKey
import app.natune.shared.domain.settings.PreferenceKind
import app.natune.shared.domain.settings.PreferenceSnapshot
import app.natune.shared.domain.settings.SettingsKeyDictionary
import app.natune.shared.domain.settings.StringPreferenceKey
import app.natune.shared.domain.settings.StringSetPreferenceKey

/** What a restore will apply from a `settings.json`, and every entry it left out with the reason. */
data class ReadBackupSettings(
    val settings: PreferenceSnapshot,
    val skipped: List<SkippedSetting>,
)

data class SkippedSetting(val name: String, val reason: Reason) {
    enum class Reason { RETIRED, UNKNOWN_SETTING, WRONG_TYPE, UNREADABLE, DUPLICATE }
}

/**
 * Reads `settings.json` from any archive that uses the entries layout, keeping whatever it can:
 *
 * - Natune's own `{"name", "kind", "value"}` entries with typed values (floats and doubles as raw bits).
 * - Metrolist-style `{"key", "type", "value"}` entries whose values are all strings (`"true"`, `"0"`, `"0.5"`).
 *
 * Every name goes through [SettingsKeyDictionary], so renamed settings land on their current key and retired ones
 * are dropped. One damaged, duplicated or mistyped entry is skipped and reported; only a file that is not JSON with
 * an entries array is corrupted. [foreign] archives come from another app: their names mean something only where
 * this build stores the same name with the same kind, so everything else is skipped.
 */
object BackupSettingsReader {
    private const val MAX_ENTRIES = 10_000
    private const val MAX_NAME_LENGTH = 1024
    private val json = Json { ignoreUnknownKeys = true }
    private val knownKinds: Map<String, Set<PreferenceKind>> by lazy {
        PersistedPreferenceKeys.groupBy({ it.name }, { it.kind }).mapValues { it.value.toSet() }
    }

    fun read(bytes: ByteArray, foreign: Boolean): ReadBackupSettings {
        val entries = try {
            val root = json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true))
            when (root) {
                is JsonObject -> root["entries"] as JsonArray
                is JsonArray -> root
                else -> error("No entries")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw BackupArchiveException(BackupArchiveProblem.CORRUPTED)
        }
        if (entries.size > MAX_ENTRIES) throw BackupArchiveException(BackupArchiveProblem.CORRUPTED)

        val editor = PreferenceSnapshot.EMPTY.toEditor()
        val skipped = mutableListOf<SkippedSetting>()
        val seen = HashSet<String>()
        entries.forEach { element ->
            val entry = element as? JsonObject
            val storedName = entry?.let { it.text("name") ?: it.text("key") }
            if (entry == null || storedName == null || storedName.isEmpty() || storedName.length > MAX_NAME_LENGTH) {
                skipped += SkippedSetting(storedName.orEmpty(), SkippedSetting.Reason.UNREADABLE)
                return@forEach
            }
            val name = SettingsKeyDictionary.currentName(storedName)
            if (name == null) {
                skipped += SkippedSetting(storedName, SkippedSetting.Reason.RETIRED)
                return@forEach
            }
            val kind = (entry.text("kind") ?: entry.text("type"))?.let(::kindOf)
            val value = entry["value"]?.let { raw -> kind?.let { decode(it, raw) } }
            if (kind == null || value == null) {
                skipped += SkippedSetting(storedName, SkippedSetting.Reason.UNREADABLE)
                return@forEach
            }
            if (foreign) {
                val expected = knownKinds[name]
                if (expected == null) {
                    skipped += SkippedSetting(storedName, SkippedSetting.Reason.UNKNOWN_SETTING)
                    return@forEach
                }
                if (kind !in expected) {
                    skipped += SkippedSetting(storedName, SkippedSetting.Reason.WRONG_TYPE)
                    return@forEach
                }
            }
            if (!seen.add(name)) {
                skipped += SkippedSetting(storedName, SkippedSetting.Reason.DUPLICATE)
                return@forEach
            }
            @Suppress("UNCHECKED_CAST")
            editor[keyOf(name, kind) as PreferenceKey<Any>] = value
        }
        return ReadBackupSettings(editor.snapshot(), skipped)
    }

    private fun kindOf(label: String): PreferenceKind? =
        when (label.uppercase()) {
            "BOOLEAN", "BOOL" -> PreferenceKind.BOOLEAN
            "INT", "INTEGER" -> PreferenceKind.INT
            "LONG" -> PreferenceKind.LONG
            "FLOAT" -> PreferenceKind.FLOAT
            "DOUBLE" -> PreferenceKind.DOUBLE
            "STRING" -> PreferenceKind.STRING
            "STRING_SET", "STRINGSET", "SET" -> PreferenceKind.STRING_SET
            else -> null
        }

    private fun keyOf(name: String, kind: PreferenceKind): PreferenceKey<*> =
        when (kind) {
            PreferenceKind.BOOLEAN -> BooleanPreferenceKey(name)
            PreferenceKind.STRING -> StringPreferenceKey(name)
            PreferenceKind.FLOAT -> FloatPreferenceKey(name)
            PreferenceKind.INT -> IntPreferenceKey(name)
            PreferenceKind.LONG -> LongPreferenceKey(name)
            PreferenceKind.DOUBLE -> DoublePreferenceKey(name)
            PreferenceKind.STRING_SET -> StringSetPreferenceKey(name)
        }

    /** Typed JSON is Natune's layout; a JSON string holding the value is the Metrolist layout. Null when unreadable. */
    private fun decode(kind: PreferenceKind, raw: JsonElement): Any? {
        if (kind == PreferenceKind.STRING_SET) {
            val items = when (raw) {
                is JsonArray -> raw.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
                is JsonPrimitive -> if (raw.isString) {
                    // A string set some players flatten into one JSON-encoded string.
                    runCatching { (json.parseToJsonElement(raw.content) as JsonArray).map { (it as JsonPrimitive).content } }
                        .getOrNull() ?: return null
                } else return null
                else -> return null
            }
            return items.takeIf { it.size <= MAX_ENTRIES }?.toSet()
        }
        val primitive = raw as? JsonPrimitive ?: return null
        if (primitive.isString) {
            val text = primitive.content
            return when (kind) {
                PreferenceKind.STRING -> text
                PreferenceKind.BOOLEAN -> text.lowercase().toBooleanStrictOrNull()
                PreferenceKind.INT -> text.trim().toIntOrNull()
                PreferenceKind.LONG -> text.trim().toLongOrNull()
                PreferenceKind.FLOAT -> text.trim().toFloatOrNull()
                PreferenceKind.DOUBLE -> text.trim().toDoubleOrNull()
                PreferenceKind.STRING_SET -> null
            }
        }
        return when (kind) {
            PreferenceKind.STRING -> null
            PreferenceKind.BOOLEAN -> primitive.booleanOrNull
            PreferenceKind.INT -> primitive.intOrNull
            PreferenceKind.LONG -> primitive.longOrNull
            // Natune writes raw bits, which keep NaN and the exact value; a decimal number is another writer's float.
            PreferenceKind.FLOAT -> primitive.intOrNull?.let(Float::fromBits) ?: primitive.doubleOrNull?.toFloat()
            PreferenceKind.DOUBLE -> primitive.longOrNull?.let(Double::fromBits) ?: primitive.doubleOrNull
            PreferenceKind.STRING_SET -> null
        }
    }

    private fun JsonObject.text(field: String): String? =
        (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

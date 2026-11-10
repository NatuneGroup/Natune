package moe.rukamori.archivetune.backup

import android.content.Context
import androidx.datastore.preferences.core.*
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import moe.rukamori.archivetune.shared.data.persistence.LegacyDataStorePreferences
import moe.rukamori.archivetune.shared.domain.settings.PreferenceKind
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.normalizeRestoredSettings
import moe.rukamori.archivetune.utils.applySharedPreferenceEdit
import moe.rukamori.archivetune.utils.dataStore
import org.xmlpull.v1.XmlPullParser

/** Parsed legacy XML settings, kept read-only until the restore commit applies them. */
internal data class LegacySettingsValues(
    val booleans: Map<String, Boolean> = emptyMap(),
    val ints: Map<String, Int> = emptyMap(),
    val longs: Map<String, Long> = emptyMap(),
    val floats: Map<String, Float> = emptyMap(),
    val strings: Map<String, String> = emptyMap(),
    val stringSets: Map<String, Set<String>> = emptyMap(),
    val doubles: Map<String, Double> = emptyMap(),
) {
    val keyNames: Set<String>
        get() = booleans.keys + ints.keys + longs.keys + floats.keys + strings.keys + stringSets.keys + doubles.keys

    val isEmpty: Boolean get() = keyNames.isEmpty()
}

/** Read-only parse used by stage preflight; this never opens DataStore or writes a preference. */
internal fun readLegacySettings(
    inputStream: InputStream,
    includeKey: (String) -> Boolean = { true },
): LegacySettingsValues {
    val content = inputStream.readBytes().toString(Charsets.UTF_8)
    if (content.isBlank()) return LegacySettingsValues()
    val parser = android.util.Xml.newPullParser()
    parser.setInput(StringReader(content))
    var eventType = parser.eventType
    val booleans = LinkedHashMap<String, Boolean>()
    val ints = LinkedHashMap<String, Int>()
    val longs = LinkedHashMap<String, Long>()
    val floats = LinkedHashMap<String, Float>()
    val strings = LinkedHashMap<String, String>()
    val stringSets = LinkedHashMap<String, Set<String>>()
    val doubles = LinkedHashMap<String, Double>()

    while (eventType != XmlPullParser.END_DOCUMENT) {
        if (eventType == XmlPullParser.START_TAG) {
            val name = parser.name
            val keyName = parser.getAttributeValue(null, "name")
            if (keyName != null && includeKey(keyName)) {
                when (name) {
                    "boolean" -> parser.getAttributeValue(null, "value")?.toBoolean()?.let { booleans[keyName] = it }
                    "int" -> parser.getAttributeValue(null, "value")?.toIntOrNull()?.let { ints[keyName] = it }
                    "long" -> parser.getAttributeValue(null, "value")?.toLongOrNull()?.let { longs[keyName] = it }
                    "float" -> parser.getAttributeValue(null, "value")?.toFloatOrNull()?.let { floats[keyName] = it }
                    "string" -> parser.getAttributeValue(null, "value")?.let { strings[keyName] = it }
                    "double" -> parser.getAttributeValue(null, "value")?.toDoubleOrNull()?.let { doubles[keyName] = it }
                    "string-set" -> {
                        val values = LinkedHashSet<String>()
                        while (true) {
                            val next = parser.next()
                            if (next == XmlPullParser.START_TAG && parser.name == "item") {
                                values.add(parser.nextText())
                                continue
                            }
                            if (next == XmlPullParser.END_TAG && parser.name == "string-set") break
                            if (next == XmlPullParser.END_DOCUMENT) break
                        }
                        stringSets[keyName] = values
                    }
                }
            }
        }
        eventType = parser.next()
    }
    return LegacySettingsValues(booleans, ints, longs, floats, strings, stringSets, doubles)
}

/** Writes [snapshot] in the legacy XML shape [readLegacySettings] parses, keeping only [includeKey] names. */
internal fun writeLegacySettingsXml(
    outputStream: OutputStream,
    snapshot: PreferenceSnapshot,
    includeKey: (String) -> Boolean,
) {
    val serializer = android.util.Xml.newSerializer()
    serializer.setOutput(outputStream, Charsets.UTF_8.name())
    serializer.startDocument(Charsets.UTF_8.name(), true)
    serializer.startTag(null, "ArchiveTuneBackup")
    serializer.startTag(null, "Settings")
    val included = LegacyDataStorePreferences.filtered(snapshot, includeKey)
    included.keys.forEach { key ->
        val value = included[key] ?: return@forEach
        val tag = when (key.kind) {
            PreferenceKind.BOOLEAN -> "boolean"
            PreferenceKind.INT -> "int"
            PreferenceKind.LONG -> "long"
            PreferenceKind.FLOAT -> "float"
            PreferenceKind.DOUBLE -> "double"
            PreferenceKind.STRING -> "string"
            PreferenceKind.STRING_SET -> "string-set"
        }
        serializer.startTag(null, tag)
        serializer.attribute(null, "name", key.name)
        if (value is Set<*>) {
            value.forEach { item ->
                serializer.startTag(null, "item")
                serializer.text(item.toString())
                serializer.endTag(null, "item")
            }
        } else {
            serializer.attribute(null, "value", value.toString())
        }
        serializer.endTag(null, tag)
    }
    serializer.endTag(null, "Settings")
    serializer.endTag(null, "ArchiveTuneBackup")
    serializer.endDocument()
    serializer.flush()
}

private fun MutablePreferences.applyLegacySettings(values: LegacySettingsValues) {
    values.booleans.forEach { (key, value) -> this[booleanPreferencesKey(key)] = value }
    values.ints.forEach { (key, value) -> this[intPreferencesKey(key)] = value }
    values.longs.forEach { (key, value) -> this[longPreferencesKey(key)] = value }
    values.floats.forEach { (key, value) -> this[floatPreferencesKey(key)] = value }
    values.strings.forEach { (key, value) -> this[stringPreferencesKey(key)] = value }
    values.stringSets.forEach { (key, value) -> this[stringSetPreferencesKey(key)] = value }
    values.doubles.forEach { (key, value) -> this[doublePreferencesKey(key)] = value }
}

/** Keep the legacy XML merge and category filter; normalize its candidate inside that same edit. */
internal suspend fun restoreSettingsFromXml(
    context: Context,
    inputStream: InputStream,
    includeKey: (String) -> Boolean = { true },
) {
    val values = readLegacySettings(inputStream, includeKey)
    if (values.isEmpty) return
    context.dataStore.edit { prefs ->
        prefs.applyLegacySettings(values)
        prefs.applySharedPreferenceEdit { normalizeRestoredSettings(values.keyNames) }
    }
}

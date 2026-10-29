package moe.rukamori.archivetune.backup

import android.content.Context
import androidx.datastore.preferences.core.*
import java.io.InputStream
import java.io.StringReader
import moe.rukamori.archivetune.shared.domain.settings.normalizeRestoredSettings
import moe.rukamori.archivetune.utils.applySharedPreferenceEdit
import moe.rukamori.archivetune.utils.dataStore
import org.xmlpull.v1.XmlPullParser

/** Keep the legacy XML merge and account exclusions; normalize its candidate inside that same edit. */
internal suspend fun restoreSettingsFromXml(
    context: Context,
    inputStream: InputStream,
    excludedKeyNames: Set<String> = emptySet(),
) {
    val content = inputStream.readBytes().toString(Charsets.UTF_8)
    if (content.isBlank()) return
    val parser = android.util.Xml.newPullParser()
    parser.setInput(StringReader(content))
    var eventType = parser.eventType
    val booleans = LinkedHashMap<String, Boolean>()
    val ints = LinkedHashMap<String, Int>()
    val longs = LinkedHashMap<String, Long>()
    val floats = LinkedHashMap<String, Float>()
    val strings = LinkedHashMap<String, String>()
    val stringSets = LinkedHashMap<String, Set<String>>()

    while (eventType != XmlPullParser.END_DOCUMENT) {
        if (eventType == XmlPullParser.START_TAG) {
            val name = parser.name
            val keyName = parser.getAttributeValue(null, "name")
            if (keyName != null && keyName !in excludedKeyNames) {
                when (name) {
                    "boolean" -> parser.getAttributeValue(null, "value")?.toBoolean()?.let { booleans[keyName] = it }
                    "int" -> parser.getAttributeValue(null, "value")?.toIntOrNull()?.let { ints[keyName] = it }
                    "long" -> parser.getAttributeValue(null, "value")?.toLongOrNull()?.let { longs[keyName] = it }
                    "float" -> parser.getAttributeValue(null, "value")?.toFloatOrNull()?.let { floats[keyName] = it }
                    "string" -> parser.getAttributeValue(null, "value")?.let { strings[keyName] = it }
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
    val restoredKeyNames = booleans.keys + ints.keys + longs.keys + floats.keys + strings.keys + stringSets.keys
    if (restoredKeyNames.isEmpty()) return
    context.dataStore.edit { prefs ->
        booleans.forEach { (key, value) -> prefs[booleanPreferencesKey(key)] = value }
        ints.forEach { (key, value) -> prefs[intPreferencesKey(key)] = value }
        longs.forEach { (key, value) -> prefs[longPreferencesKey(key)] = value }
        floats.forEach { (key, value) -> prefs[floatPreferencesKey(key)] = value }
        strings.forEach { (key, value) -> prefs[stringPreferencesKey(key)] = value }
        stringSets.forEach { (key, value) -> prefs[stringSetPreferencesKey(key)] = value }
        prefs.applySharedPreferenceEdit { normalizeRestoredSettings(restoredKeyNames) }
    }
}

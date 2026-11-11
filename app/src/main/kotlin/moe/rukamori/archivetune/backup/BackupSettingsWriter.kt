package moe.rukamori.archivetune.backup

import androidx.datastore.preferences.core.Preferences
import java.io.OutputStream

internal fun writeBackupSettingsToXml(
    output: OutputStream,
    preferences: Preferences,
    categories: Set<BackupArchiveCategory>,
) {
    val serializer = android.util.Xml.newSerializer()
    serializer.setOutput(output, Charsets.UTF_8.name())
    serializer.startDocument(Charsets.UTF_8.name(), true)
    serializer.startTag(null, "ArchiveTuneBackup")
    serializer.startTag(null, "Settings")
    preferences.asMap().forEach { (key, value) ->
        if (BackupExportPolicy.includes(key.name, categories)) serializer.writePreference(key, value)
    }
    serializer.endTag(null, "Settings")
    serializer.endTag(null, "ArchiveTuneBackup")
    serializer.endDocument()
    serializer.flush()
}

private fun org.xmlpull.v1.XmlSerializer.writePreference(
    key: Preferences.Key<*>,
    value: Any,
) {
    val tagName =
        when (value) {
            is Boolean -> "boolean"
            is Int -> "int"
            is Long -> "long"
            is Float -> "float"
            is String -> "string"
            is Set<*> -> "string-set"
            else -> return
        }
    startTag(null, tagName)
    attribute(null, "name", key.name)
    if (value is Set<*>) {
        value.forEach { item ->
            startTag(null, "item")
            text(item.toString())
            endTag(null, "item")
        }
    } else {
        attribute(null, "value", value.toString())
    }
    endTag(null, tagName)
}

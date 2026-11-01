package moe.rukamori.archivetune.qobuz

import org.json.JSONObject

internal fun JSONObject.reportedSampleRate(): Int? {
    val rate = listOfNotNull(optJSONObject("data"), this).firstNotNullOfOrNull { data ->
        data.optDouble("sampling_rate").takeIf { it.isFinite() && it > 0 }
            ?: data.optDouble("sampleRate").takeIf { it.isFinite() && it > 0 }
    } ?: return null
    return (if (rate < 1_000) rate * 1_000 else rate).toInt().takeIf { it > 0 }
}

internal fun JSONObject.reportedBitDepth(): Int? {
    return listOfNotNull(optJSONObject("data"), this).firstNotNullOfOrNull { data ->
        data.optInt("bit_depth").takeIf { it > 0 } ?: data.optInt("bitDepth").takeIf { it > 0 }
    }
}

/** Recursively searches the retained instance response shapes, without promising the requested quality. */
internal fun JSONObject.findStreamUrl(): String? {
    for (key in listOf("url", "downloadUrl", "download_url", "stream_url", "streamUrl", "link")) {
        stringOrNull(key)?.takeIf { it.startsWith("http") }?.let { return it }
    }
    optJSONObject("data")?.findStreamUrl()?.let { return it }
    val names = keys()
    while (names.hasNext()) {
        val value = opt(names.next())
        when (value) {
            is JSONObject -> value.findStreamUrl()?.let { return it }
            is String -> if (value.startsWith("http") && (value.contains(".flac", true) || value.contains(".mp3", true))) return value
        }
    }
    return null
}

internal fun JSONObject.looksLikePreview(): Boolean {
    val data = optJSONObject("data") ?: this
    if (data.optBoolean("sample", false)) return true
    if (data.optBoolean("preview", false)) return true
    data.stringOrNull("type")?.let { if (it.equals("preview", true) || it.equals("sample", true)) return true }
    return false
}

private fun JSONObject.stringOrNull(key: String): String? =
    optString(key).takeIf { it.isNotBlank() && it != "null" }

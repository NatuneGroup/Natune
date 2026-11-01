package moe.rukamori.archivetune.qobuz

import kotlin.math.roundToInt
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

internal const val AUDIO_FLAC_MIME_TYPE = "audio/flac"
internal const val AUDIO_MPEG_MIME_TYPE = "audio/mpeg"

/**
 * What a download response states about the file it serves. A field stays null unless the response,
 * or for the mime type alone the stream URL, actually says so. Nothing falls back to the requested tier.
 */
internal data class ServedAudio(
    /** audio/flac or audio/mpeg when the response or the stream URL says so, otherwise null. */
    val mimeType: String?,
    /** Qobuz format id the response reports (5, 6, 7 or 27). */
    val formatId: Int?,
    /** Sample rate in Hz, converted from the kHz the response reports. */
    val sampleRateHz: Int?,
    /** Null for MPEG streams: a PCM bit depth is not a property of a lossy stream. */
    val bitDepth: Int?,
) {
    /** Tier named from the served facts only; a bare "Qobuz" when nothing usable is reported. */
    fun label(): String {
        val tier =
            when (mimeType) {
                AUDIO_MPEG_MIME_TYPE -> if (formatId == 5) "MP3_320" else "MP3"
                AUDIO_FLAC_MIME_TYPE ->
                    when (formatId) {
                        7 -> "HI_RES"
                        27 -> "MAX"
                        else -> "FLAC"
                    }
                else ->
                    when (formatId) {
                        5 -> "MP3_320"
                        6 -> "FLAC"
                        7 -> "HI_RES"
                        27 -> "MAX"
                        else -> null
                    }
            }
        return if (tier == null) "Qobuz" else "Qobuz $tier"
    }
}

/** Reads what the response reports. The stream URL is consulted only to identify the mime type. */
internal fun JSONObject.servedAudio(streamUrl: String): ServedAudio {
    val data = optJSONObject("data") ?: this
    val reportedMime = data.stringOrNull("mime_type")?.lowercase().orEmpty()
    val mimeType =
        when {
            "flac" in reportedMime -> AUDIO_FLAC_MIME_TYPE
            "mpeg" in reportedMime || "mp3" in reportedMime -> AUDIO_MPEG_MIME_TYPE
            streamUrl.contains(".flac", true) -> AUDIO_FLAC_MIME_TYPE
            streamUrl.contains(".mp3", true) -> AUDIO_MPEG_MIME_TYPE
            else -> null
        }
    // Qobuz reports the rate in kHz (44.1, 96); DirectStream carries Hz.
    val sampleRateHz =
        data.optDouble("sampling_rate", 0.0).takeIf { it > 0.0 }?.let { reported ->
            (if (reported < 1_000.0) reported * 1_000.0 else reported).roundToInt().takeIf { it > 0 }
        }
    // A lossy MPEG stream has no PCM bit depth to report, so a reported value is dropped for it.
    val reportedBitDepth = data.optInt("bit_depth", 0).takeIf { it in 1..32 }
    return ServedAudio(
        mimeType = mimeType,
        formatId = data.optInt("format_id", 0).takeIf { it > 0 },
        sampleRateHz = sampleRateHz,
        bitDepth = reportedBitDepth.takeUnless { mimeType == AUDIO_MPEG_MIME_TYPE },
    )
}

/** MPEG audio is labelled mp3, never AAC; any other mime keeps the legacy FLAC default. */
internal fun codecsFor(mimeType: String): String = if (mimeType == AUDIO_MPEG_MIME_TYPE) "mp3" else "flac"

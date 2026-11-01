package moe.rukamori.archivetune.deezer

import org.json.JSONObject

/** One stream entry of a media-endpoint reply: the CDN URL, the tier it was served at and its size. */
internal class Media(
    val url: String,
    val flac: Boolean,
    /** The tier the gateway served, which may be lower than the one requested. */
    val format: String,
    val contentLength: Long?,
)

/** A string field's text, or null when it is absent, JSON null or blank. */
internal fun JSONObject.textOrNull(key: String): String? =
    optString(key, "").takeIf { it.isNotBlank() && it != "null" }

/**
 * The same preview test the shared Deezer provider applies: a sample/preview flag, a preview type or a
 * preview presentation marks a 30-second clip rather than the full track.
 */
internal fun JSONObject.isPreviewMedia(): Boolean =
    optBoolean("sample", false) || optBoolean("preview", false) ||
        textOrNull("type")?.lowercase()?.let { it in setOf("preview", "sample") } == true ||
        textOrNull("assetPresentation").equals("PREVIEW", ignoreCase = true) ||
        textOrNull("trackPresentation").equals("PREVIEW", ignoreCase = true) ||
        optJSONObject("data")?.isPreviewMedia() == true

/**
 * The first usable full stream among the media entries of a get_url reply. Previews, other types, an
 * unexpected cipher, a tier that was not requested and entries without a URL are skipped, so the first
 * entry no longer wins by position alone. When none qualifies there is no match and the caller moves on
 * to the next account or source. [trackJson] is the song.getData reply and supplies the FILESIZE hint.
 */
internal fun selectStreamMedia(
    json: JSONObject,
    trackJson: JSONObject,
    formats: List<String>,
): Media? {
    val first = json.optJSONArray("data")?.optJSONObject(0) ?: return null
    val mediaEntries = first.optJSONArray("media") ?: return null
    for (index in 0 until mediaEntries.length()) {
        val media = mediaEntries.optJSONObject(index) ?: continue
        if (media.isPreviewMedia()) continue
        val type = media.textOrNull("type")
        if (type != null && type != "FULL") continue
        val cipher = media.textOrNull("cipher")
        if (cipher != null && cipher != "BF_CBC_STRIPE") continue
        // The tier the gateway actually served, which may be lower than the one requested.
        val servedFormat = media.textOrNull("format")?.uppercase() ?: continue
        if (servedFormat !in formats) continue
        val url = media.optJSONArray("sources")?.optJSONObject(0)?.textOrNull("url") ?: continue
        val flac = servedFormat == DeezerAudioProvider.FORMAT_FLAC

        // song.getData carries a per-format size (FILESIZE_FLAC, FILESIZE_MP3_320, ...). The encrypted
        // stream is byte-for-byte the same length as the decrypted one because BF_CBC_STRIPE adds no
        // padding, so this size is valid for the decrypted output and saves a HEAD probe. Null is fine:
        // DirectStream treats an unknown length as "ask the CDN".
        val sizeField = "FILESIZE_$servedFormat"
        val results = trackJson.optJSONObject("results")
        val contentLength =
            results?.optString(sizeField)?.toLongOrNull()?.takeIf { it > 0L }
                ?: results?.optString("FILESIZE")?.toLongOrNull()?.takeIf { it > 0L }

        return Media(url = url, flac = flac, format = servedFormat, contentLength = contentLength)
    }
    return null
}

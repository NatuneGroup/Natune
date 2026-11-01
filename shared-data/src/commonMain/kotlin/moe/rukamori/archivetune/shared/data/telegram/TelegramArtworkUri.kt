package moe.rukamori.archivetune.shared.data.telegram

/**
 * The `tgart://` URI a Telegram track item carries so a host's artwork loader can resolve its cover: the
 * thumbnail file id plus the cleaned title and artist for the catalogue lookup, with the same percent encoding
 * the Android builder produced (UTF-8 percent-encoding with spaces as %20, never `+`).
 */
fun telegramArtworkUri(fileId: Int, title: String?, artist: String?): String? {
    if (fileId <= 0 && title.isNullOrBlank()) return null
    return buildString {
        append("tgart://").append(fileId.coerceAtLeast(0))
        val query = buildList {
            title?.takeIf(String::isNotBlank)?.let { add("t=${it.encodeTelegramArtworkQuery()}") }
            artist?.takeIf(String::isNotBlank)?.let { add("a=${it.encodeTelegramArtworkQuery()}") }
        }
        if (query.isNotEmpty()) append('?').append(query.joinToString("&"))
    }
}

/** The form-urlencoding the Android builder used, except spaces become %20 so a URLDecoder never adds a `+`. */
private fun String.encodeTelegramArtworkQuery(): String = buildString {
    for (byte in encodeToByteArray()) {
        val char = byte.toInt().toChar()
        if (char.isTelegramQueryUnreserved()) {
            append(char)
        } else {
            append('%').append((byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase())
        }
    }
}

private fun Char.isTelegramQueryUnreserved(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '.' || this == '-' || this == '*' || this == '_'

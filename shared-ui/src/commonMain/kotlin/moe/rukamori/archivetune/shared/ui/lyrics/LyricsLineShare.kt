package moe.rukamori.archivetune.shared.ui.lyrics

/**
 * The plain text a share of one lyric row sends: the sung line, then its background vocal and its translation on
 * their own lines when the row has them. The phonetic reading is left out, because it restates the sung line in
 * another script. An instrumental or empty row has nothing to share and answers null.
 */
fun NativeLyricsLine.shareText(): String? {
    if (instrumental) return null
    val main = text.trim()
    if (main.isEmpty()) return null
    return listOfNotNull(main, background?.trim()?.takeIf(String::isNotEmpty), translation?.trim()?.takeIf(String::isNotEmpty))
        .distinct()
        .joinToString("\n")
}

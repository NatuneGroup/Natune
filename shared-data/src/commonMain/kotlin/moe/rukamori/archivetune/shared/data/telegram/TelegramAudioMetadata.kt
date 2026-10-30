package moe.rukamori.archivetune.shared.data.telegram

/**
 * How a Telegram audio message reads as a song: which documents count as audio, which count as lossless, and the
 * title and artist a tagless file name yields. The rules and lists are the Android and desktop ones, so a channel
 * synced on any host lists the same songs under the same names.
 */
object TelegramAudioMetadata {
    private val LOSSLESS_MIME_TYPES = setOf(
        "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave",
        "audio/aiff", "audio/x-aiff", "audio/x-ape", "audio/x-monkeys-audio", "audio/x-wavpack",
        "audio/wavpack", "audio/x-tak", "audio/x-tta", "audio/dsd", "audio/x-dsd", "audio/x-dsf", "audio/alac",
    )
    private val LOSSLESS_EXTENSIONS =
        setOf("flac", "wav", "wave", "aiff", "aif", "ape", "alac", "wv", "tak", "tta", "dsf", "dff", "shn")
    private val AUDIO_EXTENSIONS = LOSSLESS_EXTENSIONS + setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wma", "mka")
    private val NOISE_SUFFIX = Regex("\\((?:official|lyric|audio|video|hd|hq|visualizer)[^)]*\\)", RegexOption.IGNORE_CASE)
    private val BRACKET_TAG = Regex("\\[[^\\]]*\\]")
    private val LEADING_TRACK_NUMBER = Regex("^\\s*\\d{1,3}\\s*[.\\-]\\s*")
    private val WHITESPACE = Regex("\\s+")
    private val ARTIST_SEPARATORS = listOf(" - ", " \u2013 ", " \u2014 ")

    fun fileExtension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    fun isLossless(mimeType: String, fileName: String): Boolean =
        mimeType.trim().lowercase() in LOSSLESS_MIME_TYPES || fileExtension(fileName) in LOSSLESS_EXTENSIONS

    /** A document is a song when its type says audio or its name ends in a known audio extension. */
    fun isAudioDocument(mimeType: String, fileName: String): Boolean =
        mimeType.trim().lowercase().startsWith("audio/") || fileExtension(fileName) in AUDIO_EXTENSIONS

    /** Drops "(Official Video)"-style suffixes, bracket tags and a leading track number. */
    fun cleanTrackName(raw: String): String =
        raw.replace(NOISE_SUFFIX, " ")
            .replace(BRACKET_TAG, " ")
            .replace(LEADING_TRACK_NUMBER, "")
            .replace(WHITESPACE, " ")
            .trim()

    /**
     * The title and artist of one track. The audio tags win when they are set. Otherwise the file name is cleaned, and an
     * "Artist - Title" name is split, but only when no performer tag names the artist already.
     */
    fun derive(tagTitle: String, tagPerformer: String?, fileName: String): Pair<String, String?> {
        val performer = tagPerformer?.trim()?.takeIf(String::isNotEmpty)
        if (tagTitle.isNotBlank()) return cleanTrackName(tagTitle).ifBlank { tagTitle.trim() } to performer
        val base = cleanTrackName(fileName.substringBeforeLast('.').ifBlank { fileName })
        if (performer == null) {
            for (separator in ARTIST_SEPARATORS) {
                val index = base.indexOf(separator)
                if (index > 0 && index < base.length - separator.length) {
                    val artist = base.substring(0, index).trim()
                    val track = base.substring(index + separator.length).trim()
                    if (artist.isNotEmpty() && track.isNotEmpty()) return track to artist
                }
            }
        }
        return base.ifBlank { fileName } to performer
    }
}

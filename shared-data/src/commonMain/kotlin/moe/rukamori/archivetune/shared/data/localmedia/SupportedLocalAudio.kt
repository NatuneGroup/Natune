package moe.rukamori.archivetune.shared.data.localmedia

/** Shipped Android allow/deny list; a MIME fallback never overrides an explicitly blocked suffix. */
object SupportedLocalAudio {
    private val extensions = setOf("aac", "amr", "flac", "m4a", "m4b", "m4p", "mid", "mka", "mp3",
        "mp4", "oga", "ogg", "opus", "wav", "weba", "webm", "3ga", "3gp")
    private val blocked = setOf("ape", "cue", "dsd", "dsf", "dff", "midi", "iso", "tta", "wv", "wvc")
    private val mimeTypes = setOf("audio/aac", "audio/aac-adts", "audio/amr", "audio/amr-wb", "audio/flac",
        "audio/mp4", "audio/mpeg", "audio/ogg", "audio/opus", "audio/wav", "audio/wave", "audio/webm",
        "audio/x-aac", "audio/x-flac", "audio/x-m4a", "audio/x-mka", "audio/x-matroska", "audio/x-mpeg",
        "audio/x-wav", "application/ogg", "video/mp4", "video/webm", "video/3gpp")

    fun isSupported(displayName: String?, mimeType: String?): Boolean {
        val extension = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (extension in blocked) return false
        return extension in extensions || normalizedMime(mimeType) in mimeTypes
    }
    fun isSupportedMimeType(mimeType: String?): Boolean =
        normalizedMime(mimeType).let { it in mimeTypes || it == "audio/*" }
    private fun normalizedMime(mime: String?) = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
}

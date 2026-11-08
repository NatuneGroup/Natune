package moe.rukamori.archivetune.modules

import org.json.JSONObject

enum class ModuleLyricsFormat { LRC, TTML, PLAIN }

data class ModuleLyrics(val format: ModuleLyricsFormat, val text: String, val language: String?) {
    companion object {
        const val MAX_TEXT_BYTES = 1024 * 1024

        /** Null is an ordinary provider miss, never a lyrics-not-found document to cache. */
        fun parse(value: Any?): ModuleLyrics? {
            if (value == null || value == JSONObject.NULL) return null
            val document = value as? JSONObject ?: invalid()
            val format = when (document.opt("format")) {
                "lrc" -> ModuleLyricsFormat.LRC
                "ttml" -> ModuleLyricsFormat.TTML
                "plain" -> ModuleLyricsFormat.PLAIN
                else -> invalid()
            }
            val text = document.opt("text") as? String ?: invalid()
            if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES || '\u0000' in text) invalid()
            val language = if (!document.has("language") || document.isNull("language")) null
                else (document.opt("language") as? String)?.takeIf { it.length in 1..64 } ?: invalid()
            return ModuleLyrics(format, text, language)
        }

        private fun invalid(): Nothing = throw ModuleException(ModuleFailure.INVALID_DOCUMENT,
            stage = ModuleLoadStage.PROVIDER, diagnosticCode = "NATUNE_LYRICS_RESULT")
    }
}

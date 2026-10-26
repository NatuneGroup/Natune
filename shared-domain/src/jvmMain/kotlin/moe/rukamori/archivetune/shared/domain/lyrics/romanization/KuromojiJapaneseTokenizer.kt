/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * IPADIC-backed [JapaneseTokenizer] for the JVM host. The dictionary is the one bundled in
 * `kuromoji-ipadic`, loaded from the classpath. Construction is expensive, so callers should create one
 * instance and share it; tokenising is safe to call from several threads.
 */
class KuromojiJapaneseTokenizer private constructor(
    private val tokenizer: Tokenizer,
) : JapaneseTokenizer {
    override fun tokenize(text: String): List<JapaneseToken> =
        tokenizer.tokenize(text).map { token ->
            JapaneseToken(surface = token.surface, reading = token.reading, position = token.position)
        }

    companion object {
        /**
         * Loads the dictionary and checks that it can read 日本語 back, as the Android downloaded
         * dictionary does. A dictionary that cannot do so is not used, so the caller falls back to no
         * Japanese romanisation.
         */
        fun create(): KuromojiJapaneseTokenizer {
            val tokenizer = KuromojiJapaneseTokenizer(Tokenizer())
            val probe = tokenizer.tokenize("日本語")
            val surface = probe.joinToString("") { it.surface }
            val hasReading = probe.any { token ->
                val reading = token.reading
                !reading.isNullOrEmpty() && reading != "*"
            }
            check(surface == "日本語" && hasReading) { "Japanese dictionary is not usable" }
            return tokenizer
        }
    }
}

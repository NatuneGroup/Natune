package moe.rukamori.archivetune.shared.domain.lyrics.unicode

/**
 * Pure script predicates from the Android lyric utilities. They say which writing system a line uses and
 * nothing about language eligibility or romanisation policy, which stay with their callers.
 */
object LyricsScriptPredicates {
    /**
     * Whether [char] is a letter of [script]. Surrogate halves are never letters here, so supplementary
     * characters do not match a script through this char-based check, as on Android.
     */
    fun hasScript(
        char: Char,
        script: UnicodeScript,
    ): Boolean = char.isLetter() && UnicodeScripts.scriptOf(char.code) == script

    /** Hiragana, Katakana or the CJK Unified Ideograph block U+4E00..U+9FFF, by char range. */
    fun isJapanese(text: String): Boolean =
        text.any { char ->
            (char in '\u3040'..'\u309F') ||
                (char in '\u30A0'..'\u30FF') ||
                // The ideograph block is shared with Chinese, so this range alone is not proof of Japanese.
                (char in '\u4E00'..'\u9FFF')
        }

    /** Any Hangul character, including bare jamo. Deliberately no letter check, as on Android. */
    fun isKorean(text: String): Boolean =
        text.any { char -> UnicodeScripts.scriptOf(char.code) == UnicodeScript.HANGUL }

    /**
     * Han text without kana or Hangul. Han alone cannot tell Chinese from Japanese, so this only says
     * the text has no Japanese or Korean script marks, not that it is Chinese.
     */
    fun isChinese(text: String): Boolean {
        if (text.isEmpty()) return false
        var hanCount = 0
        text.forEachCodePoint { codePoint ->
            if (UnicodeScripts.scriptOf(codePoint) == UnicodeScript.HAN) hanCount++
        }
        if (hanCount == 0) return false
        val kanaCount = text.count { hasScript(it, UnicodeScript.HIRAGANA) || hasScript(it, UnicodeScript.KATAKANA) }
        val hangulCount = text.count { hasScript(it, UnicodeScript.HANGUL) }
        return kanaCount == 0 && hangulCount == 0
    }

    fun isHindi(text: String): Boolean = text.any { hasScript(it, UnicodeScript.DEVANAGARI) }

    /**
     * Whether [contextText] reads as Japanese. An explicit source language decides it: Chinese codes
     * force false and `ja` forces true. Otherwise kana in [contextText] is the evidence, because Han
     * alone is ambiguous. Installed data is never language evidence.
     */
    fun looksJapanese(
        @Suppress("UNUSED_PARAMETER") text: String,
        sourceLanguage: String? = null,
        contextText: String = text,
    ): Boolean {
        val language = sourceLanguage?.lowercase()?.replace('_', '-')?.substringBefore('-')
        if (language == "zh" || language == "cmn" || language == "yue") return false
        if (language == "ja") return true
        var hasKana = false
        contextText.forEachCodePoint { codePoint ->
            val script = UnicodeScripts.scriptOf(codePoint)
            if (script == UnicodeScript.HIRAGANA || script == UnicodeScript.KATAKANA) hasKana = true
        }
        return hasKana
    }
}

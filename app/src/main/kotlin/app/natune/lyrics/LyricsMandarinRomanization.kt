package app.natune.lyrics

import app.natune.shared.domain.lyrics.romanization.MandarinRomanization

/** Mandarin via the bundled pinyin table first; ICU stays the fallback for characters the table misses. */
internal suspend fun romanizeChinese(text: String): String =
    MandarinRomanization.romanize(text) ?: LyricsUtils.romanizeWithIcu(text)

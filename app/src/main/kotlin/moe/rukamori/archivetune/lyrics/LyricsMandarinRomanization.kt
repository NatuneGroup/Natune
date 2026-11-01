package moe.rukamori.archivetune.lyrics

import moe.rukamori.archivetune.shared.domain.lyrics.romanization.MandarinRomanization

/** Mandarin via the bundled pinyin table first; ICU stays the fallback for characters the table misses. */
internal suspend fun romanizeChinese(text: String): String =
    MandarinRomanization.romanize(text) ?: LyricsUtils.romanizeWithIcu(text)

package moe.rukamori.archivetune.shared.ui.theme

import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle

/**
 * The app font covers Latin and Devanagari only. Every other script — CJK lyrics, Cyrillic titles, Arabic, emoji — is
 * drawn by the platform's own font stack: Skia's paragraph engine asks the system font manager (DirectWrite on Windows,
 * CoreText on macOS, fontconfig on Linux) for a family that has each missing glyph, so the app bundles no CJK or emoji
 * font and none of them add tens of megabytes to a desktop package. This report records the family the OS answered with;
 * a `none` entry is the one case where the user sees a glyph box, and it is the OS's missing font, not a font the app
 * forgot to ship.
 */
object SystemFontFallback {
    /** One representative character per script a lyric line commonly mixes with a Latin title. */
    private val PROBES = listOf(
        "latin" to 0x0041, // A
        "cjk-han" to 0x6B4C, // 歌
        "cjk-hiragana" to 0x3042, // あ
        "hangul" to 0xAC00, // 가
        "cyrillic" to 0x0416, // Ж
        "arabic" to 0x0634, // ش
        "devanagari" to 0x0915, // क
        "emoji" to 0x1F600, // 😀
    )

    /** The system family that resolves [codePoint], or null when this host has no font for it. */
    fun familyFor(codePoint: Int): String? = runCatching {
        FontMgr.default.matchFamilyStyleCharacter(null, FontStyle.NORMAL, null, codePoint)?.familyName
    }.getOrNull()

    /** `latin=Poppins, cjk-han=Noto Sans CJK JP, …`, with `none` where the platform has no answer. */
    fun report(): String = PROBES.joinToString(", ") { (label, codePoint) ->
        "$label=${familyFor(codePoint) ?: "none"}"
    }
}

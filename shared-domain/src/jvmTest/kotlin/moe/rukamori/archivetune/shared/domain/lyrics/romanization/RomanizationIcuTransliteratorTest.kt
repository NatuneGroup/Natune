/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RomanizationIcuTransliteratorTest {
    private val icu = IcuLyricsTransliterator()
    private val other =
        LyricsRomanizationPreferences(romanizeJapanese = false, romanizeKorean = false, romanizeChinese = false, romanizeHindi = false, romanizeOther = true)

    @Test fun anyLatinTransformRomanisesCyrillicGreekAndArabicAsIcuDoes() {
        // Expected values were read from ICU4J 79.1 with "Any-Latin; Latin-ASCII" on the same inputs.
        assertEquals("Rossia", icu.toLatin("Россия"))
        assertEquals("Ukraina", icu.toLatin("Україна"))
        assertEquals("Ellada", icu.toLatin("Ελλάδα"))
        assertEquals("Omega", icu.toLatin("Ωμέγα"))
        assertEquals("al\u02BFrbyt", icu.toLatin("العربية"))
        assertEquals("Hello", icu.toLatin("Hello"))
    }

    @Test fun lyricsRomanizerUsesTheInjectedTransliteratorForOtherScripts() {
        val romanizer = LyricsRomanizer(transliterator = icu)
        assertEquals("Rossia", romanizer.romanizeLyricsLine("Россия", other))
        assertEquals("Ellada", romanizer.romanizeLyricsLine("Ελλάδα", other))
        assertNull(romanizer.romanizeLyricsLine("Hello", other))
    }
}

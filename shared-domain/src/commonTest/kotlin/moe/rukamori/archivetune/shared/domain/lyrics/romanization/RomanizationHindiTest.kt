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

class RomanizationHindiTest {
    @Test fun androidFixtureRomanisesNamaste() {
        assertEquals("namaste", romanizeHindi("नमस्ते"))
    }

    @Test fun inherentVowelIsDroppedBeforeViramaAndMatras() {
        assertEquals("hindii", romanizeHindi("हिन्दी"))
        assertEquals("pyaara", romanizeHindi("प्यार"))
    }

    @Test fun conjunctWithViramaSuppressesTheFirstVowel() {
        assertEquals("ksha", romanizeHindi("क्ष"))
    }

    @Test fun devanagariNumeralsAndSignsMapToLatin() {
        assertEquals("123", romanizeHindi("१२३"))
        assertEquals("om", romanizeHindi("ॐ"))
    }

    @Test fun latinAndPunctuationPassThrough() {
        assertEquals("Hi namaste!", romanizeHindi("Hi नमस्ते!"))
    }
}

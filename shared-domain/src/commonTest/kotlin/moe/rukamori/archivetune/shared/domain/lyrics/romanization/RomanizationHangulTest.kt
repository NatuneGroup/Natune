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

class RomanizationHangulTest {
    // 한글 spelled precomposed (NFC) and as leading, medial and trailing jamo (NFD).
    private val nfc = "\uD55C\uAE00"
    private val nfd = "\u1112\u1161\u11AB\u1100\u1173\u11AF"

    @Test fun nfcAndNfdSpellingsRomaniseIdentically() {
        assertEquals("hangeul", romanizeKorean(nfc))
        assertEquals("hangeul", romanizeKorean(nfd))
    }

    @Test fun jamoCompositionJoinsLeadingVowelAndTrailingJamo() {
        assertEquals("\uD55C", composeHangulJamo("\u1112\u1161\u11AB"))
        assertEquals("\uAC01", composeHangulJamo("\u1100\u1161\u11A8"))
        // An LV syllable followed by a trailing jamo becomes LVT, which is the second NFC rule.
        assertEquals("\uAC01", composeHangulJamo("\uAC00\u11A8"))
    }

    @Test fun jamoCompositionLeavesUnpairedAndCompatibilityJamoAlone() {
        assertEquals("\u1100\u1100", composeHangulJamo("\u1100\u1100"))
        // A composed LVT syllable is not a base for a further vowel, so the vowel stays separate.
        assertEquals("\uAC01\u1161", composeHangulJamo("\u1100\u1161\u11A8\u1161"))
        // Compatibility jamo are not conjoining jamo and are never composed.
        assertEquals("\u3131\u314F", composeHangulJamo("\u3131\u314F"))
        assertEquals("abc 東", composeHangulJamo("abc 東"))
    }

    @Test fun syllablesRomaniseWithInitialMedialAndFinal() {
        assertEquals("gak", romanizeKorean("\uAC01"))
        assertEquals("ga", romanizeKorean("\uAC00"))
        assertEquals("annyeong", romanizeKorean("\uC548\uB155"))
    }

    @Test fun finalConsonantLiaisonUsesTheNextInitial() {
        // 국어: the final ᆨ before ᄋ is read as "g" because it carries onto the next syllable.
        assertEquals("gugeo", romanizeKorean("\uAD6D\uC5B4"))
        // 한국: no liaison, so the final ᆫ is written before the next initial ᄀ.
        assertEquals("hanguk", romanizeKorean("\uD55C\uAD6D"))
    }

    @Test fun nonHangulTextPassesThroughAsItIs() {
        assertEquals("A han", romanizeKorean("A \uD55C"))
    }
}

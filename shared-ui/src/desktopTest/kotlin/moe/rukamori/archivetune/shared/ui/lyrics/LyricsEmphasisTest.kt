package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The emphasis gate: only short, held, unjoined-script words ripple. */
class LyricsEmphasisTest {
    @Test
    fun aShortHeldLatinWordEarnsTheRipple() {
        assertTrue(LyricsEmphasis.isEmphasized("hello", clusterCount = 5, durationMs = 1_500))
    }

    @Test
    fun durationBelowAMinimumStaysPlain() {
        assertFalse(LyricsEmphasis.isEmphasized("hello", clusterCount = 5, durationMs = 999))
        assertTrue(LyricsEmphasis.isEmphasized("hello", clusterCount = 5, durationMs = 1_000))
    }

    @Test
    fun longWordsKeepThePlainSweep() {
        assertFalse(LyricsEmphasis.isEmphasized("antidisestablishmentarianism", clusterCount = 28, durationMs = 4_000))
        assertFalse(LyricsEmphasis.isEmphasized("eightlet", clusterCount = 8, durationMs = 4_000))
        assertTrue(LyricsEmphasis.isEmphasized("sevenlt", clusterCount = 7, durationMs = 4_000))
    }

    @Test
    fun unjoinedSafeScriptsRipple() {
        assertTrue(LyricsEmphasis.supports("hello"))
        assertTrue(LyricsEmphasis.supports("naive"))
        assertTrue(LyricsEmphasis.supports("\u0395\u03bb\u03bb\u03b7\u03bd\u03b9\u03ba\u03ac")) // Greek
        assertTrue(LyricsEmphasis.supports("\u041f\u0440\u0438\u0432\u0435\u0442")) // Cyrillic
        assertTrue(LyricsEmphasis.supports("1234!"))
    }

    @Test
    fun joinedIdeographicAndRtlScriptsStayPlain() {
        assertFalse(LyricsEmphasis.supports("\u0645\u0631\u062d\u0628\u0627")) // Arabic, joined
        assertFalse(LyricsEmphasis.supports("\u4e16\u754c")) // Han ideographs
        assertFalse(LyricsEmphasis.supports("\u3053\u3093\u306b\u3061\u306f")) // Japanese kana
        assertFalse(LyricsEmphasis.supports("\u05e9\u05dc\u05d5\u05dd")) // Hebrew, RTL
        assertFalse(LyricsEmphasis.supports(""))
    }

    @Test
    fun theRippleIsAPureFunctionOfThePosition() {
        val word = 2_000L
        val duration = 1_500L
        val count = 5
        // Before the word and long past it, nothing bumps.
        assertEquals(0f, LyricsEmphasis.rippleAt(word, duration, count, 0, word - 1))
        assertEquals(0f, LyricsEmphasis.rippleAt(word, duration, count, 4, word + duration + duration))
        // The first letter peaks at mid-word; the last letter is delayed by the stagger.
        val firstPeak = LyricsEmphasis.rippleAt(word, duration, count, 0, word + duration / 2)
        assertTrue(firstPeak > 0.9f, "the first letter peaks mid-word, got $firstPeak")
        val stagger = (duration / 2.5f / count).toLong()
        val lastAtPeak = LyricsEmphasis.rippleAt(word, duration, count, 4, word + duration / 2)
        val lastLate = LyricsEmphasis.rippleAt(word, duration, count, 4, word + duration / 2 + stagger * 4)
        assertTrue(lastLate > lastAtPeak, "the last letter's bump runs behind the first's")
        // Out-of-range letters never bump.
        assertEquals(0f, LyricsEmphasis.rippleAt(word, duration, count, 5, word + duration / 2))
        assertEquals(0f, LyricsEmphasis.rippleAt(word, duration, 0, 0, word + duration / 2))
    }
}

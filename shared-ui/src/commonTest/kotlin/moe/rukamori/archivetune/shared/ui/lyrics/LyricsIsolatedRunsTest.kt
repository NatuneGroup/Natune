package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-letter safety net: a word in a script that joins its letters keeps the plain sweep, and so
 * does a safe-script word whose isolated runs no longer match the advances the line shaped — the sign
 * that a ligature or contextual form would be torn by cutting it into single-cluster runs.
 */
class LyricsIsolatedRunsTest {
    @Test
    fun joinedAndIndicScriptsEarnNoRuns() {
        assertFalse(LyricsEmphasis.supports("ܡܪܚܒܐ")) // Syriac, joined
        assertFalse(LyricsEmphasis.supports("नमस्ते")) // Devanagari conjuncts
        assertFalse(LyricsEmphasis.supports("தமிழ்")) // Tamil
        assertFalse(LyricsEmphasis.supports("สวัสดี")) // Thai
        assertFalse(LyricsEmphasis.supports("ជំរាបសួរ")) // Khmer
        assertTrue(LyricsEmphasis.supports("lingerie")) // Latin keeps the ripple
    }

    @Test
    fun runsWithinToleranceKeepPerLetterRuns() {
        val slices = floatArrayOf(0f, 8.2f, 8.2f, 15.7f, 15.7f, 22f)
        assertTrue(LyricsEmphasis.isolatedRunsMatchLine(slices, floatArrayOf(8.2f, 7.5f, 6.6f)))
        // Drift inside the tolerance is just rasterisation noise.
        assertTrue(LyricsEmphasis.isolatedRunsMatchLine(slices, floatArrayOf(8.2f, 7.9f, 6.6f)))
    }

    @Test
    fun aRunThatChangedAdvanceFallsBackToThePlainSweep() {
        // The isolated "fi" lost its ligature: the line gave the pair 7.5, alone it takes 9.
        val slices = floatArrayOf(0f, 8.2f, 8.2f, 15.7f)
        assertFalse(LyricsEmphasis.isolatedRunsMatchLine(slices, floatArrayOf(8.2f, 9f)))
        assertFalse(LyricsEmphasis.isolatedRunsMatchLine(slices, floatArrayOf(8.2f, 6.9f)))
    }

    @Test
    fun missingOrMismatchedSlicesNeverProduceRuns() {
        assertFalse(LyricsEmphasis.isolatedRunsMatchLine(null, floatArrayOf(1f)))
        assertFalse(LyricsEmphasis.isolatedRunsMatchLine(floatArrayOf(0f, 1f), floatArrayOf(1f, 1f)))
        assertTrue(LyricsEmphasis.isolatedRunsMatchLine(floatArrayOf(), floatArrayOf()))
    }
}

package moe.rukamori.archivetune.shared.domain.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Background vocals in the shared enhanced-LRC reader. TTML states the backing voice with
 * `ttm:role="x-bg"`; the enhanced-LRC providers the shared modules also read write it inline in round
 * brackets instead, and the words used to arrive as lead words — the backing line either took the lead
 * row over or was skipped.
 */
class EnhancedLrcBackgroundVocalsTest {
    private val stampedLine =
        "<00:10.000>hold <00:10.500>on <00:11.000>(gimme <00:11.400>some <00:11.800>day)"

    private val stampedBracketInsideWord = "<00:10.000>a <00:10.500>b(c <00:11.000>d"

    private val stampedLeadOnly = "<00:10.000>hold <00:10.500>on"

    private val stampedUnbalanced = "<00:10.000>hold <00:10.500>(gimme <00:11.000>some"

    @Test
    fun anInlineBracketedRunIsMarkedAsBackground() {
        val words = EnhancedLrcWords.extractWordTimestamps(stampedLine).orEmpty()
        assertEquals(5, words.size)
        assertEquals(listOf(false, false, true, true, true), words.map { it.isBackground })
        // The brackets stay: the renderer draws the source text, it never rewrites it.
        assertEquals("hold on (gimme some day)", words.joinToString("") { it.text })
    }

    @Test
    fun aBracketInsideAWordIsNotAVoice() {
        val words = EnhancedLrcWords.extractWordTimestamps(stampedBracketInsideWord).orEmpty()
        assertTrue(words.isNotEmpty())
        assertTrue(words.none { it.isBackground })
    }

    @Test
    fun plainLeadWordsAreUntouched() {
        val words = EnhancedLrcWords.extractWordTimestamps(stampedLeadOnly).orEmpty()
        assertEquals(listOf("hold ", "on"), words.map { it.text })
        assertTrue(words.none { it.isBackground })
    }

    @Test
    fun anUnbalancedOpenBracketMarksTheRestOfTheLine() {
        val words = EnhancedLrcWords.extractWordTimestamps(stampedUnbalanced).orEmpty()
        assertEquals(listOf(false, true, true), words.map { it.isBackground })
    }
}

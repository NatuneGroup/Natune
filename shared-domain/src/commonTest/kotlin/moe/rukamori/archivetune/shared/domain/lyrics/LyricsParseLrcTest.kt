/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsParseLrcTest {
    @Test
    fun standardLrcLinesBecomeSyncedLinesInTimeOrder() {
        val lines = LyricsParser.parse("[00:04.50]Second line\n[00:01.00]First line")

        assertEquals(listOf(1_000L, 4_500L), lines.map { it.time })
        assertEquals(listOf("First line", "Second line"), lines.map { it.text })
        assertTrue(lines.all { it is SyncedLine })
        assertTrue(lines.all { it.words == null })
    }

    @Test
    fun linesWithoutTimestampsAreIgnoredByTheLrcReader() {
        val lines = LyricsParser.parse("header without stamps\n[00:01.00]Hi\nfooter")

        assertEquals(listOf("Hi"), lines.map { it.text })
    }

    @Test
    fun multipleTimestampsOnOneLineYieldOneRowPerStamp() {
        val lines = LyricsParser.parse("[00:10.00][00:20.00]Chorus line")

        assertEquals(listOf(10_000L, 20_000L), lines.map { it.time })
        assertEquals(listOf("Chorus line", "Chorus line"), lines.map { it.text })
    }

    @Test
    fun blankSpacerRowsStayAsEmptySyncedLines() {
        val lines = LyricsParser.parse("[00:01.00] Intro\n[00:05.00]\n[00:07.00] Verse")

        assertEquals(listOf("Intro", "", "Verse"), lines.map { it.text })
        assertEquals(listOf(1_000L, 5_000L, 7_000L), lines.map { it.time })
        assertTrue(lines.all { it is SyncedLine })
    }

    @Test
    fun displayTextDropsEmptySpacerRows() {
        assertEquals(
            "Intro\nVerse",
            LyricsDetection.displayText("[00:01.00] Intro\n[00:05.00]\n[00:07.00] Verse"),
        )
    }

    @Test
    fun lrcDocumentIsLineSyncedButNotWordSynced() {
        val lrc = "[00:01.00] Intro\n[00:05.00] Verse"

        assertTrue(LyricsDetection.isLineSyncedLrc(lrc))
        assertTrue(!LyricsDetection.hasWordSyncedLyrics(lrc))
        assertNull(LyricsParser.parse(lrc).single { it.time == 5_000L }.words)
    }
}

package moe.rukamori.archivetune.shared.domain.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `[translatedLyrics]` section merge: re-stamping, stripping a prior section and the parse round-trip. */
class LrcTranslationMergeTest {
    private val source =
        "[ti:Song]\n" +
            "[00:01.00]First line\n" +
            "[00:05.50]Second line\n" +
            "[00:10.00]\n"

    @Test
    fun plainTranslationIsRestampedWithSourceTimes() {
        val merged = LrcTranslationMerge.mergeTranslationIntoLrc(source, listOf("Premiere ligne", "Deuxieme ligne", ""))
        assertEquals(
            "[ti:Song]\n" +
                "[00:01.00]First line\n" +
                "[00:05.50]Second line\n" +
                "[00:10.00]\n" +
                "[translatedLyrics]\n" +
                "[00:01.00]Premiere ligne\n" +
                "[00:05.50]Deuxieme ligne",
            merged,
        )
    }

    @Test
    fun priorSectionIsReplacedNotAccumulated() {
        val once = LrcTranslationMerge.mergeTranslationIntoLrc(source, listOf("A", "B"))!!
        val twice = LrcTranslationMerge.mergeTranslationIntoLrc(once, listOf("C", "D"))!!
        assertTrue(twice.indexOf("[translatedLyrics]") == twice.lastIndexOf("[translatedLyrics]"))
        assertTrue("[00:01.00]C" in twice && "[00:05.50]D" in twice && "A" !in twice.substringAfter("[translatedLyrics]"))
    }

    @Test
    fun mergedDocumentFoldsBackIntoProviderTranslation() {
        val merged = LrcTranslationMerge.mergeTranslationIntoLrc(source, listOf("Un", "Deux"))!!
        val lines = LyricsParser.parse(merged)
        assertEquals("First line", lines[0].text)
        assertEquals("Un", lines[0].providerTranslationText)
        assertEquals("Deux", lines[1].providerTranslationText)
    }

    @Test
    fun unsyncedSourceReturnsNull() {
        assertNull(LrcTranslationMerge.mergeTranslationIntoLrc("just plain\ntext", listOf("a")))
    }

    @Test
    fun foreignTranslatedLyricsSectionParses() {
        val foreign =
            "[00:01.00]First\n[00:05.00]Second\n" +
                "[translated_lyrics]\n[00:01.00]Eins\n[00:05.00]Zwei\n"
        val lines = LyricsParser.parse(foreign)
        assertEquals("Eins", lines[0].providerTranslationText)
        assertEquals("Zwei", lines[1].providerTranslationText)
    }

    @Test
    fun saveableTranslationKeepsARebuiltBilingualDocumentVerbatim() {
        // The translator rebuild keeps each source row and appends its translation at the same
        // timestamp; re-merging that result must not emit a second [translatedLyrics] section.
        val rebuilt =
            "[ti:Song]\n" +
                "[00:01.00]First line\n" +
                "[00:01.00]Premiere ligne\n" +
                "[00:05.50]Second line\n" +
                "[00:05.50]Deuxieme ligne\n" +
                "[00:10.00]\n"
        assertEquals(rebuilt, LrcTranslationMerge.saveableTranslation(source, rebuilt))
        val lines = LyricsParser.parse(LrcTranslationMerge.saveableTranslation(source, rebuilt))
        assertEquals("First line", lines[0].text)
        assertEquals("Premiere ligne", lines[0].providerTranslationText)
    }

    @Test
    fun saveableTranslationMergesAPlainTranslation() {
        val merged = LrcTranslationMerge.saveableTranslation(source, "Premiere ligne\nDeuxieme ligne")
        assertTrue("[translatedLyrics]" in merged)
        assertTrue("[00:01.00]Premiere ligne" in merged)
        assertTrue("[00:05.50]Deuxieme ligne" in merged)
    }

    @Test
    fun saveableTranslationKeepsAnAlreadyMergedDocumentVerbatim() {
        val once = LrcTranslationMerge.mergeTranslationIntoLrc(source, listOf("Un", "Deux"))!!
        assertEquals(once, LrcTranslationMerge.saveableTranslation(source, once))
    }

    @Test
    fun saveableTranslationFallsBackToTheResultWithoutASource() {
        assertEquals("plain translation", LrcTranslationMerge.saveableTranslation(null, "plain translation"))
        assertEquals(
            "plain translation",
            LrcTranslationMerge.saveableTranslation("unsynced source\ntext", "plain translation"),
        )
    }
}

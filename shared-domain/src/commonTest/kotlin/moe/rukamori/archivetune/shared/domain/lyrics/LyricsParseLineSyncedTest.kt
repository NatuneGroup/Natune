/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.domain.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins how a line-synced document is read when several rows share one timestamp.
 *
 * A repeated timestamp means two different things: a translation-annotated document (the AI
 * translation writes the translated row directly under the original with the same prefix), or a
 * plain LRC that puts two rows on one timestamp, such as an empty spacer row before a sung line.
 * Collapsing the second kind made both rows disappear (issue #1333).
 */
class LyricsParseLineSyncedTest {
    private val spacerAndSungLineAtOneTimestamp =
        """
        [00:16.50] E ho bisogno di amici che mi facciano stare bene
        [00:21.50]
        [00:21.50] Respiravo una cosa
        [00:24.30] Una cosa pericolosa
        """.trimIndent()

    @Test
    fun keepsBothRowsWhenAnEmptySpacerSharesTheTimestamp() {
        val entries = LyricsParser.parse(spacerAndSungLineAtOneTimestamp)

        assertEquals(
            listOf(
                "E ho bisogno di amici che mi facciano stare bene",
                "",
                "Respiravo una cosa",
                "Una cosa pericolosa",
            ),
            entries.map { it.text },
        )
        assertEquals(listOf(16_500L, 21_500L, 21_500L, 24_300L), entries.map { it.time })
    }

    @Test
    fun showsEverySungLineOfADocumentThatRepeatsATimestamp() {
        assertEquals(
            "E ho bisogno di amici che mi facciano stare bene\n" +
                "Respiravo una cosa\n" +
                "Una cosa pericolosa",
            LyricsDetection.displayText(spacerAndSungLineAtOneTimestamp),
        )
    }

    @Test
    fun foldsTheTranslatedRowUnderItsOriginal() {
        val entries = LyricsParser.parse("[00:16.50] Hello there\n[00:16.50] Hola")

        assertEquals(1, entries.size)
        assertEquals("Hello there", entries.single().text)
        assertEquals("Hola", entries.single().providerTranslationText)
    }

    @Test
    fun keepsRowsThatFollowASingleTranslationRow() {
        // Two translation rows at one timestamp is not a shape the AI pass produces; the second one
        // is a real line and must not be silently dropped.
        val entries = LyricsParser.parse("[00:16.50] Hello there\n[00:16.50] Hola\n[00:16.50] Salut")

        assertEquals(listOf("Hello there", "Salut"), entries.map { it.text })
        assertEquals("Hola", entries.first().providerTranslationText)
        assertNull(entries.last().providerTranslationText)
    }

    @Test
    fun translationFoldIsDetectedAsATranslation() {
        assertEquals(true, LyricsDetection.hasTranslation("[00:16.50] Hello there\n[00:16.50] Hola"))
        assertEquals(false, LyricsDetection.hasTranslation(spacerAndSungLineAtOneTimestamp.replace("[00:21.50]\n", "")))
    }
}

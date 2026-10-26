/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.queue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Characterizes restoring a legacy queue, which has no occurrence keys, onto a freshly loaded finite
 * playlist. The alignment is trusted only when every saved song matches one fresh occurrence, in order.
 */
class FinitePlaylistQueueRestoreTest {
    /** Index 0 is A (s1), 1 is B (s2), 2 is A again (s3), 3 is C (s4). */
    private val fresh =
        FinitePlaylistOccurrences.append(
            "PL1",
            emptyList<FinitePlaylistEntry<String>>(),
            listOf(song("A", "s1", 0), song("B", "s2", 1), song("A", "s3", 2), song("C", "s4", 3)),
        )

    private fun saved(item: String, videoId: String, setVideoId: String?): FinitePlaylistSong<String> =
        FinitePlaylistSong(item = item, videoId = videoId, setVideoId = setVideoId, originalIndex = null)

    private fun sourceOf(vararg songs: FinitePlaylistSong<String>): List<FinitePlaylistEntry<String>> =
        FinitePlaylistOccurrences.append("PL1", emptyList<FinitePlaylistEntry<String>>(), songs.toList())

    @Test
    fun uniqueSavedSongsRestoreOntoTheirFreshOccurrencesAndKeepUnmatchedSongsBetween() {
        val alignment =
            FinitePlaylistLegacyAlignment.align(
                saved = listOf(saved("saved-A", "A", "s1"), saved("saved-C", "C", "s4")),
                source = fresh,
            )!!

        assertEquals(listOf(0, 3), alignment.savedIndices)
        assertEquals(listOf("saved-A", "B", "A", "saved-C"), alignment.entries.map { it.item })
        assertEquals("PL1:set:s1", alignment.entries[0].key)
    }

    @Test
    fun aSavedDuplicateIsRestoredOntoItsOwnOccurrence() {
        val source = sourceOf(song("A", "s1", 0), song("A", "s3", 2))

        val alignment =
            FinitePlaylistLegacyAlignment.align(saved = listOf(saved("second", "A", "s3")), source = source)!!

        assertEquals(listOf(1), alignment.savedIndices)
        assertEquals(listOf("A", "second"), alignment.entries.map { it.item })
        assertEquals("PL1:set:s3", alignment.entries[1].key)
    }

    @Test
    fun userAddedSongsMayGoUnmatchedAndTakeAManualKey() {
        val source = sourceOf(song("A", "s1", 0), song("C", "s4", 3))

        val alignment =
            FinitePlaylistLegacyAlignment.align(
                saved = listOf(saved("A-saved", "A", "s1"), saved("user-added", "X", "sx"), saved("C-saved", "C", "s4")),
                source = source,
                manualIndices = setOf(1),
            )!!

        assertEquals(listOf(0, 1, 2), alignment.savedIndices)
        assertEquals(listOf("A-saved", "user-added", "C-saved"), alignment.entries.map { it.item })
        assertEquals("manual:1", alignment.entries[1].key)
        assertNull(alignment.entries[1].originalIndex)
    }

    @Test
    fun anAmbiguousFreshMatchFailsTheWholeAlignment() {
        val source = sourceOf(song("A"), song("A"))

        assertNull(FinitePlaylistLegacyAlignment.align(saved = listOf(saved("x", "A", null)), source = source))
    }

    @Test
    fun aSavedSongMissingFromTheFreshListFailsTheWholeAlignment() {
        assertNull(FinitePlaylistLegacyAlignment.align(saved = listOf(saved("z", "Z", "s9")), source = fresh))
    }

    @Test
    fun reorderedSavedSongsCannotBeProvenAndFailTheWholeAlignment() {
        assertNull(
            FinitePlaylistLegacyAlignment.align(
                saved = listOf(saved("C", "C", "s4"), saved("A", "A", "s1")),
                source = fresh,
            ),
        )
    }

    @Test
    fun anAlignmentWithNoProvenSongFails() {
        assertNull(
            FinitePlaylistLegacyAlignment.align(
                saved = listOf(saved("user", "X", "sx")),
                source = fresh,
                manualIndices = setOf(0),
            ),
        )
    }
}

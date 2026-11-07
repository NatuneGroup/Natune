package moe.rukamori.archivetune.shared.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

class LrcLibQueryTest {
    @Test
    fun featuredArtistParentheticalsAreCutFromTheTitle() {
        listOf(
            "Song (feat. X)",
            "Song (ft. X)",
            "Song (featuring X)",
            "Song [feat. X]",
            "Song [ft. X]",
            "Song (feat. X & Y)",
            "Song (with X)",
        ).forEach { decorated ->
            assertEquals("Song", cleanLrcLibTitle(decorated), decorated)
        }
    }

    @Test
    fun dashedFeaturedPartsAreCutFromTheTitle() {
        assertEquals("Song", cleanLrcLibTitle("Song - feat. X"))
        assertEquals("Song", cleanLrcLibTitle("Song – ft. X"))
        assertEquals("Song", cleanLrcLibTitle("Song — featuring X"))
        // A dash that introduces anything else stays; it is part of the title.
        assertEquals("Song - Part 2", cleanLrcLibTitle("Song - Part 2"))
    }

    @Test
    fun releaseNoiseIsCutFromTheTitle() {
        assertEquals("Song", cleanLrcLibTitle("Song (Official Video)"))
        assertEquals("Song", cleanLrcLibTitle("Song [Official Audio]"))
        assertEquals("Song", cleanLrcLibTitle("Song (Lyrics)"))
        assertEquals("Song", cleanLrcLibTitle("Song (Official Music Video) [HD]"))
        assertEquals("Song", cleanLrcLibTitle("Song (feat. X) (Official Video)"))
    }

    @Test
    fun versionParentheticalsStayBecauseTheyNameTheRecording() {
        assertEquals("Song (Live)", cleanLrcLibTitle("Song (Live)"))
        assertEquals("Song (Acoustic)", cleanLrcLibTitle("Song (Acoustic)"))
        assertEquals("Song (Remix)", cleanLrcLibTitle("Song (Remix)"))
    }

    @Test
    fun aPlainTitleIsUnchanged() {
        assertEquals("Song", cleanLrcLibTitle("Song"))
        assertEquals("Song (Reprise)", cleanLrcLibTitle("Song (Reprise)"))
    }

    @Test
    fun aTitleThatIsOnlyNoiseKeepsItselfRatherThanBecomingBlank() {
        assertEquals("(Official Video)", cleanLrcLibTitle("(Official Video)"))
    }

    @Test
    fun thePrimaryArtistIsTheFirstCredit() {
        assertEquals("A", cleanLrcLibArtist("A, B"))
        assertEquals("A", cleanLrcLibArtist("A, B & C"))
        assertEquals("A", cleanLrcLibArtist("A & B"))
        assertEquals("A", cleanLrcLibArtist("A feat. B"))
        assertEquals("A", cleanLrcLibArtist("A featuring B"))
        assertEquals("A", cleanLrcLibArtist("A ft. B"))
        assertEquals("A", cleanLrcLibArtist("A; B"))
    }

    @Test
    fun aSingleArtistIsUnchanged() {
        assertEquals("A", cleanLrcLibArtist("A"))
        assertEquals("Simon", cleanLrcLibArtist("Simon & Garfunkel"))
        assertEquals("AC/DC", cleanLrcLibArtist("AC/DC"))
    }

    @Test
    fun theExactQueryIsAlwaysFirst() {
        val queries = lrcLibQueries("Song (feat. X)", "A & B")

        assertEquals(listOf(LrcLibQuery("Song (feat. X)", "A & B"), LrcLibQuery("Song", "A")), queries)
    }

    @Test
    fun anUndecoratedPairSendsOneQuery() {
        assertEquals(listOf(LrcLibQuery("Song", "Artist")), lrcLibQueries("Song", "Artist"))
    }

    @Test
    fun aTitleAndArtistThatBothNeedCleaningAreRetriedTogether() {
        assertEquals(
            listOf(LrcLibQuery("Song [ft. Y]", "A, B"), LrcLibQuery("Song", "A")),
            lrcLibQueries("Song [ft. Y]", "A, B"),
        )
    }

    @Test
    fun aBlankTitleNeverBecomesAQuery() {
        assertEquals(emptyList(), lrcLibQueries("   ", "Artist"))
    }
}

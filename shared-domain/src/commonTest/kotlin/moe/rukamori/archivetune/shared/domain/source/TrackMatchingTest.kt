package moe.rukamori.archivetune.shared.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the catalog-search ranking of [TrackMatching] to the Android provider heuristic. Each test fixes one rule: token
 * overlap, version hard rejection, artist and duration evidence, the acceptance floor, or the title folding it compares
 * with. Expected scores are worked out from the Android formula, so a change to any weight shows up here.
 */
class TrackMatchingTest {
    @Test
    fun durationToleranceIsInclusiveAtFortyFiveSeconds() {
        assertTrue(TrackMatching.durationMatches(200_000L, 245_000L))
        assertFalse(TrackMatching.durationMatches(200_000L, 245_001L))
        assertTrue(TrackMatching.durationMatches(245_000L, 200_000L))
    }

    @Test
    fun missingRuntimeOnEitherSideNeverCountsAgainstTheCandidate() {
        assertTrue(TrackMatching.durationMatches(null, 300_000L))
        assertTrue(TrackMatching.durationMatches(300_000L, null))
        assertTrue(TrackMatching.durationMatches(null, null))
    }

    @Test
    fun durationAgreementAddsBonusAndDisagreementSubtractsPenalty() {
        // Title 100 and artist 60 come first. The duration bonus is +30 and the penalty is -40.
        assertEquals(190, score(wantedDurationMs = 200_000L, candidateDurationMs = 210_000L))
        assertEquals(120, score(wantedDurationMs = 200_000L, candidateDurationMs = 300_000L))
        assertEquals(160, score(wantedDurationMs = null, candidateDurationMs = 300_000L))
    }

    @Test
    fun versionWordOnOnlyOneSideIsAHardRejection() {
        assertEquals(Int.MIN_VALUE, score(candidateTitle = "song live"))
        assertEquals(Int.MIN_VALUE, score(wantedTitle = "song remix", candidateTitle = "song"))
    }

    @Test
    fun sameVersionWordOnBothSidesIsNotAMismatch() {
        assertEquals(160, score(wantedTitle = "song live", candidateTitle = "song live"))
    }

    @Test
    fun titleOverlapBelowHalfIsRejected() {
        // One shared token out of three gives 0.33, which is under the 0.5 floor.
        assertEquals(Int.MIN_VALUE, score(wantedTitle = "red blue green", candidateTitle = "red xx yy"))
    }

    @Test
    fun exactlyHalfTitleOverlapScoresFiftyBeforeArtistOrDurationEvidence() {
        assertEquals(
            50,
            score(
                wantedTitle = "red blue",
                candidateTitle = "red green",
                wantedArtists = emptyList(),
                candidateArtist = "",
            ),
        )
    }

    @Test
    fun stopWordsAndSingleLettersDoNotCountTowardOverlap() {
        assertEquals(
            100,
            score(
                wantedTitle = "the song a",
                candidateTitle = "song",
                wantedArtists = emptyList(),
                candidateArtist = "",
            ),
        )
    }

    @Test
    fun artistScoreUsesTheBestOfTheWantedArtists() {
        assertEquals(160, score(wantedArtists = listOf("other", "artist"), candidateArtist = "artist"))
    }

    @Test
    fun artistNamesCompareAfterAccentFolding() {
        val wantedArtist = TrackMatching.normalize("Beyoncé")
        val candidateArtist = TrackMatching.normalize("Beyonce")

        assertEquals(160, score(wantedArtists = listOf(wantedArtist), candidateArtist = candidateArtist))
    }

    @Test
    fun blankTitlesAreRejected() {
        assertEquals(Int.MIN_VALUE, score(wantedTitle = ""))
        assertEquals(Int.MIN_VALUE, score(candidateTitle = "   "))
    }

    @Test
    fun bestPrefersArtistAndDurationEvidence() {
        val target = target(title = "Song", artists = listOf("Artist"), durationMs = 200_000L)
        // Scores: other band 100 + 0 + 30 = 130, studio 100 + 60 + 30 = 190, long cut 100 + 60 - 40 = 120.
        val candidates =
            listOf(
                candidate("other-band", title = "Song", artists = listOf("Other Band"), durationMs = 200_000L),
                candidate("studio", title = "Song", artists = listOf("Artist"), durationMs = 200_000L),
                candidate("long-cut", title = "Song", artists = listOf("Artist"), durationMs = 400_000L),
            )

        assertEquals("studio", TrackMatching.best(target, candidates)?.id)
    }

    @Test
    fun bestReturnsTheEarlierCandidateOnATie() {
        val target = target(title = "Song", artists = listOf("Artist"), durationMs = null)
        val candidates =
            listOf(
                candidate("first", title = "Song", artists = listOf("Artist"), durationMs = null),
                candidate("second", title = "Song", artists = listOf("Artist"), durationMs = null),
            )

        assertEquals("first", TrackMatching.best(target, candidates)?.id)
    }

    @Test
    fun bestReturnsNullWhenNoCandidateReachesTheFloor() {
        // Title overlap of 0.5 scores 50, which is under MIN_MATCH_SCORE without artist or duration evidence.
        val target = target(title = "Red Blue", artists = emptyList(), durationMs = null)
        val candidates = listOf(candidate("green", title = "Red Green", artists = emptyList(), durationMs = null))

        assertNull(TrackMatching.best(target, candidates))
    }

    @Test
    fun bestRejectsLiveAndRemixVersionsOfTheWantedStudioTrack() {
        val target = target(title = "Song", artists = listOf("Artist"), durationMs = 200_000L)
        val candidates =
            listOf(
                candidate("live", title = "Song (Live)", artists = listOf("Artist"), durationMs = 200_000L),
                candidate("remix", title = "Song - Remix", artists = listOf("Artist"), durationMs = 200_000L),
            )

        assertNull(TrackMatching.best(target, candidates))
    }

    @Test
    fun explicitLabelDoesNotBlockAMatchAgainstTheCleanTitle() {
        val target = target(title = "Song", artists = listOf("Artist"), durationMs = null)
        val candidates =
            listOf(
                candidate("explicit", title = "Song (Explicit)", artists = listOf("Artist"), durationMs = null),
            )

        assertEquals("explicit", TrackMatching.best(target, candidates)?.id)
    }

    @Test
    fun normalizeTitleCutsFeaturedArtistsAndEditionWords() {
        assertEquals("song", TrackMatching.normalizeTitle("Song (feat. Someone) - Remastered 2009"))
        assertEquals("hello", TrackMatching.normalizeTitle("Hello (Explicit Version) Official Audio"))
    }

    @Test
    fun normalizeKeepsFeaturedWordsWhileNormalizeTitleCutsThem() {
        assertEquals("song feat someone", TrackMatching.normalize("Song (feat. Someone)"))
        assertEquals("song", TrackMatching.normalizeTitle("Song (feat. Someone)"))
    }

    @Test
    fun normalizeFoldsAccentsCaseAndPunctuation() {
        assertEquals("beyonce", TrackMatching.normalize("Beyoncé"))
        assertEquals("cafe", TrackMatching.normalize("CAFÉ"))
        assertEquals("rock roll", TrackMatching.normalize("  Rock & Roll!! "))
        assertEquals("", TrackMatching.normalize(null))
    }

    private fun score(
        wantedTitle: String = "song",
        wantedArtists: List<String> = listOf("artist"),
        candidateTitle: String = "song",
        candidateArtist: String = "artist",
        wantedDurationMs: Long? = null,
        candidateDurationMs: Long? = null,
    ): Int =
        TrackMatching.score(
            wantedTitle = wantedTitle,
            wantedArtists = wantedArtists,
            candidateTitle = candidateTitle,
            candidateArtist = candidateArtist,
            wantedDurationMs = wantedDurationMs,
            candidateDurationMs = candidateDurationMs,
        )

    private fun target(
        title: String,
        artists: List<String>,
        durationMs: Long?,
    ) = TrackMatching.Target(title = title, artists = artists, album = null, durationMs = durationMs)

    private fun candidate(
        id: String,
        title: String,
        artists: List<String>,
        durationMs: Long?,
    ) = TrackMatching.Candidate(id = id, title = title, artists = artists, album = null, durationMs = durationMs)
}

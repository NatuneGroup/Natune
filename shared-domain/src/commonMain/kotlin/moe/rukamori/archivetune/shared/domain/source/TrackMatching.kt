/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.source

import kotlin.math.abs

/**
 * Ranks a provider's catalog search hits against the track that was asked for. A hit must share at least half of the
 * title tokens, counted against the longer title. It must not differ by a version word such as live or remix, and it
 * must reach [MIN_MATCH_SCORE] once artist and duration evidence is added.
 *
 * Returning null from [best] is the intended outcome for a track the catalog does not carry. The caller then falls
 * through to the next source instead of playing a wrong recording. This only chooses a provider's own winner. Playback
 * still checks the matched metadata with [TitleMatch], so a provider cannot bypass that gate by scoring loosely here.
 */
object TrackMatching {
    /** Minimum score for a candidate to be accepted at all. */
    const val MIN_MATCH_SCORE = 60

    /** Ignored when comparing token sets, since they carry no identifying information. */
    private val STOP_WORDS = setOf("the", "a", "an", "of", "and", "feat", "ft", "featuring", "with")

    /**
     * Words that distinguish otherwise identically-titled recordings. A mismatch on any of these is a hard rejection,
     * because serving a live take for a studio track is worse than serving nothing and falling through to the next
     * source.
     */
    private val VERSION_TOKENS =
        setOf("remix", "acoustic", "live", "instrumental", "demo", "edit", "mix", "version")

    /** Runtimes this far apart are still considered the same recording. */
    private const val DURATION_TOLERANCE_MS = 45_000L

    /**
     * Scores [candidateTitle] and [candidateArtist] against what was wanted. Titles and artists must already be in the
     * form that [normalizeTitle] and [normalize] produce. Returns [Int.MIN_VALUE] for a hard rejection. Otherwise higher
     * is better, and callers should require [MIN_MATCH_SCORE].
     */
    fun score(
        wantedTitle: String,
        wantedArtists: List<String>,
        candidateTitle: String,
        candidateArtist: String,
        wantedDurationMs: Long?,
        candidateDurationMs: Long?,
    ): Int {
        if (wantedTitle.isBlank() || candidateTitle.isBlank()) return Int.MIN_VALUE
        if (hasVersionMismatch(" $wantedTitle ", " $candidateTitle ")) return Int.MIN_VALUE
        val titleOverlap = tokenOverlap(significantTokens(wantedTitle), significantTokens(candidateTitle))
        if (titleOverlap < 0.5) return Int.MIN_VALUE
        var score = (titleOverlap * 100).toInt()
        if (wantedArtists.isNotEmpty() && candidateArtist.isNotBlank()) {
            val artistOverlap =
                wantedArtists.maxOf { tokenOverlap(significantTokens(it), significantTokens(candidateArtist)) }
            score += (artistOverlap * 60).toInt()
        }
        if (wantedDurationMs != null && candidateDurationMs != null) {
            // A duration agreement is weak evidence but a disagreement is strong counter-evidence, so the penalty
            // deliberately outweighs the bonus.
            if (durationMatches(wantedDurationMs, candidateDurationMs)) score += 30 else score -= 40
        }
        return score
    }

    /** What the player asked for. */
    data class Target(
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
    )

    /**
     * One search hit from a provider's catalog, normalized so [best] can rank hits from any source. [id] is the
     * provider's own track identifier and is opaque here.
     */
    data class Candidate(
        val id: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
    )

    /**
     * Picks the highest-scoring candidate that clears [MIN_MATCH_SCORE], or null when none does. When two candidates
     * tie, the earlier one wins.
     */
    fun best(
        target: Target,
        candidates: List<Candidate>,
    ): Candidate? {
        val wantedTitle = normalizeTitle(target.title)
        val wantedArtists = target.artists.map { normalize(it) }
        return candidates
            .asSequence()
            .map { candidate ->
                candidate to
                    score(
                        wantedTitle = wantedTitle,
                        wantedArtists = wantedArtists,
                        candidateTitle = normalizeTitle(candidate.title),
                        candidateArtist = normalize(candidate.artists.firstOrNull()),
                        wantedDurationMs = target.durationMs,
                        candidateDurationMs = candidate.durationMs,
                    )
            }.filter { (_, score) -> score >= MIN_MATCH_SCORE }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    /**
     * Whether two runtimes are close enough to be the same recording. A missing runtime on either side never counts
     * against the candidate.
     */
    fun durationMatches(
        wantedDurationMs: Long?,
        candidateDurationMs: Long?,
    ): Boolean {
        if (wantedDurationMs == null || candidateDurationMs == null) return true
        return abs(wantedDurationMs - candidateDurationMs) <= DURATION_TOLERANCE_MS
    }

    /** Lowercased, accent-stripped, punctuation-collapsed form used for all token comparisons. */
    fun normalize(value: String?): String = TitleNormalization.fold(value)

    /** Strips featured-artist and edition noise that catalogs disagree about. */
    fun normalizeTitle(value: String): String = TitleNormalization.normalize(value)

    private fun significantTokens(value: String): Set<String> =
        value
            .split(' ')
            .map { it.trim() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()

    private fun tokenOverlap(
        wanted: Set<String>,
        candidate: Set<String>,
    ): Double {
        if (wanted.isEmpty() || candidate.isEmpty()) return 0.0
        val shared = wanted.intersect(candidate).size
        // Divide by the larger set so that a candidate padded with extra words cannot score a perfect overlap just by
        // containing every wanted token.
        return shared.toDouble() / wanted.size.coerceAtLeast(candidate.size).toDouble()
    }

    /** Expects both arguments padded with a space on each side, so a version token matches only as a whole word. */
    private fun hasVersionMismatch(
        wanted: String,
        candidate: String,
    ): Boolean = VERSION_TOKENS.any { token -> wanted.contains(" $token ") != candidate.contains(" $token ") }
}

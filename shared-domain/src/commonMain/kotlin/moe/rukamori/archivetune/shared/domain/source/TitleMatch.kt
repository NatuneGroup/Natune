/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.source

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Decides whether a provider's matched track is the track the user asked for. A lossless source can return a different
 * recording with the same title, so this combines title, artist, duration and album evidence and applies hard gates for a
 * different version (live, remix, ...) and for an implausible duration.
 *
 * Common code avoids regex and `java.text.Normalizer` on purpose. Splitting and folding are written by hand so every target
 * produces the same result for the same input.
 */
object TitleMatch {
    const val ACCEPT_THRESHOLD = 0.78
    private const val TITLE_ONLY_THRESHOLD = 0.95
    private const val MIN_TITLE_WITH_METADATA = 0.84
    private const val MIN_ARTIST = 0.72
    private const val DURATION_HARD_GATE_MS = 15_000L

    private val titleSeparators = charArrayOf('-', '/', '|', ':', '\uFF0D', '\uFF0F', '\uFF5C', '\uFF1A')
    private const val ARTIST_SYMBOL_SEPARATORS = ",;&/"
    private val artistWordSeparators = listOf("and", "x")
    private val versionMarkers =
        listOf(
            "live", "concert", "remix", "rework", "acoustic", "instrumental", "karaoke",
            "cover", "demo", "sped up", "nightcore", "slowed", "extended", "radio edit",
        )

    data class Result(
        val accepted: Boolean,
        val score: Double,
        val title: Double,
        val artist: Double?,
        val duration: Double?,
        val reason: String,
    )

    /**
     * Folds a title for comparison: lowercase, canonical decomposition with combining marks dropped, letters and digits
     * kept, then feature and version words removed. Non-Latin letters survive, so CJK titles never fold to an empty string.
     */
    fun normalize(value: String?): String = TitleNormalization.normalize(value)

    /**
     * Title similarity in 0.0..1.0 between [wanted] and [candidate], using Jaro-Winkler. Either side blank scores 0.0, so
     * missing metadata never passes the gate.
     */
    fun ratio(
        wanted: String?,
        candidate: String?,
    ): Double {
        val a = normalize(wanted)
        val b = normalize(candidate)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val direct = jaroWinkler(a, b)
        // Segments come from the raw titles, before folding, because folding turns " - " into a space and the boundary
        // between an original and a translated name would be lost. The best score across the whole and per-segment
        // comparisons wins, so "Forget it" and "忘れてください - Forget it" match in both directions.
        val wantedSegments = segmentsOf(wanted)
        val candidateSegments = segmentsOf(candidate)
        val bestWantedSegment = wantedSegments.maxOfOrNull { jaroWinkler(it, b) } ?: 0.0
        val bestCandidateSegment = candidateSegments.maxOfOrNull { jaroWinkler(a, it) } ?: 0.0
        val bestCrossSegment =
            wantedSegments.maxOfOrNull { wantedSegment ->
                candidateSegments.maxOfOrNull { jaroWinkler(wantedSegment, it) } ?: 0.0
            } ?: 0.0
        return maxOf(direct, bestWantedSegment, bestCandidateSegment, bestCrossSegment)
    }

    /**
     * Decides whether [stream] is the wanted track. The gates run in a fixed order, so a rejection reports the first
     * cause that applies: missing metadata, version, artist, duration, then the title and composite score.
     */
    fun evaluate(
        wantedTitle: String,
        wantedArtists: List<String>,
        wantedAlbum: String?,
        wantedDurationMs: Long?,
        stream: DirectStream,
    ): Result {
        if (stream.trustedDirectId) return Result(true, 1.0, 1.0, 1.0, 1.0, "trusted catalog id")
        val candidateTitle =
            stream.matchedTitle
                ?: return Result(false, 0.0, 0.0, null, null, "provider returned no matched metadata")
        if (hasVersionMismatch(wantedTitle, candidateTitle)) {
            return Result(false, 0.0, ratio(wantedTitle, candidateTitle), null, null, "version mismatch")
        }

        val titleScore = ratio(wantedTitle, candidateTitle)
        val durationScore = durationScore(wantedDurationMs, stream.matchedDurationMs)

        val wantedArtist = wantedArtists.joinToString(", ").takeIf { it.isNotBlank() }
        val candidateArtist = stream.matchedArtist?.takeIf { it.isNotBlank() }
        val artistScore =
            if (wantedArtist != null && candidateArtist != null) artistRatio(wantedArtist, candidateArtist) else null

        // The artist gate runs before the duration gate. Both reject, so this changes only the reported reason: a
        // same-title recording by another artist usually differs in length too, and the artist is the real cause.
        if (artistScore != null && artistScore < MIN_ARTIST) {
            return Result(false, 0.0, titleScore, artistScore, durationScore, "artist mismatch")
        }
        if (durationScore == 0.0) {
            return Result(false, 0.0, titleScore, artistScore, durationScore, "duration differs by more than 15s")
        }

        // Runs after the duration gate. With no artist to compare, duration is the only independent signal left, so a
        // title-only acceptance must never override an implausible length.
        if (artistScore == null) {
            val accepted = titleScore >= TITLE_ONLY_THRESHOLD
            val score = titleScore * 0.8 + (durationScore ?: 0.5) * 0.2
            val reason = if (accepted) "strict title fallback" else "artist metadata unavailable"
            return Result(accepted, score, titleScore, null, durationScore, reason)
        }
        if (titleScore < MIN_TITLE_WITH_METADATA && !containsTokenRun(candidateTitle, wantedTitle)) {
            return Result(false, 0.0, titleScore, artistScore, durationScore, "title mismatch")
        }

        val albumScore = albumRatio(wantedAlbum, stream.matchedAlbum)
        val score =
            titleScore * 0.45 +
                artistScore * 0.30 +
                (durationScore ?: 0.5) * 0.20 +
                (albumScore ?: 0.5) * 0.05
        val accepted = score >= ACCEPT_THRESHOLD
        val reason = if (accepted) "metadata match" else "composite score too low"
        return Result(accepted, score, titleScore, artistScore, durationScore, reason)
    }

    private fun durationScore(
        wantedMs: Long?,
        candidateMs: Long?,
    ): Double? {
        if (wantedMs == null || wantedMs <= 0L || candidateMs == null || candidateMs <= 0L) return null
        val difference = abs(wantedMs - candidateMs)
        return when {
            difference > DURATION_HARD_GATE_MS -> 0.0
            difference <= 3_000L -> 1.0
            difference <= 10_000L -> 0.8
            else -> 0.6
        }
    }

    private fun albumRatio(
        wanted: String?,
        candidate: String?,
    ): Double? {
        if (wanted.isNullOrBlank() || candidate.isNullOrBlank()) return null
        return ratio(wanted, candidate)
    }

    private fun artistRatio(
        wanted: String,
        candidate: String,
    ): Double {
        val wantedParts = artistParts(wanted)
        val candidateParts = artistParts(candidate.replace(" - Topic", "", ignoreCase = true))
        if (wantedParts.isEmpty() || candidateParts.isEmpty()) return 0.0
        return wantedParts.maxOf { target ->
            candidateParts.maxOf { option ->
                if (target == option || (target.length >= 4 && option.length >= 4 && (target in option || option in target))) {
                    1.0
                } else {
                    jaroWinkler(target, option)
                }
            }
        }
    }

    private fun artistParts(value: String): List<String> =
        splitArtistNames(value).map { normalize(it) }.filter { it.isNotBlank() }

    /** Splits like the regex `\s*[,;&/]\s*|\s+(?:and|x)\s+` (case-insensitive), without a regex. */
    private fun splitArtistNames(value: String): List<String> {
        val names = mutableListOf<String>()
        var segmentStart = 0
        var index = 0
        while (index < value.length) {
            val separatorEnd = artistSeparatorEnd(value, index)
            if (separatorEnd > index) {
                names += value.substring(segmentStart, index)
                index = separatorEnd
                segmentStart = index
            } else {
                index++
            }
        }
        names += value.substring(segmentStart)
        return names
    }

    /** End of the artist separator that starts at [start], or [start] when no separator starts there. */
    private fun artistSeparatorEnd(
        value: String,
        start: Int,
    ): Int {
        val symbolAt = skipRegexWhitespace(value, start)
        if (symbolAt < value.length && value[symbolAt] in ARTIST_SYMBOL_SEPARATORS) {
            return skipRegexWhitespace(value, symbolAt + 1)
        }
        val wordAt = skipRegexWhitespace(value, start)
        if (wordAt == start) return start
        for (word in artistWordSeparators) {
            val end = wordAt + word.length
            if (end < value.length &&
                value.substring(wordAt, end).equals(word, ignoreCase = true) &&
                isRegexWhitespace(value[end])
            ) {
                return skipRegexWhitespace(value, end)
            }
        }
        return start
    }

    private fun skipRegexWhitespace(
        value: String,
        start: Int,
    ): Int {
        var index = start
        while (index < value.length && isRegexWhitespace(value[index])) index++
        return index
    }

    /** The whitespace set of the Java regex `\s`, which is narrower than Kotlin's `isWhitespace`. */
    private fun isRegexWhitespace(c: Char): Boolean = c == ' ' || c in '\t'..'\r'

    /**
     * Folded alternative names of a raw title, each at least three characters long. A title with fewer than two
     * non-blank separator-delimited parts has none.
     */
    private fun segmentsOf(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val parts = raw.split(*titleSeparators).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= 1) return emptyList()
        return parts.map { normalize(it) }.filter { it.length >= 3 }
    }

    private fun containsTokenRun(
        haystack: String,
        needle: String,
    ): Boolean {
        val target = normalize(needle)
        val candidate = normalize(haystack)
        if (target.length < 3) return false
        if (candidate.contains(target)) return true
        // A needle such as "忘れてください - Forget it" also matches a candidate that equals or contains any one of its
        // segments, in either language.
        return segmentsOf(needle).any { segment -> candidate.contains(segment) }
    }

    private fun hasVersionMismatch(
        wanted: String,
        candidate: String,
    ): Boolean {
        val target = " ${normalize(wanted)} "
        val option = " ${normalize(candidate)} "
        return versionMarkers.any { marker ->
            val token = " ${normalize(marker)} "
            (token in target) != (token in option)
        }
    }

    private fun jaroWinkler(
        a: String,
        b: String,
    ): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val distance = max(a.length, b.length) / 2 - 1
        if (distance < 0) return 0.0
        val aMatches = BooleanArray(a.length)
        val bMatches = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            for (j in max(0, i - distance) until min(i + distance + 1, b.length)) {
                if (bMatches[j] || a[i] != b[j]) continue
                aMatches[i] = true
                bMatches[j] = true
                matches++
                break
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var j = 0
        for (i in a.indices) {
            if (!aMatches[i]) continue
            while (!bMatches[j]) j++
            if (a[i] != b[j]) transpositions++
            j++
        }
        val jaro =
            (matches.toDouble() / a.length +
                matches.toDouble() / b.length +
                (matches - transpositions / 2.0) / matches) / 3.0
        var prefix = 0
        while (prefix < min(4, min(a.length, b.length)) && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * 0.1 * (1.0 - jaro)
    }
}

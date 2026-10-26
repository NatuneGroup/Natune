package moe.rukamori.archivetune.shared.data.lyrics

import kotlin.math.abs

/** LRCLIB durations are whole seconds; a candidate within this many seconds counts as the same track. */
internal const val LRCLIB_DURATION_TOLERANCE_SECONDS = 2

/**
 * Duration-first selection, or with duration -1 the best title/artist match. Two similarity
 * functions exist on purpose: the match scorer and the search-result sorter are separate
 * Android heuristics, and merging them would change which lyrics win.
 */
internal fun List<LrcLibTrack>.bestMatchingFor(
    duration: Int,
    trackName: String,
    artistName: String,
): LrcLibTrack? {
    if (isEmpty()) return null
    if (duration == -1) return findBestMatch(trackName, artistName)
    return minByOrNull { abs(it.duration.toInt() - duration) }
        ?.takeIf { abs(it.duration.toInt() - duration) <= LRCLIB_DURATION_TOLERANCE_SECONDS }
}

private fun List<LrcLibTrack>.findBestMatch(
    trackName: String,
    artistName: String,
): LrcLibTrack? {
    val normalizedTrackName = trackName.trim().lowercase()
    val normalizedArtistName = artistName.trim().lowercase()

    return maxByOrNull { track ->
        val trackNameSimilarity = trackSimilarity(normalizedTrackName, track.trackName.trim().lowercase())
        val artistNameSimilarity = trackSimilarity(normalizedArtistName, track.artistName.trim().lowercase())
        var score = (trackNameSimilarity + artistNameSimilarity) / 2.0
        // Synced lyrics break ties in favour of the richer format.
        if (track.syncedLyrics != null) score += 0.1
        score
    }?.takeIf { track ->
        val trackNameSimilarity = trackSimilarity(normalizedTrackName, track.trackName.trim().lowercase())
        val artistNameSimilarity = trackSimilarity(normalizedArtistName, track.artistName.trim().lowercase())
        (trackNameSimilarity + artistNameSimilarity) / 2.0 > 0.6
    }
}

/** Score used by the match picker. A containment hit sets a 0.8 floor over the edit-distance score. */
private fun trackSimilarity(
    str1: String,
    str2: String,
): Double {
    if (str1 == str2) return 1.0
    if (str1.isEmpty() || str2.isEmpty()) return 0.0

    val containsScore =
        when {
            str1.contains(str2) || str2.contains(str1) -> 0.8
            else -> 0.0
        }

    val maxLength = maxOf(str1.length, str2.length)
    val distance = levenshteinDistance(str1, str2)
    val distanceScore = 1.0 - (distance.toDouble() / maxLength)

    return maxOf(containsScore, distanceScore)
}

/** Score used to order search results before duration filtering; containment replaces edit distance. */
internal fun calculateStringSimilarity(
    str1: String,
    str2: String,
): Double {
    val s1 = str1.trim().lowercase()
    val s2 = str2.trim().lowercase()

    if (s1 == s2) return 1.0
    if (s1.isEmpty() || s2.isEmpty()) return 0.0

    return when {
        s1.contains(s2) || s2.contains(s1) -> 0.8
        else -> {
            val maxLength = maxOf(s1.length, s2.length)
            val distance = levenshteinDistance(s1, s2)
            1.0 - (distance.toDouble() / maxLength)
        }
    }
}

private fun levenshteinDistance(
    str1: String,
    str2: String,
): Int {
    val len1 = str1.length
    val len2 = str2.length
    val matrix = Array(len1 + 1) { IntArray(len2 + 1) }

    for (i in 0..len1) matrix[i][0] = i
    for (j in 0..len2) matrix[0][j] = j

    for (i in 1..len1) {
        for (j in 1..len2) {
            val cost = if (str1[i - 1] == str2[j - 1]) 0 else 1
            matrix[i][j] =
                minOf(
                    matrix[i - 1][j] + 1,
                    matrix[i][j - 1] + 1,
                    matrix[i - 1][j - 1] + cost,
                )
        }
    }

    return matrix[len1][len2]
}

package moe.rukamori.archivetune.shared.domain.recognition

/**
 * Capture parameters every platform adapter must honor. The fingerprint input has to be identical on every target,
 * so adapters record at exactly this rate and length instead of choosing their own.
 */
object RecognitionCaptureSpec {
    const val SAMPLE_RATE_HZ: Int = 16_000
    const val DURATION_MILLIS: Long = 8_400L
}

/** A match resolved by the matcher. Optional values stay as the matcher returned them; presentation drops blanks. */
data class RecognizedTrack(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val coverArtUrl: String?,
    val coverArtHqUrl: String?,
    val genre: String?,
    val releaseDate: String?,
    val label: String?,
    val lyrics: List<String>,
    val shazamUrl: String?,
    val isrc: String?,
) {
    /** The identity used by the details route: the track id, or the normalized title, artist and ISRC when it is blank. */
    val stableKey: String
        get() = recognitionStableKey(trackId, title, artist, isrc)

    val searchQuery: String
        get() = recognitionSearchQuery(title, artist)
}

/**
 * One saved recognition. Label and lyrics were never persisted by the baseline history record, so they are not
 * modeled here either; a saved result reopens without them.
 */
data class RecognitionHistoryEntry(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val coverArtUrl: String?,
    val coverArtHqUrl: String?,
    val genre: String?,
    val releaseDate: String?,
    val shazamUrl: String?,
    val isrc: String?,
    val recognizedAtEpochMillis: Long,
) {
    val stableKey: String
        get() = recognitionStableKey(trackId, title, artist, isrc)

    val searchQuery: String
        get() = recognitionSearchQuery(title, artist)
}

/** Why a recognition attempt produced no result. [PermissionRequired] is an owner outcome; the rest come from the pipeline. */
enum class MusicRecognitionFailure {
    PermissionRequired,
    NoMatch,
    RecordingFailed,
    SignatureFailed,
    RecognitionFailed,
}

enum class RecognitionPhase {
    Listening,
    Processing,
}

fun recognitionStableKey(
    trackId: String,
    title: String,
    artist: String,
    isrc: String?,
): String =
    trackId.takeIf { it.isNotBlank() }
        ?: listOf(title, artist, isrc.orEmpty()).joinToString("|") { it.trim().lowercase() }

fun recognitionSearchQuery(
    title: String,
    artist: String,
): String = "$title $artist".trim()

fun RecognizedTrack.toHistoryEntry(recognizedAtEpochMillis: Long): RecognitionHistoryEntry =
    RecognitionHistoryEntry(
        trackId = trackId,
        title = title,
        artist = artist,
        album = album,
        coverArtUrl = coverArtUrl,
        coverArtHqUrl = coverArtHqUrl,
        genre = genre,
        releaseDate = releaseDate,
        shazamUrl = shazamUrl,
        isrc = isrc,
        recognizedAtEpochMillis = recognizedAtEpochMillis,
    )

/** Rebuilds the details of a saved result. Label and lyrics were not saved, so they stay absent. */
fun RecognitionHistoryEntry.toRecognizedTrack(): RecognizedTrack =
    RecognizedTrack(
        trackId = trackId,
        title = title,
        artist = artist,
        album = album,
        coverArtUrl = coverArtUrl,
        coverArtHqUrl = coverArtHqUrl,
        genre = genre,
        releaseDate = releaseDate,
        label = null,
        lyrics = emptyList(),
        shazamUrl = shazamUrl,
        isrc = isrc,
    )

/**
 * The baseline matching policy: a trimmed, case-insensitive containment check over the descriptive fields. An empty
 * query keeps every entry in its given order.
 */
fun filterRecognitionHistory(
    history: List<RecognitionHistoryEntry>,
    query: String,
): List<RecognitionHistoryEntry> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return history

    return history.filter { entry ->
        listOf(
            entry.title,
            entry.artist,
            entry.album,
            entry.genre,
            entry.releaseDate,
            entry.isrc,
        ).filterNotNull().any { value -> value.contains(normalizedQuery, ignoreCase = true) }
    }
}

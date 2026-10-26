package moe.rukamori.archivetune.shared.data.recognition

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionHistoryEntry
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionHistoryLoad

/** Thrown when a stored history value exists but does not decode. An edit must not read that as an empty history. */
class RecognitionHistoryUnreadableException(
    cause: Throwable,
) : IllegalStateException("Stored music recognition history cannot be decoded", cause)

/**
 * Reads the stored history value. An absent or blank value is an empty history, as the baseline read it. A value that
 * does not decode is reported as [RecognitionHistoryLoad.Unavailable]; reporting it as empty would let the next save
 * replace data that is still on disk.
 */
fun decodeRecognitionHistory(raw: String?): RecognitionHistoryLoad =
    try {
        RecognitionHistoryLoad.Loaded(decodeRecognitionHistoryOrThrow(raw))
    } catch (unreadable: RecognitionHistoryUnreadableException) {
        RecognitionHistoryLoad.Unavailable(unreadable.cause)
    }

/**
 * Decodes the stored value for a read-modify-write. It throws instead of returning an empty list, so an edit cannot
 * write back over a value it failed to read.
 */
fun decodeRecognitionHistoryOrThrow(raw: String?): List<RecognitionHistoryEntry> {
    val text = raw?.takeIf { it.isNotBlank() } ?: return emptyList()
    val records =
        try {
            HistoryJson.decodeFromString<List<StoredRecognitionHistoryEntry>>(text)
        } catch (failure: Exception) {
            throw RecognitionHistoryUnreadableException(failure)
        }
    return records.map { it.toDomain() }
}

/** The same JSON array the baseline wrote, so a value saved by either side is read by the other. */
fun encodeRecognitionHistory(entries: List<RecognitionHistoryEntry>): String =
    HistoryJson.encodeToString(entries.map { it.toStored() })

/**
 * The baseline's stored record. Field names, nullability and the absence of defaults match it exactly, because that is
 * what keeps history saved before the shared store existed readable.
 */
@Serializable
private data class StoredRecognitionHistoryEntry(
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
)

/** Extra keys are tolerated on read, as the baseline tolerated them, so a value with additional fields still loads. */
private val HistoryJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

private fun StoredRecognitionHistoryEntry.toDomain(): RecognitionHistoryEntry =
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

private fun RecognitionHistoryEntry.toStored(): StoredRecognitionHistoryEntry =
    StoredRecognitionHistoryEntry(
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

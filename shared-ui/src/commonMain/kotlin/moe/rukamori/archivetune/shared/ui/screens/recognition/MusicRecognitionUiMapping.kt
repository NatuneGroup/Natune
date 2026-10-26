package moe.rukamori.archivetune.shared.ui.screens.recognition

import moe.rukamori.archivetune.shared.data.recognition.MusicRecognitionOwnerState
import moe.rukamori.archivetune.shared.data.recognition.RecognitionStatus
import moe.rukamori.archivetune.shared.domain.recognition.MusicRecognitionFailure
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionHistoryEntry
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionPhase
import moe.rukamori.archivetune.shared.domain.recognition.RecognizedTrack
import moe.rukamori.archivetune.shared.ui.screens.MusicRecognitionUi
import moe.rukamori.archivetune.shared.ui.screens.RecognitionErrorUi
import moe.rukamori.archivetune.shared.ui.screens.RecognitionHistoryItemUi
import moe.rukamori.archivetune.shared.ui.screens.RecognitionPhaseUi
import moe.rukamori.archivetune.shared.ui.screens.RecognitionStatusUi
import moe.rukamori.archivetune.shared.ui.screens.RecognizedTrackUi

/** Formats a saved recognition time for the device locale, for example a medium date with a short time. */
fun interface RecognitionTimeFormatter {
    fun format(epochMillis: Long): String
}

/**
 * Maps the owner's state to the screen model. This is presentation only: it never decides an outcome, and the screen
 * renders the filtered list as given rather than filtering again.
 */
fun MusicRecognitionOwnerState.toMusicRecognitionUi(
    formatter: RecognitionTimeFormatter,
    reducedMotion: Boolean,
): MusicRecognitionUi =
    MusicRecognitionUi(
        status = status.toStatusUi(),
        history = history.map { entry -> entry.toHistoryItemUi(formatter) },
        filteredHistory = filteredHistory.map { entry -> entry.toHistoryItemUi(formatter) },
        historyVisible = historyVisible,
        historyQuery = historyQuery,
        settingsVisible = settingsVisible,
        backgroundRecognitionEnabled = backgroundRecognitionEnabled,
        backgroundRecognitionAvailable = backgroundRecognitionAvailable,
        reducedMotion = reducedMotion,
        historyUnavailable = historyUnavailable,
        backgroundRecognitionSaveFailed = backgroundRecognitionSaveFailed,
    )

/**
 * Maps a resolved match to the details model. Blank optional values are dropped and the high-quality artwork is
 * preferred, as the baseline presentation did.
 */
fun RecognizedTrack.toRecognizedTrackUi(): RecognizedTrackUi =
    RecognizedTrackUi(
        stableKey = stableKey,
        title = title,
        artist = artist,
        album = album,
        artworkUrl = coverArtHqUrl ?: coverArtUrl,
        metadata = listOfNotNull(genre, releaseDate).filter(String::isNotBlank).joinToString(" • "),
        label = label?.takeIf(String::isNotBlank),
        lyricsPreview = lyrics.take(LYRICS_PREVIEW_LINES).takeIf { it.isNotEmpty() }?.joinToString("\n"),
        shazamUrl = shazamUrl?.takeIf(String::isNotBlank),
        isrc = isrc?.takeIf(String::isNotBlank),
        searchQuery = searchQuery,
    )

private const val LYRICS_PREVIEW_LINES = 6

private fun RecognitionHistoryEntry.toHistoryItemUi(formatter: RecognitionTimeFormatter): RecognitionHistoryItemUi =
    RecognitionHistoryItemUi(
        stableKey = stableKey,
        title = title,
        artist = artist,
        artworkUrl = coverArtHqUrl ?: coverArtUrl,
        metadata = listOfNotNull(album, genre, releaseDate).filter(String::isNotBlank).joinToString(" • "),
        recognizedAt = formatter.format(recognizedAtEpochMillis),
        shazamUrl = shazamUrl?.takeIf(String::isNotBlank),
        searchQuery = searchQuery,
    )

private fun RecognitionStatus.toStatusUi(): RecognitionStatusUi =
    when (this) {
        RecognitionStatus.Idle -> RecognitionStatusUi.Idle
        is RecognitionStatus.Running -> RecognitionStatusUi.Running(phase.toPhaseUi())
        is RecognitionStatus.Recognized -> RecognitionStatusUi.Recognized(track.toRecognizedTrackUi())
        is RecognitionStatus.Failed -> RecognitionStatusUi.Failed(failure.toErrorUi())
    }

private fun RecognitionPhase.toPhaseUi(): RecognitionPhaseUi =
    when (this) {
        RecognitionPhase.Listening -> RecognitionPhaseUi.LISTENING
        RecognitionPhase.Processing -> RecognitionPhaseUi.PROCESSING
    }

private fun MusicRecognitionFailure.toErrorUi(): RecognitionErrorUi =
    when (this) {
        MusicRecognitionFailure.PermissionRequired -> RecognitionErrorUi.PERMISSION_REQUIRED
        MusicRecognitionFailure.NoMatch -> RecognitionErrorUi.NO_MATCH
        MusicRecognitionFailure.RecordingFailed -> RecognitionErrorUi.RECORDING_FAILED
        MusicRecognitionFailure.SignatureFailed -> RecognitionErrorUi.SIGNATURE_FAILED
        MusicRecognitionFailure.RecognitionFailed -> RecognitionErrorUi.RECOGNITION_FAILED
    }

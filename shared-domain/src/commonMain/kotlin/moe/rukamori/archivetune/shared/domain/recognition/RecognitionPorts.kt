/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.recognition

import kotlinx.coroutines.flow.Flow

/**
 * Microphone or device-playback capture. Adapters own the audio device, its permission state and buffer sizing; the
 * owner only sees PCM and failures.
 */
interface RecognitionCapturePort {
    /**
     * Mono PCM16 at [RecognitionCaptureSpec.SAMPLE_RATE_HZ] covering [RecognitionCaptureSpec.DURATION_MILLIS].
     * Returns an empty array when nothing was captured. Throws when recording cannot start or read. Cancellation
     * must propagate.
     */
    suspend fun captureSamples(): ShortArray
}

/** One match attempt. Each adapter classifies its own outcomes; no caller infers no-match from error text. */
sealed interface RecognitionMatchOutcome {
    data class Matched(val track: RecognizedTrack) : RecognitionMatchOutcome

    /** A fingerprint was produced and the catalog had no match. */
    data object NoMatch : RecognitionMatchOutcome

    /** The captured audio did not yield a fingerprint. */
    data object SignatureUnavailable : RecognitionMatchOutcome
}

/** Fingerprinting and catalog matching. Throws only for transport or service failure; cancellation must propagate. */
interface RecognitionMatchPort {
    suspend fun match(samples: ShortArray): RecognitionMatchOutcome
}

/**
 * Fingerprint production for [RecognitionMatchPort]. Returns the Shazam DejaVu signature URI for the mono PCM16 at
 * [RecognitionCaptureSpec.SAMPLE_RATE_HZ], or null when the audio yields no fingerprint. The URI keeps the DejaVu
 * header, from which the matcher reads the signed sample count. Platform producers own their native code and any
 * dispatching; failures throw and cancellation must propagate.
 */
interface RecognitionSignaturePort {
    suspend fun generateSignature(samples: ShortArray): String?
}

interface RecognitionPermissionPort {
    /** True when the microphone can be used now, prompting the user first when the platform requires it. */
    suspend fun requestMicrophonePermission(): Boolean
}

/** The persisted history as the store reports it. An unreadable store is reported, never an empty success. */
sealed interface RecognitionHistoryLoad {
    data class Loaded(val entries: List<RecognitionHistoryEntry>) : RecognitionHistoryLoad

    data class Unavailable(val cause: Throwable?) : RecognitionHistoryLoad
}

interface RecognitionHistoryPort {
    /** Emits the persisted entries, or [RecognitionHistoryLoad.Unavailable] when the store cannot be read. */
    fun observe(): Flow<RecognitionHistoryLoad>

    /**
     * Applies [transform] to the persisted list in one atomic edit. Throws when the store cannot be read or written.
     * An undecodable stored value must make this throw without writing, so saved data is never overwritten silently.
     */
    suspend fun edit(transform: (List<RecognitionHistoryEntry>) -> List<RecognitionHistoryEntry>)
}

interface RecognitionBackgroundSettingPort {
    /** The persisted value. An absent value reads as enabled, matching the baseline default. */
    fun observeEnabled(): Flow<Boolean>

    /** Throws when the value cannot be saved. */
    suspend fun setEnabled(enabled: Boolean)
}

/** Wall-clock time for the recognition timestamp. The owner never reads the system clock directly. */
fun interface RecognitionClock {
    fun nowEpochMillis(): Long
}

/** Local diagnostics for failures the owner absorbs into state. Hosts log here; nothing is transmitted. */
fun interface RecognitionDiagnostics {
    fun report(
        message: String,
        cause: Throwable?,
    )
}

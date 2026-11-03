package moe.rukamori.archivetune.shared.domain.dsp.acoustic

/**
 * The acoustic quality audit models, shared by every host. An audit decodes a bounded excerpt of the
 * current source into PCM, renders a spectral heat grid, and reports a heuristic suspicion verdict —
 * never provenance proof.
 *
 * Ported from the Android `acoustics` package; the source resolver and decoder stay host code, since
 * they differ per platform.
 */

/**
 * What an audit runs on. [mediaId] names the song; [requestKey] binds the audit to a source identity:
 * a queue occurrence id, or the session graph's occurrence#attempt key which also pins the load
 * attempt. It never carries a wildcard — an empty key refuses on the owner and never matches a stored
 * result — and a fallback to another attempt or source retires bindings made under the older key.
 */
data class AcousticAuditTarget(
    val mediaId: String,
    val title: String,
    val artists: String = "",
    val requestKey: String = "",
)

enum class AcousticAuditStatus {
    IDLE, RESOLVING, DECODING, ANALYZING, COMPLETE, UNAVAILABLE, FAILED, CANCELLED,
}

enum class AcousticAssessment {
    INCONCLUSIVE, NO_SUSPICIOUS_CUTOFF, SUSPECTED_UPSCALED,
}

data class AcousticAuditResult(
    val mediaId: String,
    val sourceLabel: String,
    val sampleRate: Int,
    val channelCount: Int,
    val startMs: Long,
    val durationMs: Long,
    val cutoffHz: Float?,
    val nyquistHz: Float,
    val width: Int,
    val height: Int,
    // Index = frequencyRow * width + timeColumn; row zero is the lowest frequency.
    val magnitudeDb: FloatArray,
    val assessment: AcousticAssessment,
    val limitations: List<String>,
    /** The decoded source's request token (occurrence plus attempt identity); surfaces never bind on it alone. */
    val requestKey: String = "",
    /** The queue occurrence the source was decoded under; surfaces bind it to the target's key, never "". */
    val occurrenceKey: String = "",
)

data class AcousticAuditState(
    val status: AcousticAuditStatus = AcousticAuditStatus.IDLE,
    val progress: Float? = null,
    val result: AcousticAuditResult? = null,
    val message: String? = null,
)

object AcousticAuditLimits {
    const val SAMPLE_SECONDS = 20
    const val MAX_PCM_BYTES = 16 * 1024 * 1024
    const val MAX_PCM_FRAMES = 192_000 * SAMPLE_SECONDS
    const val MAX_HEAT_WIDTH = 512
    const val MAX_HEAT_HEIGHT = 1024
    const val MAX_HEAT_CELLS = MAX_HEAT_WIDTH * MAX_HEAT_HEIGHT
    const val DEADLINE_MS = 45_000L
}

/**
 * The decoder output a host probe hands the analyzer: source-rate interleaved PCM with the facts the
 * verdict needs. [advertisedSampleRate] is what the stream parameters report; [claimedLossless] is a
 * codec-name claim, never a quality-tier guess.
 */
class AcousticPcm(
    val samples: FloatArray,
    val sampleRate: Int,
    val channelCount: Int,
    val startUs: Long,
    val frames: Int,
    val truncated: Boolean,
)

/** A bounded audit refusal: [code] names the reason for the state message, never a user-facing string itself. */
class AcousticUnavailable(val code: String) : Exception("acoustic audit unavailable: $code")

/** What a host probe produced for one audit: decoded PCM ready for analysis, or a refusal the UI renders. */
sealed interface AcousticWindow {
    class Decoded(
        val pcm: AcousticPcm,
        val sourceLabel: String,
        val advertisedSampleRate: Int?,
        val claimedLossless: Boolean,
        val limitations: List<String> = emptyList(),
        /** The decoded source's request token (occurrence plus attempt identity), e.g. "occurrence#attempt". */
        val requestKey: String = "",
        /** The queue occurrence the source was decoded under; the owner binds it to the target's key. */
        val occurrenceKey: String = "",
    ) : AcousticWindow

    /** [code] names the refusal reason for the state message; codes come from the shared string table. */
    class Refused(val code: String) : AcousticWindow
}

/** Codec names a probe reports for which a lossless claim is safe to assume. */
val ACOUSTIC_LOSSLESS_CODECS = setOf(
    "flac", "alac", "wavpack", "tta", "pcm_s16le", "pcm_s24le", "pcm_s32le",
    "pcm_f32le", "pcm_f64le", "pcm_u8", "ape", "mlp", "truehd",
)

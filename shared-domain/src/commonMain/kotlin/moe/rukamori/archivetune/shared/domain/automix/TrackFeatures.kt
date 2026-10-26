package moe.rukamori.archivetune.shared.domain.automix

/**
 * The bounded local feature analysis feeding smart transitions, ported from
 * `playback/smart/TrackFeatures.kt`. The persisted-JSON parse lives with the analysis store, which
 * is not part of this port; the field names stay the same so the store can mirror them exactly.
 */
object TrackFeatures {

    val available: Boolean = true
    val sampleRate: Double = 11_025.0

    fun analyze(
        samples: FloatArray,
        durationSeconds: Double,
        performanceMode: AutomixPerformanceMode = AutomixPerformanceMode.BALANCED,
        checkActive: () -> Unit = defaultAutomixCheckActive(),
    ): Features? = CompactAutomixAnalyzer.analyze(samples, durationSeconds, performanceMode, checkActive)

    fun resample(
        samples: FloatArray,
        inputRate: Double,
        outputRate: Double = sampleRate,
        checkActive: () -> Unit = defaultAutomixCheckActive(),
    ): FloatArray? = CompactAutomixAnalyzer.resample(samples, inputRate, outputRate, checkActive)

    /** The subset of the analyzer's output the transition policy reads. */
    data class Features(
        val duration: Double,
        val bpm: Double,
        val beatInterval: Double,
        val firstBeat: Double,
        val beatConfidence: Double,
        val key: String,
        val keyConfidence: Double,
        val audibleStartTime: Double,
        val pickupTime: Double,
        val introEndTime: Double,
        val outroStartTime: Double,
        val contentEndTime: Double,
        val mixInTime: Double,
        val mixOutTime: Double,
        val vocalProbability: Double,
        val downbeats: List<Double>,
        val phraseBoundaries: List<Double>,
        val vocalActivityMask: List<Double>,
        val energyCurve: List<EnergySample>,
        val lowEnergyCurve: List<EnergySample>,
        val mixInCandidates: List<MixCandidate>,
        val mixOutCandidates: List<MixCandidate>,
        val finalFadeOnsetTime: Double? = null,
    )
}

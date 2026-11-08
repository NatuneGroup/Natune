package moe.rukamori.archivetune.desktop.playback

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import moe.rukamori.archivetune.shared.domain.dsp.DspGraph
import moe.rukamori.archivetune.shared.domain.dsp.DspSettings
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStagePlan
import moe.rukamori.archivetune.shared.domain.dsp.normalization.NormalizationGain

/** Shared tone processing on the decoder worker. FFmpeg owns tempo/pitch; the native mixer owns transitions/volume. */
internal class DesktopPcmProcessor {
    private data class Configuration(val settings: DspSettings, val plan: SoftwareStagePlan)
    private val configured = AtomicReference<Configuration?>()
    private var graph: DspGraph? = null
    private var epoch = Long.MIN_VALUE
    private var samplesMeasured = 0L
    private var energy = 0.0
    private var peakMeasured = 0.0
    private var measuredGain: Double? = null

    fun configure(settings: DspSettings, plan: SoftwareStagePlan) {
        configured.set(Configuration(settings.copy(speed = 1.0, pitchSemitones = 0.0, transitionGain = 1f),
            plan.copy(tempoPitch = false)))
    }

    /** JNI signature is ([FIIJDD)V. One native decoder thread owns all history and scratch. */
    fun process(samples: FloatArray, frames: Int, rate: Int, generation: Long, reportedGain: Double, reportedPeak: Double) {
        val config = configured.get() ?: return
        if (epoch != generation || graph?.format?.sampleRateHz != rate) {
            graph = DspGraph(rate, 2)
            epoch = generation
            samplesMeasured = 0
            energy = 0.0
            peakMeasured = 0.0
            measuredGain = null
        }
        if (samplesMeasured < rate * 2L * 5L) {
            val count = minOf(frames * 2, (rate * 2L * 5L - samplesMeasured).toInt())
            for (index in 0 until count) {
                val value = samples[index].toDouble()
                if (value.isFinite()) { energy += value * value; peakMeasured = maxOf(peakMeasured, abs(value)) }
            }
            samplesMeasured += count
            if (samplesMeasured >= rate * 2L * 5L && energy > 1e-8) {
                val rms = sqrt(energy / samplesMeasured)
                if (rms > 0.001) measuredGain = (-18.0 - 20.0 * log10(rms)).coerceIn(-12.0, 6.0)
            }
        }
        val gainDb = reportedGain.takeIf { it.isFinite() && abs(it) <= 60 } ?: measuredGain
        val peak = reportedPeak.takeIf { it.isFinite() && it > 0 } ?: peakMeasured.takeIf { it > 0 }
        val normalization = if (config.settings.linearGain != 1f) config.settings.linearGain else NormalizationGain.linearGain(gainDb, peak)
        val processor = requireNotNull(graph)
        processor.apply(config.settings.copy(linearGain = normalization), config.plan)
        check(processor.process(samples, frames, samples, frames) == frames) { "Tone processing changed frame count" }
    }
}

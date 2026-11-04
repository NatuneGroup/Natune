@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import moe.rukamori.archivetune.shared.domain.dsp.DspFormat
import moe.rukamori.archivetune.shared.domain.dsp.DspGraph
import moe.rukamori.archivetune.shared.domain.dsp.DspSettings
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStagePlan

/**
 * The one software chain on Android: a Media3 [AudioProcessor] around the shared [DspGraph]. It replaces
 * the separate Tryptify, LastWave and float-DSP processors, so tone, dynamics, clarity, tempo and dither
 * run through the code every platform feeds.
 *
 * Settings arrive as one immutable snapshot from any thread ([submit]) and the playback thread only ever
 * reads the latest one, so a settings change never blocks the render path and no lock is taken on it.
 *
 * Input is 16-bit or float PCM; output is always float, which the sink either plays as float PCM or
 * converts once. Anything else — and every bit-perfect route, whose plan arrives bypassed — leaves this
 * processor inactive, which is [AudioProcessor.AudioFormat.NOT_SET] and makes Media3 skip it, so those
 * samples never enter the chain.
 *
 * A neutral but non-bypassed plan stays active on purpose: [BaseAudioProcessor] settles [isActive] at
 * configure time, so going inactive for "no stage engaged" would need a sink rebuild before an equalizer
 * switch could take effect. The graph hands a neutral block through bit-exactly instead, and
 * [isEngaged] reports what actually runs.
 */
class UnifiedDspAudioProcessor(
    private val newGraph: (DspFormat) -> DspGraph = { DspGraph(it.sampleRateHz, it.channelCount) },
    private val bypassGate: () -> Boolean = { BitPerfectRuntime.chainBypassActive },
) : BaseAudioProcessor() {

    private class Snapshot(val settings: DspSettings, val plan: SoftwareStagePlan)

    @Volatile
    private var requested: Snapshot? = null

    /** The snapshot the graph was last given; touched only from the playback thread. */
    private var applied: Snapshot? = null

    private var graph: DspGraph? = null
    private var format: DspFormat? = null

    /** The bit-perfect gate's last known verdict, so a mid-stream change is applied once, not per block. */
    private var gateBypassed = false

    private var inputFrames = FloatArray(0)
    private var shortScratch = ShortArray(0)
    private var outputFrames = FloatArray(0)

    /** What actually runs: false while the route bypasses or while the chain is inactive. */
    @Volatile
    var isEngaged: Boolean = false
        private set

    /** Replaces the settings and the route plan whole. Safe from any thread, including the UI thread. */
    fun submit(settings: DspSettings, plan: SoftwareStagePlan) {
        requested = Snapshot(settings, plan)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val request = requested ?: return inactive()
        if (request.plan.bypass) return inactive()
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) return inactive()
        // The shared graph only runs at rates it can filter at; anything else stays untouched by design.
        val target =
            runCatching { DspFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount) }
                .getOrNull() ?: return inactive()
        val graph = graph ?: newGraph(target).also { graph = it }
        // Configure allocates, and Media3 calls this off the audio thread; a changed rate also drops every
        // stage's history, which is what a new stream format needs.
        graph.configure(target)
        val gated = bypassGate()
        graph.apply(
            if (gated) DspSettings() else request.settings,
            if (gated) UnifiedDspSettingsSource.BIT_PERFECT_PLAN else request.plan,
        )
        applied = request
        gateBypassed = gated
        format = target
        isEngaged = !graph.isBypassed
        EngineRuntime.publishDspActive(isEngaged)
        return AudioProcessor.AudioFormat(target.sampleRateHz, target.channelCount, C.ENCODING_PCM_FLOAT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val graph = graph
        val audioFormat = format
        if (graph == null || audioFormat == null) {
            copyThrough(inputBuffer)
            return
        }
        val request = requested
        // The bit-perfect gate can engage or release mid-stream, after the sink was configured, and a
        // settings move is applied once per change: the graph glides a gain or preset instead of clicking.
        val gated = bypassGate()
        if (request != null && (request !== applied || gated != gateBypassed)) {
            graph.apply(
                if (gated) DspSettings() else request.settings,
                if (gated) UnifiedDspSettingsSource.BIT_PERFECT_PLAN else request.plan,
            )
            applied = request
            gateBypassed = gated
        }
        val channels = audioFormat.channelCount
        val floatInput = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (floatInput) FLOAT_BYTES else SHORT_BYTES
        val frames = inputBuffer.remaining() / (bytesPerSample * channels)
        if (frames <= 0) {
            copyThrough(inputBuffer)
            return
        }
        val samples = frames * channels
        ensureInput(samples)
        val source = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        if (floatInput) {
            source.asFloatBuffer().get(inputFrames, 0, samples)
        } else {
            ensureShorts(samples)
            source.asShortBuffer().get(shortScratch, 0, samples)
            for (index in 0 until samples) inputFrames[index] = shortScratch[index] / SHORT_SCALE
        }
        inputBuffer.position(inputBuffer.limit())

        graph.push(inputFrames, frames)
        val capacity = graph.requiredOutputCapacity(frames)
        ensureOutput(capacity * channels)
        val produced = graph.pull(outputFrames, capacity)
        writeFloat(produced * channels)
    }

    override fun onQueueEndOfStream() {
        val graph = graph ?: return
        val channels = format?.channelCount ?: return
        // The tempo stage may still hold frames; finish releases them and the drain hands them to the sink.
        graph.finish()
        val pending = graph.pendingFrames
        if (pending <= 0) return
        ensureOutput(pending * channels)
        val produced = graph.pull(outputFrames, pending)
        if (produced <= 0) return
        writeFloat(produced * channels)
    }

    override fun onFlush() {
        graph?.reset()
    }

    override fun onReset() {
        graph = null
        format = null
        applied = null
        inputFrames = FloatArray(0)
        shortScratch = ShortArray(0)
        outputFrames = FloatArray(0)
        isEngaged = false
        EngineRuntime.publishDspActive(false)
    }

    private fun inactive(): AudioProcessor.AudioFormat {
        isEngaged = false
        EngineRuntime.publishDspActive(false)
        return AudioProcessor.AudioFormat.NOT_SET
    }

    private fun writeFloat(samples: Int) {
        val bytes = samples * FLOAT_BYTES
        val output = replaceOutputBuffer(bytes)
        output.order(ByteOrder.nativeOrder()).asFloatBuffer().put(outputFrames, 0, samples)
        output.position(0)
        output.limit(bytes)
    }

    private fun copyThrough(inputBuffer: ByteBuffer) {
        replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
    }

    private fun ensureInput(samples: Int) {
        if (inputFrames.size < samples) inputFrames = FloatArray(grown(inputFrames.size, samples))
    }

    private fun ensureShorts(samples: Int) {
        if (shortScratch.size < samples) shortScratch = ShortArray(grown(shortScratch.size, samples))
    }

    private fun ensureOutput(samples: Int) {
        if (outputFrames.size < samples) outputFrames = FloatArray(grown(outputFrames.size, samples))
    }

    private fun grown(current: Int, wanted: Int): Int = maxOf(wanted, current * 2)

    private companion object {
        const val FLOAT_BYTES = 4
        const val SHORT_BYTES = 2
        const val SHORT_SCALE = 32_768f
    }
}

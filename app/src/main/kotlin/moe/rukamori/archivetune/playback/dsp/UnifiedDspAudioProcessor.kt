@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.round
import moe.rukamori.archivetune.shared.domain.dsp.DspDither
import moe.rukamori.archivetune.shared.domain.dsp.DspFormat
import moe.rukamori.archivetune.shared.domain.dsp.DspGraph
import moe.rukamori.archivetune.shared.domain.dsp.DspSettings
import moe.rukamori.archivetune.shared.domain.dsp.TpdfDither
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStagePlan

/**
 * The one software chain on Android: a Media3 [AudioProcessor] around the shared [DspGraph]. It replaces
 * the separate Tryptify, LastWave and float-DSP processors, so tone, dynamics, clarity, tempo and dither
 * run through the code every platform feeds.
 *
 * Settings arrive as one immutable snapshot from any thread ([submit]) and the playback thread only ever
 * reads the latest one, so a settings change never blocks the render path and no lock is taken on it.
 *
 * Input is 16-bit or float PCM; the graph runs in float and the output is always 16-bit PCM. That is a
 * contract, not a preference: the standard sink appends Media3's silence skipper and this project's
 * transition filter after the supplied processors, both of which reject anything but 16-bit PCM in
 * `onConfigure` — even when silence skipping is switched off, because the sink configures every processor
 * in its chain. The graph's clamp runs before the reduction and its TPDF dither runs on the way out when a
 * host asked for it. Decoding and encoding share the signed 16-bit grid (32768 steps, saturated at the end
 * points), so a neutral block returns every PCM16 value unchanged and only an engaged stage moves a sample.
 *
 * Anything else — and every bit-perfect route, whose plan arrives bypassed — leaves this processor
 * inactive, which is [AudioProcessor.AudioFormat.NOT_SET] and makes Media3 skip it, so those samples never
 * enter the chain.
 *
 * A neutral but non-bypassed plan stays active on purpose: [BaseAudioProcessor] settles [isActive] at
 * configure time, so going inactive for "no stage engaged" would need a sink rebuild before an equalizer
 * switch could take effect. The graph hands a neutral block through, and [isEngaged] reports what runs.
 */
class UnifiedDspAudioProcessor(
    private val newGraph: (DspFormat) -> DspGraph = { DspGraph(it.sampleRateHz, it.channelCount) },
    private val bypassGate: () -> Boolean = { BitPerfectRuntime.chainBypassActive },
) : BaseAudioProcessor() {

    /**
     * One prepared settings request. Everything a change needs is built in [submit] on the caller's thread: the
     * settings with the graph's dither stripped (this adapter dithers once, at the output grid) and the output
     * dither stage. The render path then only swaps references at a block boundary.
     */
    private class Snapshot(
        val settings: DspSettings,
        val plan: SoftwareStagePlan,
        val dither: TpdfDither?,
    )

    /**
     * One `configure` for the next stream, held until Media3 flushes. Configure may arrive while the
     * previous stream is still draining, and Media3 commits the new format at flush; rebuilding the graph
     * here would drop the old stream's stage history and read its remaining buffers with the new stride.
     */
    private class Pending(
        val format: DspFormat,
        val floatInput: Boolean,
        val request: Snapshot,
        val gated: Boolean,
    )

    @Volatile
    private var requested: Snapshot? = null

    /** The snapshot and stream the graph is running; touched only from the playback thread. */
    private var applied: Snapshot? = null

    private var graph: DspGraph? = null
    private var format: DspFormat? = null

    // Read with [format] rather than from [inputAudioFormat], which BaseAudioProcessor only adopts at the
    // next flush: a block queued for the draining stream must keep the encoding that stream was configured
    // with, even after a configure for the next one arrived.
    private var floatInput = false

    /** The bit-perfect gate's last known verdict, so a mid-stream change is applied once, not per block. */
    private var gateBypassed = false

    /** The stream's staged configure, or null while no new stream is waiting for its flush. */
    private var pending: Pending? = null

    /** The output quantizer, present only while a host asked for dither. */
    private var dither: TpdfDither? = null

    private var inputFrames = FloatArray(0)
    private var outputFrames = FloatArray(0)

    /** What actually runs: false while the route bypasses or while the chain is inactive. */
    @Volatile
    var isEngaged: Boolean = false
        private set

    /** Replaces the settings and the route plan whole. Safe from any thread, including the UI thread. */
    fun submit(settings: DspSettings, plan: SoftwareStagePlan) {
        // The request is prepared here, on the caller's thread: the block that adopts it only swaps references.
        requested = Snapshot(
            settings = settings.copy(dither = DspDither.OFF),
            plan = plan,
            dither = ditherFor(settings),
        )
    }

    /**
     * The output dither stage this request runs. A preference re-emission repeats the same request, so keeping
     * the running generator for the same seed avoids restarting the noise sequence on every emission.
     */
    private fun ditherFor(settings: DspSettings): TpdfDither? {
        if (!settings.dither.enabled) return null
        val running = requested?.dither
        return if (running != null && running.seed == settings.dither.seed) {
            running
        } else {
            TpdfDither(OUTPUT_BIT_DEPTH, settings.dither.seed)
        }
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
        val next = Pending(target, floatInput = encoding == C.ENCODING_PCM_FLOAT, request = request, gated = bypassGate())
        if (format == null) {
            // The first stream has nothing to drain, so there is nothing to wait for.
            adopt(next)
        } else {
            pending = next
        }
        return AudioProcessor.AudioFormat(target.sampleRateHz, target.channelCount, C.ENCODING_PCM_16BIT)
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
            applyRequest(graph, request, gated)
        }
        val channels = audioFormat.channelCount
        val bytesPerSample = if (floatInput) FLOAT_BYTES else SHORT_BYTES
        val frames = inputBuffer.remaining() / (bytesPerSample * channels)
        if (frames <= 0) {
            copyThrough(inputBuffer)
            return
        }
        val samples = frames * channels
        ensureInput(samples)
        // Absolute primitive reads keep steady-state rendering allocation-free; a duplicate or a typed view
        // would allocate one object per block. Audio buffers are native order, which is what the views used
        // to impose, so the order is stated once here instead of inherited.
        if (inputBuffer.order() != ByteOrder.nativeOrder()) inputBuffer.order(ByteOrder.nativeOrder())
        val start = inputBuffer.position()
        if (floatInput) {
            for (index in 0 until samples) {
                inputFrames[index] = inputBuffer.getFloat(start + index * FLOAT_BYTES)
            }
        } else {
            for (index in 0 until samples) {
                inputFrames[index] = inputBuffer.getShort(start + index * SHORT_BYTES) / SHORT_SCALE
            }
        }
        inputBuffer.position(inputBuffer.limit())

        graph.push(inputFrames, frames)
        val capacity = graph.requiredOutputCapacity(frames)
        ensureOutput(capacity * channels)
        val produced = graph.pull(outputFrames, capacity)
        writePcm16(produced * channels, channels)
    }

    override fun onQueueEndOfStream() {
        val graph = graph ?: return
        val channels = format?.channelCount ?: return
        // The tempo stage may still hold frames; finish releases them and the drain hands them to the sink.
        graph.finish()
        val pendingFrames = graph.pendingFrames
        if (pendingFrames <= 0) return
        ensureOutput(pendingFrames * channels)
        val produced = graph.pull(outputFrames, pendingFrames)
        if (produced <= 0) return
        writePcm16(produced * channels, channels)
    }

    override fun onFlush() {
        val next = pending
        pending = null
        val graph = graph
        if (next != null) {
            // The old stream has drained; this is where Media3 commits the new format.
            adopt(next)
            return
        }
        graph?.reset()
    }

    override fun onReset() {
        graph = null
        format = null
        floatInput = false
        applied = null
        pending = null
        dither = null
        gateBypassed = false
        inputFrames = FloatArray(0)
        outputFrames = FloatArray(0)
        isEngaged = false
        EngineRuntime.publishDspActive(false)
    }

    /** Starts the staged stream on the graph, dropping whatever the previous stream left behind. */
    private fun adopt(next: Pending) {
        val target = next.format
        val existing = graph
        // A new format needs every stage rebuilt, which is what a fresh graph does; the same format keeps
        // the running graph so a flush clears its history instead of throwing its stages away.
        val nextGraph =
            if (existing != null && format == target) {
                existing
            } else {
                newGraph(target).also { graph = it }
            }
        applyRequest(nextGraph, next.request, next.gated)
        nextGraph.reset()
        format = target
        floatInput = next.floatInput
        isEngaged = !nextGraph.isBypassed
        EngineRuntime.publishDspActive(isEngaged)
    }

    /**
     * Swaps in one prepared request. The graph dithers for a host that reads its float output, while this
     * adapter quantizes to 16-bit itself, so the prepared settings already carry the dither off and the output
     * dither stage rides with the request. Neither the settings value nor the equalizer's stages are built here:
     * the host prepared both, and this path only installs them.
     */
    private fun applyRequest(graph: DspGraph, request: Snapshot, gated: Boolean) {
        if (gated) {
            graph.apply(NEUTRAL_SETTINGS, UnifiedDspSettingsSource.BIT_PERFECT_PLAN)
            dither = null
        } else {
            graph.apply(request.settings, request.plan)
            dither = request.dither
        }
        applied = request
        gateBypassed = gated
    }

    private fun inactive(): AudioProcessor.AudioFormat {
        pending = null
        isEngaged = false
        EngineRuntime.publishDspActive(false)
        return AudioProcessor.AudioFormat.NOT_SET
    }

    /**
     * The chain reduces onto the signed 16-bit grid the input was read from: a full-scale sample saturates at
     * the end points instead of wrapping, and a neutral block round-trips every PCM16 value unchanged. The
     * dither, when a host asked for it, quantizes onto that same grid before this reduction.
     */
    private fun writePcm16(samples: Int, channels: Int) {
        val bytes = samples * SHORT_BYTES
        val output = replaceOutputBuffer(bytes).order(ByteOrder.nativeOrder())
        dither?.process(outputFrames, samples / channels, channels)
        for (index in 0 until samples) {
            val scaled = round(outputFrames[index].coerceIn(-1f, 1f) * SHORT_SCALE).toInt()
            output.putShort(index * SHORT_BYTES, scaled.coerceIn(SHORT_MIN, SHORT_MAX).toShort())
        }
        output.position(0)
        output.limit(bytes)
    }

    private fun copyThrough(inputBuffer: ByteBuffer) {
        replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
    }

    private fun ensureInput(samples: Int) {
        if (inputFrames.size < samples) inputFrames = FloatArray(grown(inputFrames.size, samples))
    }

    private fun ensureOutput(samples: Int) {
        if (outputFrames.size < samples) outputFrames = FloatArray(grown(outputFrames.size, samples))
    }

    private fun grown(current: Int, wanted: Int): Int = maxOf(wanted, current * 2)

    private companion object {
        const val FLOAT_BYTES = 4
        const val SHORT_BYTES = 2

        /** `2^15`: the divisor that reads every PCM16 value, including [SHORT_MIN], into [-1, 1]. */
        const val SHORT_SCALE = 32_768f
        const val SHORT_MIN = -32_768
        const val SHORT_MAX = 32_767
        const val OUTPUT_BIT_DEPTH = 16

        /** What the bit-perfect gate renders, built once instead of per request. */
        val NEUTRAL_SETTINGS: DspSettings = DspSettings().withoutProcessing()
    }
}

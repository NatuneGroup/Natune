package moe.rukamori.archivetune.shared.domain.dsp.chain

import moe.rukamori.archivetune.shared.domain.dsp.DspGraph
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStagePlan

/** The unified settings model. The alias keeps the legacy `dsp.chain` import working without a second model. */
typealias DspSettings = moe.rukamori.archivetune.shared.domain.dsp.DspSettings

/**
 * The portable chain this module shipped before the chain was unified. It is now a façade over [DspGraph]
 * and exists only for the callers that already use its one-shot API (`DspPortableChainTest` and the desktop
 * transition integration test).
 *
 * Its contract is unchanged: normalization, EQ, tempo/pitch and a full-scale clamp, with an immediate
 * equalizer retune and without the DC blocker, limiter or dither that only the graph stages run. New callers
 * use [DspGraph] directly.
 *
 * Call [reset] before reusing a chain after [finish].
 */
class PortableDspChain(sampleRateHz: Int, channels: Int) {
    private val graph = DspGraph(sampleRateHz, channels, equalizerGlideMs = 0.0)

    var isBypassed: Boolean = false
        private set

    fun apply(settings: DspSettings, plan: SoftwareStagePlan) {
        graph.apply(settings, plan)
        isBypassed = plan.bypass
    }

    /** Processes [frames] frames and returns the frames ready now. Tempo changes make the count differ. */
    fun process(input: FloatArray, frames: Int): FloatArray {
        graph.push(input, frames)
        return drainAll()
    }

    /** Ends the stream and returns the tail that tempo/pitch held back. */
    fun finish(): FloatArray {
        graph.finish()
        return drainAll()
    }

    /** Clears filter and time-stretch history at a seek or any other discontinuity. */
    fun reset() {
        graph.reset()
    }

    private fun drainAll(): FloatArray {
        val channels = graph.format.channelCount
        val parts = ArrayList<FloatArray>()
        var totalFrames = 0
        while (true) {
            val ready = graph.pendingFrames
            if (ready <= 0) break
            val block = FloatArray(ready * channels)
            val copied = graph.pull(block, ready)
            if (copied <= 0) break
            parts.add(if (copied == ready) block else block.copyOf(copied * channels))
            totalFrames += copied
        }
        if (parts.isEmpty()) return FloatArray(0)
        if (parts.size == 1) return parts[0]
        val output = FloatArray(totalFrames * channels)
        var offset = 0
        for (part in parts) {
            part.copyInto(output, offset)
            offset += part.size
        }
        return output
    }
}

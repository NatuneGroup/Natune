package moe.rukamori.archivetune.shared.domain.transition

/**
 * Mixes two interleaved float blocks under an output-frame-indexed envelope. A null deck contributes
 * silence. A null plan passes the outgoing deck at unity, so an idle scheduler never alters audio.
 */
object TwoDeckMixer {
    /**
     * Writes [frames] frames into [output]. [outgoing] and [incoming] hold this block's frames, starting at
     * timeline frame [firstFrame]. Each array must have at least `frames * channels` samples.
     */
    fun mix(
        output: FloatArray,
        outgoing: FloatArray?,
        incoming: FloatArray?,
        channels: Int,
        frames: Int,
        firstFrame: Long,
        plan: TransitionPlan?,
    ) {
        require(channels > 0) { "channels must be positive" }
        val samples = frames * channels
        require(output.size >= samples) { "output holds fewer samples than requested" }
        require(outgoing == null || outgoing.size >= samples) { "outgoing block is shorter than requested" }
        require(incoming == null || incoming.size >= samples) { "incoming block is shorter than requested" }

        for (frame in 0 until frames) {
            val timelineFrame = firstFrame + frame
            val outgoingGain = if (plan == null) 1f else EqualPowerEnvelope.outgoingGainAt(plan, timelineFrame)
            val incomingGain = if (plan == null) 0f else EqualPowerEnvelope.incomingGainAt(plan, timelineFrame)
            val base = frame * channels
            for (channel in 0 until channels) {
                val index = base + channel
                val outgoingSample = if (outgoing == null || outgoingGain == 0f) 0f else outgoing[index] * outgoingGain
                val incomingSample = if (incoming == null || incomingGain == 0f) 0f else incoming[index] * incomingGain
                output[index] = outgoingSample + incomingSample
            }
        }
    }
}

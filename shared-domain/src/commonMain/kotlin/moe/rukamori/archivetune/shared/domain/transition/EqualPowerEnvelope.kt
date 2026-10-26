package moe.rukamori.archivetune.shared.domain.transition

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Equal-power handoff gains. Across the overlap the outgoing and incoming gains satisfy
 * outgoing² + incoming² = 1, so perceived loudness stays level while uncorrelated material blends.
 * A linear ramp would dip about 3 dB at the midpoint instead.
 */
object EqualPowerEnvelope {
    fun gainsAt(plan: TransitionPlan, frame: Long): TransitionGains =
        TransitionGains(outgoing = outgoingGainAt(plan, frame), incoming = incomingGainAt(plan, frame))

    /** Outgoing gain at [frame]: unity before the overlap, zero from its end. */
    fun outgoingGainAt(plan: TransitionPlan, frame: Long): Float =
        when {
            frame < plan.startFrame -> 1f
            frame >= plan.endFrame -> 0f
            else -> outgoingGain(progressAt(plan, frame))
        }

    /** Incoming gain at [frame]: silent before the overlap, unity from its end. */
    fun incomingGainAt(plan: TransitionPlan, frame: Long): Float =
        when {
            frame < plan.startFrame -> 0f
            frame >= plan.endFrame -> 1f
            else -> incomingGain(progressAt(plan, frame))
        }

    /** Outgoing gain at [progress] in [0, 1]: cos(progress · π/2). */
    fun outgoingGain(progress: Double): Float = cos(progress.coerceIn(0.0, 1.0) * (PI / 2.0)).toFloat()

    /** Incoming gain at [progress] in [0, 1]: sin(progress · π/2). */
    fun incomingGain(progress: Double): Float = sin(progress.coerceIn(0.0, 1.0) * (PI / 2.0)).toFloat()

    /** Player volume for the outgoing deck: [baseVolume] under the fade, capped at [maxGain]. */
    fun outgoingVolume(progress: Double, baseVolume: Float, maxGain: Float): Float =
        (baseVolume * outgoingGain(progress)).coerceIn(0f, maxGain)

    /** Player volume for the incoming deck: [baseVolume] under the fade, capped at [maxGain]. */
    fun incomingVolume(progress: Double, baseVolume: Float, maxGain: Float): Float =
        (baseVolume * incomingGain(progress)).coerceIn(0f, maxGain)

    private fun progressAt(plan: TransitionPlan, frame: Long): Double =
        (frame - plan.startFrame).toDouble() / plan.overlapFrames.toDouble()
}

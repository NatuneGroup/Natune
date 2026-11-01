package moe.rukamori.archivetune.playback

import kotlin.math.pow
import moe.rukamori.archivetune.playback.smart.TransitionPlan

/**
 * The transition filter rides of the smart-fade blend, ported faithfully from BitChord's
 * CrossfadeController:
 *  - DJ_FILTER: the outgoing low-pass sweeps closed while the incoming high-pass rides down,
 *    front-loaded;
 *  - DJ_BLEND with bassSwap: the low end changes hands once at the beat the planner chose, with a
 *    clash-scaled body lift and shallow exit roll-off;
 *  - otherwise: vocal-separation damage control scaled by the overlap.
 *
 * Each ride takes both processors explicitly, so [MusicService] keeps the filters themselves and
 * this file keeps no state at all.
 */
internal fun rideFilterSweep(
    plan: TransitionPlan,
    progress: Float,
    outgoing: TransitionFilterProcessor,
    incoming: TransitionFilterProcessor,
) {
    val sweep = plan.filterSweep.coerceIn(0.0, 1.0)
    if (sweep <= 0.0) {
        outgoing.open()
        incoming.open()
        return
    }
    // Both ends scaled by the sweep, so a partial sweep engages less
    // sharply AND stops short of the floor.
    val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
    val entry = glide(open, FILTER_ENTRY_HZ, sweep)
    val floor = glide(open, FILTER_FLOOR_HZ, sweep)
    val cutoff = glide(entry, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE))
    outgoing.setCutoffs(cutoff.toFloat(), TransitionFilterProcessor.OFF_HZ)
    incoming.setCutoffs(
        TransitionFilterProcessor.OPEN_HZ,
        entryHighPass(progress, sweep, ENTRY_HIGH_PASS_HZ, ENTRY_OPEN_BY),
    )
}

/** Hands the low end from one track to the other, once, ramped over
 * [BASS_SWAP_WIDTH] of the fade at the beat the planner chose. */
internal fun rideBassSwap(
    plan: TransitionPlan,
    progress: Float,
    outgoing: TransitionFilterProcessor,
    incoming: TransitionFilterProcessor,
) {
    val swapAt = plan.bassSwapFraction.coerceIn(0.05, 0.95)
    // 0 before the swap window, 1 after it: how much of the low end has
    // changed hands.
    val handover = ((progress - swapAt) / BASS_SWAP_WIDTH * 0.5 + 0.5).coerceIn(0.0, 1.0)
    // The entry corner scales with how much the two actually sing over
    // each other; whichever corner sits higher is doing the work.
    val clash = plan.vocalOverlap.coerceIn(0.0, 1.0)
    val entry =
        maxOf(
            bassCutoff(1.0 - handover),
            entryHighPass(
                progress,
                1.0,
                glide(BLEND_ENTRY_HIGH_PASS_HZ, BLEND_ENTRY_CLASH_HIGH_PASS_HZ, clash),
                BLEND_ENTRY_OPEN_BY + (BLEND_ENTRY_CLASH_OPEN_BY - BLEND_ENTRY_OPEN_BY) * clash,
            ),
        )
    incoming.setCutoffs(TransitionFilterProcessor.OPEN_HZ, entry)
    outgoing.setCutoffs(blendExitLowPass(progress, clash), bassCutoff(handover))
}

/** The outgoing low-pass through a beat-matched blend: open until
 * [BLEND_EXIT_FROM], then closing shallowly — enough to stop competing with
 * the arriving voice, nowhere near the DJ_FILTER floor. */
internal fun blendExitLowPass(
    progress: Float,
    clash: Double,
): Float {
    val from = BLEND_EXIT_FROM + (BLEND_EXIT_CLASH_FROM - BLEND_EXIT_FROM) * clash
    val amount = ((progress - from) / (1.0 - from)).coerceIn(0.0, 1.0)
    val floor = glide(BLEND_EXIT_LOW_PASS_HZ, BLEND_EXIT_CLASH_LOW_PASS_HZ, clash)
    return glide(TransitionFilterProcessor.OPEN_HZ.toDouble(), floor, amount).toFloat()
}

/** [amount] 0 leaves the low end alone; 1 lifts it out entirely. */
internal fun bassCutoff(amount: Double): Float =
    glide(TransitionFilterProcessor.OFF_HZ.toDouble(), BASS_SWAP_HZ, amount).toFloat()

/** Vocal-clash damage control, scaled by how much the two collide. */
internal fun rideVocalSeparation(
    plan: TransitionPlan,
    progress: Float,
    outgoing: TransitionFilterProcessor,
    incoming: TransitionFilterProcessor,
) {
    val amount = plan.vocalOverlap.coerceIn(0.0, 1.0)
    if (amount <= 0.0) {
        outgoing.open()
        incoming.open()
        return
    }
    val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
    val floor = glide(open, VOCAL_SEPARATION_FLOOR_HZ, amount)
    outgoing.setCutoffs(
        glide(open, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE)).toFloat(),
        TransitionFilterProcessor.OFF_HZ,
    )
    incoming.setCutoffs(
        TransitionFilterProcessor.OPEN_HZ,
        entryHighPass(progress, amount, VOCAL_SEPARATION_HIGH_PASS_HZ, ENTRY_OPEN_BY),
    )
}

/** Where the incoming track's high-pass sits at [progress]: rides from
 * [topHz] down to nothing by [openBy] of the fade, front-loaded by
 * [ENTRY_SHAPE] so the travel is spent where a voice actually is. */
internal fun entryHighPass(
    progress: Float,
    amount: Double,
    topHz: Double,
    openBy: Double,
): Float {
    val remaining = (1.0 - progress / openBy).coerceIn(0.0, 1.0)
    return glide(TransitionFilterProcessor.OFF_HZ.toDouble(), topHz, amount * remaining.pow(ENTRY_SHAPE))
        .toFloat()
}

/** Geometric interpolation between two cutoffs: pitch is logarithmic, so
 * cutoffs must glide multiplicatively or they lurch through the bottom of
 * their range and crawl through the top. */
internal fun glide(
    from: Double,
    to: Double,
    amount: Double,
): Double = from * (to / from).pow(amount.coerceIn(0.0, 1.0))

internal const val FILTER_ENTRY_HZ = 7_000.0
internal const val FILTER_FLOOR_HZ = 300.0
internal const val FILTER_SWEEP_SHAPE = 0.75
internal const val ENTRY_HIGH_PASS_HZ = 1_200.0
internal const val ENTRY_OPEN_BY = 0.6
internal const val ENTRY_SHAPE = 0.35
internal const val BASS_SWAP_HZ = 200.0
internal const val BASS_SWAP_WIDTH = 0.10
internal const val VOCAL_SEPARATION_FLOOR_HZ = 1_600.0
internal const val VOCAL_SEPARATION_HIGH_PASS_HZ = 700.0
internal const val BLEND_ENTRY_HIGH_PASS_HZ = 520.0
internal const val BLEND_ENTRY_CLASH_HIGH_PASS_HZ = 950.0
internal const val BLEND_ENTRY_OPEN_BY = 0.45
internal const val BLEND_ENTRY_CLASH_OPEN_BY = 0.7
internal const val BLEND_EXIT_FROM = 0.3
internal const val BLEND_EXIT_CLASH_FROM = 0.12
internal const val BLEND_EXIT_LOW_PASS_HZ = 2_200.0
internal const val BLEND_EXIT_CLASH_LOW_PASS_HZ = 1_100.0

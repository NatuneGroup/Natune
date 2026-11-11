package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.runtime.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.hypot
import kotlin.random.Random

internal class LyricsBlurDrift(
    private val random: Random = Random.Default,
    private val maxDriftDp: Float = DefaultWanderRadiusDp,
) {
    private val xState = mutableFloatStateOf(0f)
    private val yState = mutableFloatStateOf(0f)
    private val rotationState = mutableFloatStateOf(0f)

    /** Horizontal offset in dp, in `-maxDriftDp..maxDriftDp`. */
    val xDp: FloatState get() = xState

    /** Vertical offset in dp, in `-maxDriftDp..maxDriftDp`. */
    val yDp: FloatState get() = yState

    /**
     * Rotation of the backdrop about its own centre, in degrees.
     *
     * Unbounded on purpose. It is an angle, so it wraps for free, and letting it
     * accumulate is what lets a colour keep travelling the same way past a
     * half-turn instead of being tugged back toward a nominal zero.
     */
    val rotationDeg: FloatState get() = rotationState

    private var fromX = 0f
    private var fromY = 0f
    private var toX = 0f
    private var toY = 0f
    private var fromRotation = 0f
    private var toRotation = 0f
    private var legDurationMs = 0f
    private var legElapsedMs = 0f

    init {
        startNextLeg()
    }

    /**
     * Advances the walk by [deltaMs] of wall time. Legs roll over inside the
     * same call, so a dropped frame is caught up rather than skipped.
     */
    fun advance(deltaMs: Float) {
        if (deltaMs <= 0f) return
        legElapsedMs += deltaMs
        while (legElapsedMs >= legDurationMs) {
            legElapsedMs -= legDurationMs
            startNextLeg()
        }
        // Raised cosine: zero velocity at both ends of the leg.
        val t = legElapsedMs / legDurationMs
        val eased = 0.5f - 0.5f * cos(PI.toFloat() * t)
        xState.floatValue = fromX + (toX - fromX) * eased
        yState.floatValue = fromY + (toY - fromY) * eased
        rotationState.floatValue = fromRotation + (toRotation - fromRotation) * eased
    }

    private fun startNextLeg() {
        fromX = toX
        fromY = toY
        fromRotation = toRotation

        // Uniform-area sampling over the full reachable disc: sqrt(u) spreads targets evenly by area
        // (a plain radius would pile them up near the centre) and lets an offset reach the disc edge,
        // so the colour mass regularly crosses the display bounds on its way to the far side.
        val radius = maxDriftDp * sqrt(random.nextFloat())
        val angle = random.nextFloat() * TwoPi
        toX = cos(angle) * radius
        toY = sin(angle) * radius
        val rotationSign = if (random.nextBoolean()) 1f else -1f
        val rotationSpan =
            MinLegRotationDegrees + random.nextFloat() * (MaxLegRotationDegrees - MinLegRotationDegrees)
        toRotation = fromRotation + rotationSign * rotationSpan

        // Long traversals stay slow: the leg duration is derived from the
        // distance (not clamped down to a sprint), so a full-screen sweep
        // takes its time exactly like the Apple Music lyrics backdrop.
        val distance = hypot(toX - fromX, toY - fromY)
        legDurationMs =
            (distance / WanderSpeedDpPerSecond * 1000f)
                .coerceIn(MinLegDurationMs, MaxLegDurationMs)
    }

    internal companion object {
        /** Legacy fixed amplitude, used only when no screen size is known. */
        const val DefaultWanderRadiusDp = 120f

        /** Average travel speed. Deliberately slow — this sits behind lyrics. */
        private const val WanderSpeedDpPerSecond = 26f

        private const val MinLegDurationMs = 6_000f

        private const val MaxLegDurationMs = 26_000f

        private const val MinLegRotationDegrees = 18f

        private const val MaxLegRotationDegrees = 55f

        private const val TwoPi = (2.0 * PI).toFloat()
    }
}


@Composable
internal fun rememberLyricsBlurDrift(radius: Float, active: Boolean): LyricsBlurDrift {
    val drift = remember(radius) { LyricsBlurDrift(maxDriftDp = radius) }
    LaunchedEffect(drift, active) {
        if (!active) return@LaunchedEffect
        var previous = 0L
        while (true) withFrameNanos { now ->
            if (previous != 0L) drift.advance((now - previous).coerceAtLeast(0L) / 1_000_000f)
            previous = now
        }
    }
    return drift
}

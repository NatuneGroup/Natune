package moe.rukamori.archivetune

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.MotionDurationScale
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToLong
import kotlinx.coroutines.delay

val LocalAnimationsDisabled = compositionLocalOf { false }

/** Android 12 (API 31): the first release with platform RenderEffect blur. */
private const val ANDROID_S_SDK = 31

/**
 * The SDK level the blur policy decides against. Android reports its real level; desktop and iOS
 * have no pre-RenderEffect stack, so they report a level past every threshold.
 */
expect val platformBlurSdkLevel: Int

/**
 * Whether the app may blur at all, decided once from "Disable blur effects" and provided at the
 * root. Every blur or glass effect reads the policy through [LocalBlurPolicy] instead of the raw
 * preference, so a site that forgets to check still cannot render one. [renderEffect] covers the
 * platform paths (Modifier.blur, BlurEffect, Haze, the kyant backdrop) and [cpuFallback] the
 * pre-Android-12 stack-blur bitmaps; the split exists because those are the two families call
 * sites branch on.
 */
@Immutable
data class BlurPolicy(
    val userEnabled: Boolean,
) {
    /** Platform RenderEffect blur: allowed only when the user has not disabled it, and only on S+. */
    val renderEffect: Boolean get() = blurRenderEffectAllowed(userEnabled, platformBlurSdkLevel)

    /** CPU stack-blur fallbacks (rememberPreBlurredBitmap, ImageBlurUtils): the pre-S complement. */
    val cpuFallback: Boolean get() = blurCpuFallbackAllowed(userEnabled, platformBlurSdkLevel)

    /** May anything in the app blur, by any technique. */
    val any: Boolean get() = userEnabled
}

fun blurRenderEffectAllowed(
    userEnabled: Boolean,
    sdkInt: Int,
): Boolean = userEnabled && sdkInt >= ANDROID_S_SDK

fun blurCpuFallbackAllowed(
    userEnabled: Boolean,
    sdkInt: Int,
): Boolean = userEnabled && sdkInt < ANDROID_S_SDK

val LocalBlurPolicy = staticCompositionLocalOf { BlurPolicy(userEnabled = true) }

/**
 * The system's animator duration scale, as set in developer options.
 *
 * 1.0 is the default; 0.5 halves every duration; 0 turns animations off system-wide. Compose feeds
 * this into its own clock, so durations already shorten on their own — what it does not do is tell
 * a spring to settle in fewer oscillations, which is why a springy transition reads as stepping
 * rather than as speed once the scale drops. [LocalAnimationScale] carries the value so a spec can
 * answer for itself.
 */
val LocalAnimationScale = compositionLocalOf { 1f }

/** The pure math behind every scaled wait and spec below: the span as scaled by [scale]. */
fun scaleDuration(
    millis: Long,
    scale: Float,
): Long = (millis * scale).roundToLong()

/**
 * Waits for a span that mirrors an on-screen animation, stretched or shrunk with the animator
 * duration scale; returns at once when animations are off. Must run in a composition-scoped
 * coroutine (LaunchedEffect / rememberCoroutineScope); elsewhere there is no MotionDurationScale
 * and the delay is unscaled.
 */
suspend fun scaledDelay(millis: Long) {
    val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    if (scale <= 0f || millis <= 0L) return
    delay(scaleDuration(millis, scale))
}

/**
 * A spring that stays smooth below 1x — no overshoot to squeeze into fewer frames, the same
 * adaptation as [moe.rukamori.archivetune.ui.screens.settings.SettingsAnimations.pressSpring] — and
 * snaps at 0, where the system has turned animation off. At 1x and above it is the plain spring;
 * Compose shortens it through the animation scale on its own.
 */
fun <T> scaleAwareSpring(
    scale: Float,
    dampingRatio: Float = Spring.DampingRatioNoBouncy,
    stiffness: Float = Spring.StiffnessMedium,
    visibilityThreshold: T? = null,
): FiniteAnimationSpec<T> =
    when {
        scale <= 0f -> snap()
        scale >= 1f -> spring(dampingRatio, stiffness, visibilityThreshold)
        else ->
            spring(
                dampingRatio = 1f + (dampingRatio - 1f) * scale,
                stiffness = stiffness * (0.5f + 0.5f * scale),
                visibilityThreshold = visibilityThreshold,
            )
    }

/**
 * App-wide scrollbar toggle. When `true`, all LazyColumn / LazyGrid /
 * ScrollState scrollbars in the app are suppressed. Provided by MainActivity
 * from the [HideScrollbarKey] preference; consumed by the scrollbar modifier
 * extension in [moe.rukamori.archivetune.ui.utils.ScrollUtils].
 */
val LocalHideScrollbar = compositionLocalOf { false }

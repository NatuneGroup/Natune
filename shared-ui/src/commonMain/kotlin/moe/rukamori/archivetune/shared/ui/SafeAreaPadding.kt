package moe.rukamori.archivetune.shared.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The window's safe area in physical edges, as the host measured it. UIKit reports left/right physically, so this is
 * never mirrored: a landscape notch stays on the side it physically occupies under RTL too.
 */
data class SafeAreaPadding(
    val left: Double = 0.0,
    val top: Double = 0.0,
    val right: Double = 0.0,
    val bottom: Double = 0.0,
) {
    val hasInsets: Boolean get() = left > 0.0 || top > 0.0 || right > 0.0 || bottom > 0.0

    companion object {
        val None = SafeAreaPadding()
    }
}

/** UIKit reports points; a window that is tearing down can report a negative or non-finite edge, so clamp it. */
fun SafeAreaPadding.sanitized(): SafeAreaPadding = SafeAreaPadding(
    left = left.sanePoints(),
    top = top.sanePoints(),
    right = right.sanePoints(),
    bottom = bottom.sanePoints(),
)

private fun Double.sanePoints(): Double = if (!isFinite() || this < 0.0) 0.0 else coerceAtMost(MAX_SAFE_AREA_POINTS)

internal const val MAX_SAFE_AREA_POINTS = 320.0

/**
 * Whether the host has a window safe area to reserve at all. Android and desktop own their own bar insets, so a host
 * that supplies none must stay untouched: the padding and the consumption below are skipped together.
 */
internal fun SafeAreaPadding.needsRootPadding(): Boolean = hasInsets

/**
 * The safe area the host measured, provided once by [ArchiveTuneRoot]. A surface that must paint edge to edge (the
 * expanded player) draws its background outside this inset and reserves the inset around its own controls, so it
 * cannot use the root's padding itself; every other screen keeps consuming it through
 * [LocalShellContentPadding] and the root. [SafeAreaPadding.None] on a host with no host-measured safe area.
 */
val LocalHostSafeArea = staticCompositionLocalOf { SafeAreaPadding.None }

/**
 * Reserves a host-measured safe area once, for the whole app. [absolutePadding] keeps the physical edges (never
 * swapped by layout direction), and consuming the same bar types means a screen that also reads
 * [WindowInsets.statusBars] or [WindowInsets.navigationBars] cannot reserve them a second time. The IME and the
 * display cutout keep their own insets, because this padding never includes them. Composed because reading
 * [WindowInsets.systemBars] is a composition-time query.
 */
@Composable
fun Modifier.safeAreaPadding(insets: SafeAreaPadding): Modifier =
    if (!insets.needsRootPadding()) this
    else absolutePadding(
        left = insets.left.dp,
        top = insets.top.dp,
        right = insets.right.dp,
        bottom = insets.bottom.dp,
    ).consumeWindowInsets(WindowInsets.systemBars)

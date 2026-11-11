/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.constants.PlayerDesignStyle
import moe.rukamori.archivetune.constants.QueuePeekHeight

/** One dispatch context for both orientations, scoped to player presentation, never audio policy. */
@Immutable
data class PlayerPresentation(
    val sharedBackground: Boolean = false,
    val hideSleeve: Boolean = false,
    val sleeveRadius: Float? = null,
    val swipeArtwork: Boolean = true,
    val showCodec: Boolean = true,
    val showVolume: Boolean = true,
) {
    fun radius(native: Dp): Dp = sleeveRadius?.takeIf { it.isFinite() }?.coerceIn(0f, 64f)?.dp ?: native
}

val LocalPlayerPresentation = staticCompositionLocalOf { PlayerPresentation() }

/** Keep the slot and morph bounds mounted; hide decorative pixels, not full-bleed playing media. */
fun Modifier.ordinarySleeve(hidden: Boolean): Modifier =
    if (hidden) graphicsLayer { alpha = 0f } else this

/**
 * The collapsed queue peek. V9, V10 and Looper draw their codec in the player, so their peek is the
 * rail alone, plus the sleep pill when one shows. Metrolist keeps its codec row in the peek.
 */
fun queuePeekHeight(
    style: PlayerDesignStyle,
    showCodecOnPlayer: Boolean,
    sleepTimerEnabled: Boolean,
): Dp =
    when {
        // V5 is named on its own because it hides the peek without painting a backdrop of its own —
        // it is the one style here that is not simply "has a custom backdrop".
        style == PlayerDesignStyle.V5 || style.hasCustomBackdrop -> 0.dp
        style in RailQueuePeekStyles -> 88.dp + (if (sleepTimerEnabled) 42.dp else 0.dp)
        style == PlayerDesignStyle.METROLIST_KMP && showCodecOnPlayer -> 88.dp
        else -> QueuePeekHeight
    }

private val RailQueuePeekStyles =
    setOf(PlayerDesignStyle.V9, PlayerDesignStyle.V10, PlayerDesignStyle.LOOPER)

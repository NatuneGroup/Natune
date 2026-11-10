package moe.rukamori.archivetune.ui.player.bitchord

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import moe.rukamori.archivetune.ui.player.playerPressFeedback
import moe.rukamori.archivetune.LocalAnimationScale
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.shared.ui.player.PlayerPlayPauseGlyph
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.ui.player.PlayerControlCrossfade
import moe.rukamori.archivetune.ui.player.PlayerControlMotion

/**
 * Translucent circular button used for the track menu and the like control.
 *
 * [active] brightens the disc rather than only the glyph: this sits on album
 * artwork of any colour, and a white icon on a white-ish sleeve has no tint
 * change left to make. The filled heart carries the state as a shape too.
 */
@Composable
internal fun CircleGlyph(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
    haptic: Haptic = Haptic.Tap,
    iconModifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val discAlpha by animateFloatAsState(
        targetValue = if (active) 0.34f else 0.18f,
        label = "glyphDisc",
    )
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = discAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.play(haptic)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(19.dp).then(iconModifier),
        )
    }
}

/**
 * Transport / bottom glyphs. The circular clip belongs on the touch target,
 * never on the [Icon] — clipping the icon itself shaves the corners off wide
 * glyphs like fast-forward and the queue list.
 */
@Composable
internal fun TransportGlyph(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
    haptic: Haptic = Haptic.Tap,
    motion: PlayerControlMotion? = null,
) {
    val haptics = rememberHaptics()
    // Faded rather than hidden: the row keeps its shape at the ends of a queue.
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.3f,
        label = "transportAlpha",
    )
    Box(
        modifier = Modifier
            .size(size + 12.dp).playerPressFeedback()
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) {
                haptics.play(haptic)
                motion?.tap()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (icon == Icons.Rounded.Pause || icon == Icons.Rounded.PlayArrow) {
            PlayerPlayPauseGlyph(icon == Icons.Rounded.Pause, Color.White.copy(alpha = alpha),
                Modifier.size(size).then(motion?.modifier ?: Modifier),
                !LocalAnimationsDisabled.current && LocalAnimationScale.current > 0f, contentDescription)
        } else PlayerControlCrossfade(icon) { glyph ->
            Icon(
                imageVector = glyph,
                contentDescription = contentDescription,
                tint = Color.White.copy(alpha = alpha),
                modifier = Modifier.size(size).then(motion?.modifier ?: Modifier),
            )
        }
    }
}

/**
 * The back glyph, in its own composable so its position read is scoped here.
 *
 * A value-returning @Composable would not have done: Compose does not make those restartable, so
 * the read would have landed in the caller and invalidated the whole player anyway.
 */
@Composable
internal fun BitChordPreviousGlyph(
    positionProvider: () -> Long,
    canSkipPrevious: Boolean,
    onClick: () -> Unit,
    motion: PlayerControlMotion? = null,
) {
    TransportGlyph(
        icon = Icons.Rounded.FastRewind,
        contentDescription = "Previous",
        size = 48.dp,
        onClick = onClick,
        motion = motion,
        // Lit whenever back has something to do — either a track to step to, or enough elapsed for
        // it to restart this one.
        enabled = canSkipPrevious || positionProvider() > BACK_RESTARTS_AFTER_MS,
        haptic = Haptic.SkipPrevious,
    )
}

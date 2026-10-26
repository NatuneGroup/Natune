package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.glass.glassGlowOverlay
import moe.rukamori.archivetune.shared.ui.glass.liquidGlassMaterial

/**
 * One round button of the compact bottom UI, ported from the baseline CompactControlCircle: with a backdrop the
 * circle is liquid glass with the glass glow on top; otherwise it is a tonal surface with a small shadow so it still
 * reads as chrome above the page.
 */
@Composable
fun CompactControlCircle(
    backdrop: PillBackdrop,
    glowStrength: Float,
    tint: Color,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (backdrop == PillBackdrop.LIQUID_GLASS) {
        Box(
            modifier
                .size(CompactControlSize)
                .liquidGlassMaterial(
                    shape = CircleShape,
                    interactive = true,
                    baseColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                .glassGlowOverlay(glowStrength, CircleShape)
                .clip(CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) { content() }
    } else {
        Surface(
            modifier = modifier.size(CompactControlSize),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = tint,
            shadowElevation = 3.dp,
        ) {
            Box(
                Modifier
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                    ),
                contentAlignment = Alignment.Center,
            ) { content() }
        }
    }
}

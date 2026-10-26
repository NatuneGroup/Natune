package moe.rukamori.archivetune.shared.ui.glass

import androidx.compose.foundation.border
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop

/**
 * True while the Liquid Glass look is wanted and drawable on this host, ignoring occlusion. Mirrors the baseline's
 * rememberLiquidGlassEnabled (master switch && blur capability); the optical policy already folds in both.
 */
@Composable
fun rememberLiquidGlassEnabled(): Boolean = LocalOpticalPolicy.current.liquidGlassLook

/**
 * A surface container color that goes transparent over a glass backdrop so the material shows through, ported from
 * the baseline's glass-aware surfaces. With glass off — or no backdrop on screen — it is the ordinary container.
 */
@Composable
fun glassAwareContainerColor(
    fallback: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    alpha: Float = 0.4f,
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
): Color =
    if (rememberLiquidGlassEnabled() && backdrop != null) {
        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = alpha)
    } else {
        fallback
    }

/**
 * The card color the baseline uses on glass hosts: a faint tinted surface instead of the solid tonal card, so cards
 * keep reading as glass over the backdrop.
 */
@Composable
fun glassAwareCardColor(
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
): Color =
    if (rememberLiquidGlassEnabled() && backdrop != null) {
        lerp(
            MaterialTheme.colorScheme.surfaceContainerLow,
            MaterialTheme.colorScheme.primary,
            0.05f,
        ).copy(alpha = 0.42f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

/** [glassAwareCardColor] as a CardColors for Material3 Cards. */
@Composable
fun glassAwareCardColors(
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
): androidx.compose.material3.CardColors =
    CardDefaults.cardColors(containerColor = glassAwareCardColor(backdrop))

/**
 * The hairline cards and list rows get on glass hosts: a primary-tinted edge so the boundary still reads over the
 * backdrop. Off-glass it draws nothing, matching the baseline where the border exists only with glass.
 */
fun Modifier.glassAwareCardBorder(
    shape: Shape,
    width: Dp = 1.dp,
): Modifier =
    composed {
        if (rememberLiquidGlassEnabled() && LocalLiquidGlassBackdrop.current != null) {
            border(width, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), shape)
        } else {
            this
        }
    }

/**
 * The large top app bar over a glass backdrop scrolls to a translucent tinted surface instead of the solid
 * scrolled container, so the bar keeps the material behind it readable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun glassAwareLargeTopAppBarColors(
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
): TopAppBarColors {
    val glass = rememberLiquidGlassEnabled() && backdrop != null
    return TopAppBarDefaults.largeTopAppBarColors(
        containerColor = if (glass) Color.Transparent else MaterialTheme.colorScheme.surface,
        scrolledContainerColor =
            if (glass) {
                MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
    )
}

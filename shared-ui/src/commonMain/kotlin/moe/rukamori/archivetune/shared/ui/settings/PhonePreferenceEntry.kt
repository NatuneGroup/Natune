package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.glass.motionAllowed
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** Port of Android PreferenceEntry's segmented shape, icon badge, spacing and text hierarchy. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PhonePreferenceEntry(
    headline: @Composable () -> Unit,
    supporting: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    icon: DrawableResource?,
    index: Int,
    count: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) .96f else 1f,
        animationSpec = if (motionAllowed()) spring(dampingRatio = .6f) else snap(), label = "preference-press")
    val large = 22.dp
    val small = 5.dp
    val shape = when {
        count <= 1 -> RoundedCornerShape(large)
        index == 0 -> RoundedCornerShape(large, large, small, small)
        index == count - 1 -> RoundedCornerShape(small, small, large, large)
        else -> RoundedCornerShape(small)
    }
    val iconShape = if (LocalPhoneSettingsRoot.current) CircleShape else when (index % 4) {
        0 -> MaterialShapes.Ghostish.toShape()
        1 -> MaterialShapes.Clover4Leaf.toShape()
        2 -> MaterialShapes.Cookie9Sided.toShape()
        else -> MaterialShapes.Pill.toShape()
    }
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < .5f
    val background = if (dark) colors.onSurface.copy(alpha = .08f) else Color.White.copy(alpha = .65f)
    val border = colors.primary.copy(alpha = if (dark) .10f else .14f)
    val topAlpha = if (index == 0) .20f else .08f
    val bottomAlpha = if (index == count - 1) .04f else .08f
    Row(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }.fillMaxWidth()
            .padding(horizontal = 16.dp).padding(top = if (index == 0) 0.dp else 1.5.dp)
            .clip(shape).background(background, shape)
            .border(.5.dp, Brush.verticalGradient(listOf(border.copy(alpha = topAlpha), border.copy(alpha = bottomAlpha))), shape)
            .alpha(if (enabled) 1f else .5f)
            .heightIn(min = 72.dp).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(46.dp).clip(iconShape).background(colors.primary), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, Modifier.size(25.dp), tint = colors.onPrimary)
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            ProvideTextStyle(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = colors.onSurface)) {
                headline()
            }
            Spacer(Modifier.height(2.dp))
            ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = colors.onSurfaceVariant)) { supporting() }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

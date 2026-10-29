package moe.rukamori.archivetune.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

private val AppleMusicChipSize = 30.dp

/** The round translucent chip (star, more) beside the Apple Music title. */
@Composable
internal fun AppleMusicChip(
    iconRes: Int,
    tint: Color,
    contentDescription: String?,
    onClick: () -> Unit,
    onPositioned: ((Rect) -> Unit)? = null,
    iconModifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(AppleMusicChipSize)
                .let { base ->
                    if (onPositioned != null) {
                        base.onGloballyPositioned { coords -> onPositioned(coords.boundsInRoot()) }
                    } else {
                        base
                    }
                }
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(onClick = onClick),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp).then(iconModifier),
        )
    }
}

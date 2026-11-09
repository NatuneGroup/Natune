package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** The Android back affordance retains its long-press return-to-main behavior on the shared phone surface. */
@Composable
internal fun SettingsBackButton(onClick: () -> Unit, onLongClick: () -> Unit, colors: IconButtonColors,
    modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier.size(48.dp), shape = CircleShape, color = colors.containerColor) {
        Box(Modifier.combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides colors.contentColor, content = content)
        }
    }
}

package moe.rukamori.archivetune.shared.ui.menu

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect

/** The clicked Compose control supplies real window geometry for the Apple menu popup. */
class OverflowMenuAnchor {
    var bounds: Rect? by mutableStateOf(null)
}
val LocalOverflowMenuAnchor = staticCompositionLocalOf<OverflowMenuAnchor?> { null }

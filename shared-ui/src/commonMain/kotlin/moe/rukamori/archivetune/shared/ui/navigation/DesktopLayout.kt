package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True only while the desktop host draws its own shell chrome: the persistent top bar, the labelled sidebar and the
 * wider page layouts that go with them. Every other host leaves it off, so the phone shell and page geometry stay
 * exactly as they are.
 */
val LocalDesktopLayout = staticCompositionLocalOf { false }

/**
 * Focus requests for a host-drawn global search field. Ctrl+K bumps [request]; the field consumes the newest value
 * once. The counter, not a boolean, so a second request while the field is already focused still runs its focus.
 */
@Stable
class DesktopSearchFocus {
    var request by mutableIntStateOf(0)
        private set

    fun requestFocus() {
        request++
    }
}

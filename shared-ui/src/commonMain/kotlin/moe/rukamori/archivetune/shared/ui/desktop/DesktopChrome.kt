package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Chrome hooks the shared shell declares and the JVM host supplies. The shared UI never imports a
 * desktop-only API here: the host owns the implementations and the other hosts keep the neutral
 * defaults, so a phone renders exactly as it did. Only desktop chrome reads these; page composables
 * stay platform-agnostic.
 */

/**
 * Which host paints the window's own caption. Windows removes its non-client area and answers DWM's
 * Mica and immersive-dark attributes, so its top band is raised a tonal step above the page instead
 * of sharing the artwork tint; Linux draws the reference build's flat Material band. Metrolist rc8
 * draws Windows 11 caption buttons with hover/press states and a red close, while its Linux build
 * keeps thin flat glyphs over the page tint. The shell geometry stays identical on both.
 */
enum class DesktopCaptionChrome {
    System,
    Windows,
    Linux,
}

val LocalDesktopCaptionChrome = staticCompositionLocalOf { DesktopCaptionChrome.System }

private val NoopListScrollbar: @Composable (LazyListState, Modifier) -> Unit = { _, _ -> }

/**
 * A host-drawn vertical scrollbar for a settings [LazyListState]. The desktop host binds it to
 * Compose Desktop's `VerticalScrollbar`; every other host keeps the no-op, so no scrollbar appears
 * on a phone. The list keeps ownership of its state and the scrollbar only reads it, so `scrollTo`
 * anchors and the reader's place behave identically on every host. The reference desktop build
 * draws a thin scrollbar at the right edge of its settings and queue lists.
 */
val LocalDesktopListScrollbar = staticCompositionLocalOf { NoopListScrollbar }

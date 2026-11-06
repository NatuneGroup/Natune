package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * The desktop shell's geometry, taken from the reference desktop app so the window reads as one product on every
 * monitor: the labelled rail, the 48 dp title bar, the 88 dp player bar under both, and the 360 dp side panes.
 *
 * The numbers are plain dp, not adaptive elevations, because the whole window is measured here: the shell decides
 * which column exists, and the pages below only lay out inside the width it reports through `BoxWithConstraints`.
 */
object DesktopShellMetrics {
    /** Wide rail with icon above a small label; the collapsed rail keeps only the icon. */
    val railExpandedWidth = 180.dp
    val railCollapsedWidth = 96.dp
    val railItemHeight = 64.dp
    val railItemCorner = 16.dp
    val railItemIcon = 22.dp

    /** The window's own top strip: search and window buttons, above the rail and the page. */
    val titleBarHeight = 48.dp
    val titleBarPadding = 8.dp
    val titleBarSearchGap = 4.dp
    val searchFieldHeight = 36.dp
    /** Below this window width the search field takes its narrow size instead of the wide one. */
    val searchTightWindowWidth = 960.dp
    val searchFieldNarrowWidth = 280.dp
    val searchFieldWideWidth = 420.dp

    /** Linux and the Windows fallback paint their own caption strip; Windows' native frame is 48 dp in total. */
    val captionHeight = 32.dp
    val captionButtonWidth = 46.dp
    val captionButtonHeight = 32.dp

    /** The bottom player: one full-window row with the seek track above the transport row. */
    val playerBarHeight = 88.dp
    val playerSeekTrackHeight = 16.dp
    val playerControlsHeight = 72.dp
    val playerArtworkSize = 44.dp
    val playerVolumeWidth = 88.dp

    /** A page column narrower than this next to a pane would clip the page's own rows. */
    val primaryPaneMinWidth = 560.dp
    val supportingPaneMinWidth = 240.dp
    /** Queue and lyrics panes: 360 dp by default, never narrower than 280 dp, resettable by double click. */
    val paneDefaultWidth = 360.dp
    val paneMinWidth = 280.dp
    val paneMaxWidthFraction = 0.6f
    val paneHeaderHeight = 56.dp

    /** The page sits on a rounded, outlined surface inside the shell, as the reference app's content frame does. */
    val contentCorner = 16.dp
    val contentOutline = 1.dp
    val contentMaxWidth = 1200.dp
    val contentPadding = 16.dp

    /** The expanded player: artwork and controls beside the lyrics, capped so a 4K window does not stretch them. */
    val expandedPlayerMaxWidth = 1800.dp
    val expandedArtworkMin = 320.dp
    val expandedArtworkMax = 560.dp
    val expandedPlayerPadding = 32.dp
    /** Below this height the expanded player stacks in a scrollable column instead of two side-by-side columns. */
    val expandedTwoColumnMinHeight = 560.dp

    /** A window narrower than rail + page + pane cannot dock a pane without squeezing the page. */
    val paneDockMinWindowWidth = 1040.dp
    /** A short window keeps its page and shows panes over it instead of beside it. */
    val paneDockMinHeight = 480.dp
}

/**
 * Width classes of the reference app: 600 / 840 / 1200 / 1600 dp. They drive the rail's expansion, how many docked
 * panes fit and whether the search takes its narrow width.
 */
enum class DesktopWidthClass { Compact, Medium, Expanded, Large, ExtraLarge }

/** Height classes of the reference app: 480 / 900 dp. A compact height drops the labelled rail and stacks columns. */
enum class DesktopHeightClass { Compact, Medium, Expanded }

fun desktopWidthClass(width: Dp): DesktopWidthClass = when {
    width >= 1600.dp -> DesktopWidthClass.ExtraLarge
    width >= 1200.dp -> DesktopWidthClass.Large
    width >= 840.dp -> DesktopWidthClass.Expanded
    width >= 600.dp -> DesktopWidthClass.Medium
    else -> DesktopWidthClass.Compact
}

fun desktopHeightClass(height: Dp): DesktopHeightClass = when {
    height >= 900.dp -> DesktopHeightClass.Expanded
    height >= 480.dp -> DesktopHeightClass.Medium
    else -> DesktopHeightClass.Compact
}

/** Where a requested side pane goes: docked beside the page, over it, or nowhere because it was not asked for. */
enum class DesktopPaneMode { Hidden, Docked, Overlay }

fun desktopPaneMode(windowWidth: Dp, windowHeight: Dp, requested: Boolean): DesktopPaneMode = when {
    !requested -> DesktopPaneMode.Hidden
    windowHeight < DesktopShellMetrics.paneDockMinHeight -> DesktopPaneMode.Overlay
    windowWidth < DesktopShellMetrics.paneDockMinWindowWidth -> DesktopPaneMode.Overlay
    else -> DesktopPaneMode.Docked
}

/**
 * How many panes the window carries: two from 1200 dp (page plus queue plus lyrics), one from 840 dp, and one over
 * the page below that. The reference app's maximum pane count follows the same width steps.
 */
fun desktopPaneCapacity(windowWidth: Dp, windowHeight: Dp): Int {
    if (desktopHeightClass(windowHeight) == DesktopHeightClass.Compact) return 1
    return when (desktopWidthClass(windowWidth)) {
        DesktopWidthClass.ExtraLarge, DesktopWidthClass.Large -> 2
        DesktopWidthClass.Expanded, DesktopWidthClass.Medium -> 1
        DesktopWidthClass.Compact -> 1
    }
}

/** The rail is labelled from 840 dp and while the window is tall enough for two-line items. */
fun desktopRailExpanded(windowWidth: Dp, windowHeight: Dp, userExpanded: Boolean): Boolean =
    userExpanded &&
        windowWidth >= 840.dp &&
        desktopHeightClass(windowHeight) != DesktopHeightClass.Compact

/** The search field's width for the window it sits in; it keeps room for the rail toggle and the window buttons. */
fun desktopSearchFieldWidth(windowWidth: Dp): Dp = if (windowWidth < DesktopShellMetrics.searchTightWindowWidth) {
    DesktopShellMetrics.searchFieldNarrowWidth
} else {
    DesktopShellMetrics.searchFieldWideWidth
}

/**
 * A pane width that leaves the page its [DesktopShellMetrics.primaryPaneMinWidth]. The result never goes below the
 * pane minimum, so a drag cannot make a pane vanish, and the page column is what narrows first.
 */
fun clampPaneWidth(requested: Dp, windowWidth: Dp, railWidth: Dp, otherPanes: Int): Dp {
    val paneMin = DesktopShellMetrics.paneMinWidth.value
    val available = windowWidth.value - railWidth.value - paneMin * otherPanes
    val ceiling = max(paneMin, available - DesktopShellMetrics.primaryPaneMinWidth.value)
    return max(paneMin, minOf(requested.value, ceiling)).dp
}

/** The overlay pane's width: it may cover the page, but it always leaves the rail and a strip of page visible. */
fun overlayPaneWidth(requested: Dp, windowWidth: Dp, railWidth: Dp): Dp {
    val paneMin = DesktopShellMetrics.paneMinWidth.value
    val ceiling = (windowWidth.value - railWidth.value) * 0.85f
    return max(paneMin, minOf(requested.value, ceiling)).dp
}

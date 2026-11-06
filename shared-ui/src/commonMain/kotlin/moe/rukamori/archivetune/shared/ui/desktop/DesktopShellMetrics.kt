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
    /**
     * The rail toggle's touch target in the title bar: the 48 dp interactive minimum of its icon button. The Windows
     * frame publishes it as a client region, so the click reaches Compose instead of dragging the window.
     */
    val titleBarRailToggleWidth = 48.dp

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
    /** The drag target between the page and a docked pane; it is spent from the window's pane budget. */
    val paneDividerWidth = 6.dp

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

    /** A short window keeps its page and shows panes over it instead of beside it. */
    val paneDockMinHeight = 480.dp
}

/**
 * Width classes of the reference app: 600 / 840 / 1200 / 1600 dp. They drive the rail's expansion, the size of the
 * expanded player and whether the search takes its narrow width. Docked panes are *not* decided from a class: see
 * [desktopPaneSlots], which budgets their real widths.
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

/** Where a pane the user asked for is drawn: beside the page, or over it when the window has no room for both. */
enum class DesktopPanePlacement { Docked, Overlay }

/** A pane the user asked for, with the width its divider last gave it. */
data class DesktopPaneRequest(val pane: DesktopPane, val width: Dp)

/** One pane the shell draws: the width it gets and whether it sits beside the page or over it. */
data class DesktopPaneSlot(val pane: DesktopPane, val width: Dp, val placement: DesktopPanePlacement)

/**
 * The window's pane budget: the one place that decides which panes are drawn and how wide they are.
 *
 * The budget is measured in real dp, not in width classes: the rail, the page's own
 * [DesktopShellMetrics.primaryPaneMinWidth], one [DesktopShellMetrics.paneDividerWidth] per docked pane and each open
 * pane's actual width. A pane is docked only while the page keeps its minimum, and narrows to
 * [DesktopShellMetrics.paneMinWidth] before it loses its slot. A pane that still does not fit covers the page when it
 * is the one the user opened last ([active]); every other overflow pane is left out of the layout, so the state can
 * follow the layout instead of a closed pane counting as open. A window shorter than
 * [DesktopShellMetrics.paneDockMinHeight] never docks: the active pane covers the page.
 *
 * Two 360 dp panes do not fit at 1200 x 720 even though the width class says "two panes": 180 rail + 560 page + 6 + 360
 * leaves 94 dp, so the second pane covers the page while the first stays docked.
 */
fun desktopPaneSlots(
    windowWidth: Dp,
    windowHeight: Dp,
    railWidth: Dp,
    open: List<DesktopPaneRequest>,
    active: DesktopPane?,
): List<DesktopPaneSlot> {
    if (open.isEmpty()) return emptyList()
    val pageMinimum = DesktopShellMetrics.primaryPaneMinWidth.value
    val paneMinimum = DesktopShellMetrics.paneMinWidth.value
    val divider = DesktopShellMetrics.paneDividerWidth.value
    fun overlay(request: DesktopPaneRequest): DesktopPaneSlot = DesktopPaneSlot(
        request.pane,
        overlayPaneWidth(request.width, windowWidth, railWidth),
        DesktopPanePlacement.Overlay,
    )
    if (windowHeight < DesktopShellMetrics.paneDockMinHeight) {
        // A short window has no vertical room for a second column.
        return listOf(overlay(open.firstOrNull { it.pane == active } ?: open.last()))
    }
    val budget = windowWidth.value - railWidth.value - pageMinimum
    var used = 0f
    val docked = mutableListOf<DesktopPaneSlot>()
    open.forEach { request ->
        val width = minOf(request.width.value.coerceAtLeast(paneMinimum), budget - used - divider)
        if (width >= paneMinimum) {
            docked += DesktopPaneSlot(request.pane, width.dp, DesktopPanePlacement.Docked)
            used += width + divider
        }
    }
    val covering = open.firstOrNull { request ->
        request.pane == active && docked.none { it.pane == request.pane }
    }
    return if (covering == null) docked else docked + overlay(covering)
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

/** The overlay pane's width: it may cover the page, but it always leaves the rail and a strip of page visible. */
fun overlayPaneWidth(requested: Dp, windowWidth: Dp, railWidth: Dp): Dp {
    val paneMin = DesktopShellMetrics.paneMinWidth.value
    val ceiling = (windowWidth.value - railWidth.value) * 0.85f
    return max(paneMin, minOf(requested.value, ceiling)).dp
}

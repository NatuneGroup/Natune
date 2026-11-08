package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.theme.Space

/** The panes in docking order: the queue first, then the lyrics. */
private val PaneOrder = listOf(DesktopPane.Queue, DesktopPane.Lyrics)

/**
 * The desktop window's scaffold, shaped like the reference desktop app:
 *
 * ```
 * title bar   (search, navigation toggle, window buttons)
 * rail | page surface                        | queue pane | lyrics pane
 * player bar
 * ```
 *
 * The title bar and the player bar span the whole window, the rail sits beside the page and the panes dock to the
 * right of it. Nothing here owns the queue, the lyrics, the playback state or the routes: the host passes the two
 * panes and the player bar in, so the shell only decides which column exists and how wide it is.
 *
 * Geometry is decided once per window size from [DesktopShellMetrics]: the rail collapses to icons below 840 dp, a
 * pane docks only while the page keeps its own minimum width, and a short window moves a pane over the page instead
 * of squeezing both. Every column scrolls its own content, so the 800 x 500 minimum window never clips a control.
 */
@Composable
fun DesktopShell(
    navigation: ShellNavigation,
    state: DesktopShellState,
    titleBar: @Composable () -> Unit,
    playerBar: @Composable () -> Unit,
    queuePane: @Composable (Modifier) -> Unit,
    lyricsPane: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("desktop-shell"),
    ) {
        val windowWidth = maxWidth
        val windowHeight = maxHeight
        val density = LocalDensity.current
        val railExpanded = desktopRailExpanded(windowWidth, windowHeight, state.railExpanded)
        val railWidth = desktopRailWidth(railExpanded)
        val open = PaneOrder.filter { state.isOpen(it) }.map { DesktopPaneRequest(it, state.width(it)) }
        // One budget for the whole window: which panes are drawn, how wide they are and whether they sit beside the
        // page or over it. The pixels a drag reports are converted here, once, so the pane state stays in dp.
        val slots = desktopPaneSlots(windowWidth, windowHeight, railWidth, open, state.activePane)
        // The state follows the layout: a pane the window cannot draw is closed, or a closed pane would still count
        // as open for the shortcut that toggles it.
        SideEffect {
            open.forEach { request ->
                if (slots.none { it.pane == request.pane }) state.close(request.pane)
            }
        }
        Column(Modifier.fillMaxSize()) {
            titleBar()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                DesktopRail(navigation, railExpanded)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    DesktopContentSurface { pageModifier -> content(pageModifier) }
                    OverlayPanes(state, slots, queuePane, lyricsPane)
                }
                DockedPanes(state, slots, density, queuePane, lyricsPane)
            }
            playerBar()
        }
    }
}

/** The page sits on its own rounded surface, inset from the shell so the rail and the panes read as separate columns. */
@Composable
private fun DesktopContentSurface(content: @Composable (Modifier) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(DesktopShellMetrics.contentCorner),
        border = BorderStroke(DesktopShellMetrics.contentOutline, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxSize()
            .padding(top = Space.xs, bottom = Space.xs, end = Space.xs)
            .testTag("desktop-content-surface"),
    ) {
        // Pages keep their own padding; the shell only caps how wide a page grows on a very wide monitor.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = DesktopShellMetrics.contentMaxWidth).fillMaxHeight()) {
                content(Modifier.fillMaxSize())
            }
        }
    }
}

/** The panes that sit beside the page, in the order the reference app docks them: queue first, then lyrics. */
@Composable
private fun RowScope.DockedPanes(
    state: DesktopShellState,
    slots: List<DesktopPaneSlot>,
    density: Density,
    queuePane: @Composable (Modifier) -> Unit,
    lyricsPane: @Composable (Modifier) -> Unit,
) {
    slots.forEach { slot ->
        if (slot.placement != DesktopPanePlacement.Docked) return@forEach
        DesktopPaneDivider(
            pane = slot.pane,
            // The divider reports device pixels; the pane state is dp, and the drag starts from the width the pane is
            // drawn at, so dragging a pane the window narrowed still tracks the pointer.
            onDrag = { delta -> state.resize(slot.pane, slot.width + with(density) { delta.toDp() }) },
            onReset = { state.resetWidth(slot.pane) },
        )
        DockedPane(slot.pane, slot.width, state, queuePane, lyricsPane)
    }
}

/** The panes that sit over the page, for a window too narrow or too short to carry them beside it. */
@Composable
private fun BoxScope.OverlayPanes(
    state: DesktopShellState,
    slots: List<DesktopPaneSlot>,
    queuePane: @Composable (Modifier) -> Unit,
    lyricsPane: @Composable (Modifier) -> Unit,
) {
    slots.forEach { slot ->
        if (slot.placement != DesktopPanePlacement.Overlay) return@forEach
        DesktopPaneOverlay(
            pane = slot.pane,
            width = slot.width,
            onClose = { state.close(slot.pane) },
        ) {
            DesktopPaneContent(slot.pane, queuePane, lyricsPane)
        }
    }
}

/** One docked pane: its surface and the pane content the host supplies for it. */
@Composable
private fun DockedPane(
    pane: DesktopPane,
    width: Dp,
    state: DesktopShellState,
    queuePane: @Composable (Modifier) -> Unit,
    lyricsPane: @Composable (Modifier) -> Unit,
) {
    DesktopPaneSurface(pane = pane, width = width, onClose = { state.close(pane) }) {
        DesktopPaneContent(pane, queuePane, lyricsPane)
    }
}

@Composable
private fun DesktopPaneContent(
    pane: DesktopPane,
    queuePane: @Composable (Modifier) -> Unit,
    lyricsPane: @Composable (Modifier) -> Unit,
) {
    val paneModifier = Modifier
        .fillMaxSize()
        .padding(horizontal = Space.xs)
        .testTag("desktop-pane-content-${pane.name.lowercase()}")
    when (pane) {
        DesktopPane.Queue -> queuePane(paneModifier)
        DesktopPane.Lyrics -> lyricsPane(paneModifier)
    }
}

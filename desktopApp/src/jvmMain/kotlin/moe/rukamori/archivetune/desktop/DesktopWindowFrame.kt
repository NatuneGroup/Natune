package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Window
import moe.rukamori.archivetune.desktop.integration.linux.LinuxWindowDrag
import moe.rukamori.archivetune.desktop.integration.windows.WindowsCustomFrame
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.desktopSystemWindowFrame
import moe.rukamori.archivetune.shared.ui.desktop.DesktopShellMetrics
import moe.rukamori.archivetune.shared.ui.desktop.LocalDesktopWindowControls
import moe.rukamori.archivetune.shared.ui.desktop.desktopSearchFieldWidth
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The window's decoration, per operating system:
 *
 * - **Windows**: the non-client area is removed and the app's own title bar carries the caption buttons, with Snap
 *   Layouts over the maximize button, a Mica backdrop and the user's accent on the frame border.
 * - **Linux (X11)**: a 32 dp caption above the app, dragged and resized through `_NET_WM_MOVERESIZE` so the window
 *   manager keeps its own snapping and tiling; the search bar sits below it, which is the 80 dp strip the reference
 *   desktop app shows.
 * - **macOS and Wayland**: no Compose caption. macOS keeps its native title bar and Wayland keeps the compositor's
 *   frame, because neither offers a protocol an app-drawn caption can drive.
 *
 * The frame never owns application state: it decides which pixels are caption and which are the page, and the shell
 * inside it is identical on every platform.
 */
@Composable
fun FrameWindowScope.DesktopWindowFrame(
    mode: DesktopFrameMode,
    systemDark: Boolean,
    windowState: WindowState,
    title: String,
    onRequestClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    when {
        mode == DesktopFrameMode.Custom && DesktopWindowFramePolicy.isWindows() ->
            WindowsFrame(systemDark, windowState, onRequestClose, content)
        mode == DesktopFrameMode.Custom && DesktopWindowFramePolicy.isLinux() ->
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    LinuxCaptionBar(windowState, onRequestClose)
                    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                }
                // The resize border sits above the app inside the caption's own 6 dp, so it never covers content.
                LinuxResizeEdges()
            }
        else -> content()
    }
}

/**
 * Windows: keeps the real window frame (Aero Snap and resizing stay native), removes its non-client area in the window
 * procedure and supplies the caption buttons to the app's title bar through [LocalDesktopWindowControls]. The regions
 * the title bar occupies are published to the procedure, so a click on the search field stays a click, a click on the
 * caption drags the window and the maximize button answers Snap Layouts.
 */
@Composable
private fun FrameWindowScope.WindowsFrame(
    systemDark: Boolean,
    windowState: WindowState,
    onRequestClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    LaunchedEffect(window) {
        val installed = WindowsCustomFrame.install(window)
        DesktopDiagnosticLog.host("window frame: custom=$installed")
    }
    LaunchedEffect(window, systemDark) { WindowsCustomFrame.applyBackdrop(window, systemDark) }
    DisposableEffect(window) { onDispose { WindowsCustomFrame.uninstall() } }
    PublishWindowsHitTest(windowState)
    CompositionLocalProvider(
        LocalDesktopWindowControls provides {
            DesktopCaptionButtons(
                maximized = windowState.placement != WindowPlacement.Floating,
                onMinimize = { windowState.isMinimized = true },
                onToggleMaximize = {
                    windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
                        WindowPlacement.Floating
                    } else {
                        WindowPlacement.Maximized
                    }
                },
                onClose = onRequestClose,
                publishHits = true,
            )
        },
    ) {
        content()
    }
}

/**
 * Publishes the title bar's regions to the Windows procedure. The layout is the one the shell draws, so the regions
 * come from the window width and the search field's width instead of a second measurement of the same pixels.
 */
@Composable
private fun PublishWindowsHitTest(windowState: WindowState) {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current.density
    val container = windowInfo.containerSize
    LaunchedEffect(container, density, windowState.placement) {
        val width = container.width
        if (width <= 0) return@LaunchedEffect
        fun px(value: Dp): Int = (value.value * density).toInt()
        val bar = px(DesktopShellMetrics.titleBarHeight)
        val padding = px(DesktopShellMetrics.titleBarPadding)
        val searchWidth = px(desktopSearchFieldWidth((width / density).dp))
        val center = width / 2
        val searchLeft = (center - searchWidth / 2 - padding).coerceAtLeast(0)
        val searchRight = (center + searchWidth / 2 + padding).coerceAtMost(width)
        val buttonsLeft = (width - padding - px(DesktopShellMetrics.captionButtonWidth * 3)).coerceAtLeast(0)
        // The window buttons end the title bar, and the rail toggle sits immediately left of them, inside the caption
        // strip. It is a client region like the search field: a click on it must reach Compose, not drag the window.
        val railToggleRight = buttonsLeft
        val railToggleLeft = (railToggleRight - px(DesktopShellMetrics.titleBarRailToggleWidth)).coerceAtLeast(0)
        val caption = buildList {
            if (searchLeft > 0) add(WindowsCustomFrame.CaptionArea(WindowsCustomFrame.CAPTION, 0, 0, searchLeft, bar))
            if (railToggleLeft > searchRight) {
                add(WindowsCustomFrame.CaptionArea(WindowsCustomFrame.CAPTION, searchRight, 0, railToggleLeft, bar))
            }
        }
        WindowsCustomFrame.publish(
            caption = caption,
            clientHoles = listOf(
                WindowsCustomFrame.CaptionArea(WindowsCustomFrame.CLIENT, searchLeft, 0, searchRight, bar),
                WindowsCustomFrame.CaptionArea(WindowsCustomFrame.CLIENT, railToggleLeft, 0, railToggleRight, bar),
            ),
        )
    }
}

/**
 * The three caption buttons, drawn by the app on Windows and Linux: 46 x 32 dp each with a glyph drawn rather than an
 * icon resource, so it matches the OS convention at any DPI. On Windows each button also publishes its own hit test so
 * the maximize button answers `HTMAXBUTTON` (Snap Layouts) and the close button answers `HTCLOSE`.
 */
@Composable
internal fun DesktopCaptionButtons(
    maximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    publishHits: Boolean = false,
) {
    Row(modifier.height(DesktopShellMetrics.captionButtonHeight)) {
        CaptionButton(
            label = stringResource(Res.string.desktop_shell_window_minimize),
            glyph = CaptionGlyph.Minimize,
            onClick = onMinimize,
            tag = "minimize",
            hitTest = if (publishHits) WindowsCustomFrame.MINIMIZE_BUTTON else null,
        )
        CaptionButton(
            label = stringResource(
                if (maximized) Res.string.desktop_shell_window_restore else Res.string.desktop_shell_window_maximize,
            ),
            glyph = if (maximized) CaptionGlyph.Restore else CaptionGlyph.Maximize,
            onClick = onToggleMaximize,
            tag = if (maximized) "restore" else "maximize",
            hitTest = if (publishHits) WindowsCustomFrame.MAXIMIZE_BUTTON else null,
        )
        CaptionButton(
            label = stringResource(Res.string.desktop_shell_window_close),
            glyph = CaptionGlyph.Close,
            onClick = onClose,
            tag = "close",
            hitTest = if (publishHits) WindowsCustomFrame.CLOSE_BUTTON else null,
        )
    }
}

private enum class CaptionGlyph { Minimize, Maximize, Restore, Close }

@Composable
private fun CaptionButton(
    label: String,
    glyph: CaptionGlyph,
    onClick: () -> Unit,
    tag: String,
    hitTest: Int?,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current.density
    Box(
        Modifier
            .size(DesktopShellMetrics.captionButtonWidth, DesktopShellMetrics.captionButtonHeight)
            .then(
                if (hitTest == null) {
                    Modifier
                } else {
                    Modifier.onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInWindow()
                        WindowsCustomFrame.publishButton(
                            WindowsCustomFrame.CaptionArea(
                                hitTest,
                                bounds.left.toInt(),
                                bounds.top.toInt(),
                                bounds.right.toInt(),
                                bounds.bottom.toInt(),
                            ),
                        )
                    }
                },
            )
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }
            .testTag("caption-$tag"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) {
            val stroke = Stroke(width = density.coerceAtLeast(1f))
            val w = size.width
            val h = size.height
            when (glyph) {
                CaptionGlyph.Minimize -> drawLine(contentColor, Offset(0f, h / 2), Offset(w, h / 2), stroke.width)
                CaptionGlyph.Maximize -> drawRect(contentColor, Offset.Zero, Size(w, h), style = stroke)
                CaptionGlyph.Restore -> {
                    drawRect(contentColor, Offset(0f, h * 0.25f), Size(w * 0.75f, h * 0.75f), style = stroke)
                    drawLine(contentColor, Offset(w * 0.25f, h * 0.25f), Offset(w * 0.25f, 0f), stroke.width)
                    drawLine(contentColor, Offset(w * 0.25f, 0f), Offset(w, 0f), stroke.width)
                    drawLine(contentColor, Offset(w, 0f), Offset(w, h * 0.75f), stroke.width)
                }
                CaptionGlyph.Close -> {
                    drawLine(contentColor, Offset.Zero, Offset(w, h), stroke.width)
                    drawLine(contentColor, Offset(w, 0f), Offset(0f, h), stroke.width)
                }
            }
        }
    }
}

/**
 * Linux: the 32 dp caption above the title bar. The empty stretch drags and double-clicks to maximize; the right end
 * carries the same three buttons.
 */
@Composable
private fun FrameWindowScope.LinuxCaptionBar(
    windowState: WindowState,
    onRequestClose: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .height(DesktopShellMetrics.captionHeight)
            .testTag("desktop-caption-bar"),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(window) {
                        detectDragGestures(
                            onDragStart = {
                                // The compositor performs the whole move; the app sends one request and lets go.
                                LinuxWindowDrag.startMove(window)
                            },
                        ) { _, _ -> }
                    }
                    .pointerInput(windowState) {
                        detectTapGestures(
                            onDoubleTap = {
                                windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
                                    WindowPlacement.Floating
                                } else {
                                    WindowPlacement.Maximized
                                }
                            },
                            onTap = { },
                        )
                    }
                    .testTag("desktop-caption-drag"),
            )
            DesktopCaptionButtons(
                maximized = windowState.placement == WindowPlacement.Maximized,
                onMinimize = { windowState.isMinimized = true },
                onToggleMaximize = {
                    windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
                        WindowPlacement.Floating
                    } else {
                        WindowPlacement.Maximized
                    }
                },
                onClose = onRequestClose,
            )
        }
    }
}

/** The invisible resize border of the Linux caption: one hit area per direction, each one message to the manager. */
@Composable
private fun FrameWindowScope.LinuxResizeEdges() {
    val edge = 6.dp
    Box(Modifier.fillMaxSize()) {
        ResizeEdge(window, Alignment.TopStart, LinuxWindowDrag.Direction.TOP_LEFT, edge, edge)
        ResizeEdge(window, Alignment.TopEnd, LinuxWindowDrag.Direction.TOP_RIGHT, edge, edge)
        ResizeEdge(window, Alignment.BottomStart, LinuxWindowDrag.Direction.BOTTOM_LEFT, edge, edge)
        ResizeEdge(window, Alignment.BottomEnd, LinuxWindowDrag.Direction.BOTTOM_RIGHT, edge, edge)
        ResizeEdge(window, Alignment.TopCenter, LinuxWindowDrag.Direction.TOP, width = null, height = edge)
        ResizeEdge(window, Alignment.BottomCenter, LinuxWindowDrag.Direction.BOTTOM, width = null, height = edge)
        ResizeEdge(window, Alignment.CenterStart, LinuxWindowDrag.Direction.LEFT, edge, height = null)
        ResizeEdge(window, Alignment.CenterEnd, LinuxWindowDrag.Direction.RIGHT, edge, height = null)
    }
}

@Composable
private fun BoxScope.ResizeEdge(
    window: Window,
    alignment: Alignment,
    direction: Int,
    width: Dp? = null,
    height: Dp? = null,
) {
    Box(
        Modifier
            .then(if (width == null) Modifier.fillMaxWidth() else Modifier.size(width))
            .then(if (height != null) Modifier.height(height) else Modifier.fillMaxHeight())
            .align(alignment)
            .pointerInput(window, direction) {
                detectDragGestures(onDragStart = { LinuxWindowDrag.startResize(window, direction) }) { _, _ -> }
            },
    )
}

/**
 * The preference the settings row owns, mirrored for the next launch. Called whenever the stored value changes, so the
 * launch after a change opens with the frame the user chose: AWT cannot add or remove a decoration on a live window.
 */
internal fun mirrorWindowFramePreference(preferences: PreferenceSnapshot, instanceDirectory: java.nio.file.Path) {
    DesktopWindowFramePolicy.write(instanceDirectory, preferences.desktopSystemWindowFrame)
}

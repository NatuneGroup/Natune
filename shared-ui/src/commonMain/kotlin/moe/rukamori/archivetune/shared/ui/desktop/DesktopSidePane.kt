package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.theme.Space
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** How wide the drag target is: a thin divider that is still easy to grab without moving the page. */
private val DividerHitWidth = 6.dp
private val DividerLineWidth = 1.dp

/** The visible title of a pane, so the pane and its close button never disagree about what it holds. */
internal val DesktopPane.label: StringResource
    get() = when (this) {
        DesktopPane.Queue -> Res.string.desktop_shell_queue_pane
        DesktopPane.Lyrics -> Res.string.desktop_shell_lyrics_pane
    }

/**
 * The divider between the page and a docked pane. Dragging it right narrows the pane, because the pane is on the
 * right; a double click puts it back to its default width.
 */
@Composable
internal fun DesktopPaneDivider(
    pane: DesktopPane,
    onDrag: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(Res.string.desktop_shell_resize_pane, stringResource(pane.label))
    Box(
        modifier
            .width(DividerHitWidth)
            .fillMaxHeight()
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> onDrag(-delta) },
            )
            .pointerInput(pane) { detectTapGestures(onDoubleTap = { onReset() }) }
            .semantics { contentDescription = description }
            .testTag("desktop-pane-divider-${pane.name.lowercase()}"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(DividerLineWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/**
 * One docked pane: a header with the pane's name and its close button over the pane's own content. The content is the
 * existing queue panel or the existing lyrics renderer, so there is exactly one queue owner and one lyrics renderer.
 */
@Composable
internal fun DesktopPaneSurface(
    pane: DesktopPane,
    width: Dp,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val name = stringResource(pane.label)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(DesktopShellMetrics.contentCorner),
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .testTag("desktop-pane-${pane.name.lowercase()}"),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(DesktopShellMetrics.paneHeaderHeight)
                    .padding(start = Space.sm, end = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("desktop-pane-close-${pane.name.lowercase()}"),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.m4c_icon_close),
                        contentDescription = stringResource(Res.string.desktop_shell_close_pane, name),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

/**
 * A pane over the page, for windows too narrow to keep the page at its minimum width beside a pane: the scrim closes
 * it, and the pane keeps the width it had while docked.
 */
@Composable
internal fun DesktopPaneOverlay(
    pane: DesktopPane,
    width: Dp,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().testTag("desktop-pane-overlay")) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = onClose)
                .testTag("desktop-pane-scrim"),
        )
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(Space.xs)
                .clip(RoundedCornerShape(DesktopShellMetrics.contentCorner)),
        ) {
            DesktopPaneSurface(pane = pane, width = width, onClose = onClose, modifier = modifier, content = content)
        }
    }
}

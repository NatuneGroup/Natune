package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.theme.Space
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The navigation rail beside the page: five destinations, icon above a small label while it is expanded and only the
 * icon while it is collapsed. It scrolls when the window is too short for five 64 dp items, so a 500 dp window still
 * reaches Together.
 *
 * The rail is drawn on the shell surface, under the title bar and beside the page surface, like the reference app's
 * sidebar; it never floats over the page and never draws its own shadow.
 */
@Composable
internal fun DesktopRail(
    navigation: ShellNavigation,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val selected = desktopSelectedDestination(navigation.selectedTab, navigation.route)
    Column(
        modifier
            .width(if (expanded) DesktopShellMetrics.railExpandedWidth else DesktopShellMetrics.railCollapsedWidth)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.xs, vertical = Space.xs)
            .testTag("desktop-rail"),
        verticalArrangement = Arrangement.spacedBy(Space.xxs),
    ) {
        DesktopDestination.entries.forEach { destination ->
            DesktopRailItem(
                destination = destination,
                expanded = expanded,
                selected = destination == selected,
                onClick = { destination.open(navigation) },
            )
        }
    }
}

@Composable
private fun DesktopRailItem(
    destination: DesktopDestination,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .height(DesktopShellMetrics.railItemHeight)
            .clip(RoundedCornerShape(DesktopShellMetrics.railItemCorner))
            .background(container)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .testTag("desktop-rail-${destination.name.lowercase()}")
            .padding(horizontal = Space.xs, vertical = Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides content) {
            Box(Modifier.size(DesktopShellMetrics.railItemIcon), contentAlignment = Alignment.Center) {
                destination.Icon(selected, Modifier.size(DesktopShellMetrics.railItemIcon).testTag("desktop-rail-icon-${destination.name.lowercase()}"))
            }
        }
        if (!expanded) return@Column
        Spacer(Modifier.height(Space.xxs))
        Text(
            stringResource(destination.label),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The rail toggle beside the app mark in the title bar; it only collapses the labels, never the navigation. */
@Composable
internal fun DesktopRailToggle(expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onToggle, modifier = modifier.testTag("desktop-rail-toggle")) {
        Icon(
            painter = painterResource(if (expanded) Res.drawable.m4hv_icon_list_view else Res.drawable.m4hv_icon_grid_view),
            contentDescription = stringResource(
                if (expanded) Res.string.desktop_shell_collapse_rail else Res.string.desktop_shell_expand_rail,
            ),
            modifier = Modifier.size(DesktopShellMetrics.railItemIcon),
        )
    }
}

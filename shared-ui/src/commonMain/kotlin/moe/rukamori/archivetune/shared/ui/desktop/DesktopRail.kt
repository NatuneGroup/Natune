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
 * The navigation rail beside the page: five destinations, each a 56 x 32 indicator pill with the icon in it and a
 * one-line label under the pill while the rail is expanded, only the pill while it is collapsed. It is the reference
 * app's 104 dp sidebar, not a second column: the labels ellipsize instead of the rail growing, and the whole item stays
 * one target.
 *
 * It scrolls when the window is too short for five 60 dp items, so a 500 dp window still reaches Together. The rail is
 * drawn on the shell surface, under the title bar and beside the page surface, like the reference app's sidebar; it
 * never floats over the page and never draws its own shadow.
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
            .width(desktopRailWidth(expanded))
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState())
            // The items centre on the 96 dp column the reference centres them on, with its 8 dp gap before the page.
            .padding(
                end = DesktopShellMetrics.railTrailingGap,
                top = Space.xs,
                bottom = Space.xs,
            )
            .testTag("desktop-rail"),
        verticalArrangement = Arrangement.spacedBy(DesktopShellMetrics.railItemSpacing),
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
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .testTag("desktop-rail-${destination.name.lowercase()}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The indicator is the pill behind the icon alone, as the reference draws it; the label below is part of the
        // same item, so clicking it selects the destination too.
        Box(
            Modifier
                .size(
                    width = DesktopShellMetrics.railItemIndicatorWidth,
                    height = DesktopShellMetrics.railItemIndicatorHeight,
                )
                .clip(RoundedCornerShape(DesktopShellMetrics.railItemCorner))
                .background(container)
                .testTag("desktop-rail-indicator-${destination.name.lowercase()}"),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides content) {
                destination.Icon(
                    selected,
                    Modifier
                        .size(DesktopShellMetrics.railItemIcon)
                        .testTag("desktop-rail-icon-${destination.name.lowercase()}"),
                )
            }
        }
        if (!expanded) return@Column
        Spacer(Modifier.height(DesktopShellMetrics.railItemLabelGap))
        // One 12 sp line: the rail never grows for a longer name, and a name that still does not fit ellipsizes, so
        // every item keeps the 68 dp pitch.
        Text(
            stringResource(destination.railLabel),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = Space.xxs)
                .testTag("desktop-rail-label-${destination.name.lowercase()}"),
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

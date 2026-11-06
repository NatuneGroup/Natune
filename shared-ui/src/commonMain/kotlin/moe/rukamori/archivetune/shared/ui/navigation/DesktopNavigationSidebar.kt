package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.theme.RowHeights
import moe.rukamori.archivetune.shared.ui.theme.Space
import org.jetbrains.compose.resources.stringResource

private val SidebarWidth = 232.dp
private val SidebarRowHeight = RowHeights.navigation
private val SidebarGutter = Space.xs

/**
 * The wide desktop sidebar: icon and label rows, so the two destinations are named without opening anything. Narrow
 * desktop windows keep the stock icon rail, and phone hosts never compose this at all.
 */
@Composable
internal fun DesktopNavigationSidebar(
    navigation: ShellNavigation,
    tabs: List<ShellTab>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(SidebarWidth)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = SidebarGutter, vertical = Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.xxs),
    ) {
        tabs.forEach { tab ->
            val selected = navigation.selectedTab == tab
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(SidebarRowHeight)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { navigation.select(tab, toRoot = false) })
                    .testTag("shell-tab-${tab.name}")
                    .padding(horizontal = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides
                        if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    TabMark(tab)
                }
                Text(
                    stringResource(tab.label),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

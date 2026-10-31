@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import moe.rukamori.archivetune.shared.domain.settings.PlayerMenuStyle
import moe.rukamori.archivetune.ui.component.NewAction
import moe.rukamori.archivetune.ui.component.NewActionGrid
import moe.rukamori.archivetune.ui.component.rememberPlayerMenuStyle

/** True when the player and lyrics menus use the Material 3 presentation instead of Apple Music's. */
@Composable
internal fun rememberMd3PlayerMenus(): Boolean = rememberPlayerMenuStyle() == PlayerMenuStyle.MD3

/**
 * The action block of the player and lyrics menus. Apple Music keeps the icon grid; the Material 3 style lists the
 * same actions as rows with their icons. Both take the same arguments, so a call site does not choose.
 */
@Composable
internal fun PlayerMenuActionBlock(
    actions: List<NewAction>,
    modifier: Modifier = Modifier,
) {
    if (rememberMd3PlayerMenus()) {
        NewActionList(actions = actions, modifier = modifier)
    } else {
        NewActionGrid(actions = actions, modifier = modifier)
    }
}

/** The Material 3 presentation of [NewActionGrid]: one row per action with its icon, a disabled row dimmed. */
@Composable
internal fun NewActionList(
    actions: List<NewAction>,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        actions.forEach { action ->
            ListItem(
                headlineContent = { Text(text = action.text, color = action.contentColor) },
                leadingContent = action.icon,
                modifier =
                    Modifier
                        .alpha(if (action.enabled) 1f else 0.38f)
                        .clickable(enabled = action.enabled, onClick = action.onClick),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.shared.ui.menu.playerqueue

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
import org.jetbrains.compose.resources.stringResource

/**
 * The Material 3 action block of the player queue menu: the same [PlayerQueueGridItem]s as the Apple Music icon grid,
 * laid out as list rows with their icons. A disabled action keeps its row, dimmed and not clickable.
 */
@Composable
internal fun PlayerQueueActionList(
    items: List<PlayerQueueGridItem>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEach { item ->
            ListItem(
                headlineContent = { Text(text = stringResource(item.label)) },
                leadingContent = item.leading,
                modifier =
                    Modifier
                        .alpha(if (item.enabled) 1f else 0.38f)
                        .clickable(enabled = item.enabled, onClick = item.onClick),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

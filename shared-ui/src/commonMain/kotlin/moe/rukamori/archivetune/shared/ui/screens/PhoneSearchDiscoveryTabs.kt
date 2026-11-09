package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Android SearchSegmentedTabs metrics, used by the phone's embedded discovery surface. */
@Composable
internal fun PhoneSearchDiscoveryTabs(current: DiscoveryTab, select: (DiscoveryTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(24.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(DiscoveryTab.EXPLORE, DiscoveryTab.SUGGESTIONS).forEach { tab ->
            val selected = tab == current
            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(20.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                .clickable { select(tab) }, contentAlignment = Alignment.Center) {
                Text(stringResource(if (tab == DiscoveryTab.EXPLORE) Res.string.m4_explore else Res.string.m4_suggestions),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

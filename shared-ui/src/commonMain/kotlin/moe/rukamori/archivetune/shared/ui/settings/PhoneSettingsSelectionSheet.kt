package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource

/** Android's PreferenceSelectionBottomSheet, with host-provided runtime labels where needed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneSettingsSelectionSheet(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    enabled: (String) -> Boolean = { true },
    description: (String) -> String? = { null },
) {
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = null) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp),
            shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)),
            tonalElevation = 6.dp, shadowElevation = 10.dp) {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 24.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp)
                    .size(40.dp, 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f)))
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    itemsIndexed(options, key = { _, option -> option.first }) { index, (value, label) ->
                        val selected = value == current
                        val shape = when {
                            options.size == 1 -> RoundedCornerShape(22.dp)
                            index == 0 -> RoundedCornerShape(22.dp, 22.dp, 5.dp, 5.dp)
                            index == options.lastIndex -> RoundedCornerShape(5.dp, 5.dp, 22.dp, 22.dp)
                            else -> RoundedCornerShape(5.dp)
                        }
                        Row(Modifier.fillMaxWidth().padding(bottom = if (index == options.lastIndex) 0.dp else 1.5.dp)
                            .clip(shape).background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .16f) else MaterialTheme.colorScheme.surfaceContainerLow)
                            .clickable(enabled = enabled(value)) { onSelect(value) }.padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(label, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                description(value)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                            Icon(painterResource(if (selected) Res.drawable.m6_icon_radio_checked else Res.drawable.m6_icon_radio_unchecked), null, Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

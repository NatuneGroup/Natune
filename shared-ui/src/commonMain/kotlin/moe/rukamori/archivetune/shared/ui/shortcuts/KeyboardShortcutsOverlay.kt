package moe.rukamori.archivetune.shared.ui.shortcuts

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The one help surface for the shortcut table. It renders [KeyboardShortcuts] itself, so it cannot drift from what the
 * key handler dispatches, and it lays the rows out in one column on a narrow window and two on a wide one.
 */
@Composable
fun KeyboardShortcutsDialog(onDismiss: () -> Unit, platform: ShortcutPlatform = currentShortcutPlatform) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            // The cap has to constrain the fill, not follow it: fillMaxWidth first made the 900 dp maximum inert and
            // the overlay 94 % of a 1440 px window (1350 px) instead of the intended dialog width.
            modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth(0.94f).heightIn(max = 720.dp)
                .testTag("shortcut-overlay"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
        ) {
            BoxWithConstraints {
                // Two columns only once a chord and its label still fit side by side.
                val columns = if (maxWidth >= 720.dp) 2 else 1
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(Res.string.shortcut_overlay_title),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                stringResource(Res.string.shortcut_overlay_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(Res.string.shortcut_overlay_close))
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        val groups = ShortcutGroup.entries.mapNotNull { group ->
                            KeyboardShortcuts.listed.filter { it.group == group }
                                .takeIf { it.isNotEmpty() }
                                ?.let { group to it }
                        }
                        groups.forEach { (group, groupEntries) ->
                            item(key = "group:${group.name}") {
                                Text(
                                    stringResource(shortcutGroupLabel(group)),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                                        .testTag("shortcut-group:${group.name}"),
                                )
                            }
                            val rows = groupEntries.chunked(columns)
                            items(count = rows.size, key = { index -> "row:${group.name}:$index" }) { index ->
                                Row(Modifier.fillMaxWidth()) {
                                    rows[index].forEach { entry ->
                                        ShortcutRow(entry, platform, Modifier.weight(1f))
                                    }
                                    // Keeps a lone trailing row from stretching across both columns.
                                    repeat(columns - rows[index].size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutRow(entry: ShortcutEntry, platform: ShortcutPlatform, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(entry.label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text(
                entry.chords.joinToString("   ") { it.displayText(platform) },
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    .widthIn(max = 220.dp),
            )
        }
    }
}

@Composable
private fun shortcutGroupLabel(group: ShortcutGroup) = when (group) {
    ShortcutGroup.PLAYBACK -> Res.string.shortcut_group_playback
    ShortcutGroup.NAVIGATION -> Res.string.shortcut_group_navigation
    ShortcutGroup.VIEW -> Res.string.shortcut_group_view
}

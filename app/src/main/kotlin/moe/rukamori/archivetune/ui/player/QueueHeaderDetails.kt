package moe.rukamori.archivetune.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R

/**
 * Source line, song count and the queue actions in one row. The text column takes whatever width the icons leave,
 * and each line ellipsizes instead of pushing the row taller.
 */
@Composable
internal fun QueueHeaderDetails(
    title: String?,
    detailsText: String,
    color: Color,
    locked: Boolean,
    onLock: () -> Unit,
    onSave: () -> Unit,
    onJump: () -> Unit,
    onClear: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    stringResource(R.string.queue_source_title, it),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                )
            }
            Text(
                detailsText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.6f),
            )
        }
        IconButton(
            onClick = onLock,
            colors = IconButtonDefaults.iconButtonColors(contentColor = color.copy(alpha = 0.7f)),
        ) {
            Icon(
                painter = painterResource(if (locked) R.drawable.lock else R.drawable.lock_open),
                contentDescription = stringResource(if (locked) R.string.queue_unlock_edits else R.string.queue_lock_edits),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onSave) {
            Icon(painterResource(R.drawable.playlist_add), stringResource(R.string.queue_save_playlist), tint = color)
        }
        IconButton(onClick = onJump) {
            Icon(painterResource(R.drawable.location_on), stringResource(R.string.queue_jump_current), tint = color)
        }
        IconButton(
            onClick = onClear,
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(
                painter = painterResource(R.drawable.delete),
                contentDescription = stringResource(R.string.clear),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// One line instead of a stacked title and status: the status wraps rather than clips when the font scale grows.
@Composable
internal fun QueueAutoplayFooter(enabled: Boolean, color: Color) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.queue_continue_playing), style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold, color = color)
            Text(stringResource(if (enabled) R.string.queue_autoplaying_similar else R.string.queue_autoplay_off),
                style = MaterialTheme.typography.labelMedium, color = color.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = color.copy(alpha = 0.08f), thickness = 1.dp)
    }
}

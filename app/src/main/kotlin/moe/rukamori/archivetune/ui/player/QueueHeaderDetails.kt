package moe.rukamori.archivetune.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import moe.rukamori.archivetune.utils.makeTimeString

@Composable
internal fun QueueHeaderDetails(title: String?, remainingMs: Long?, color: Color, onSave: () -> Unit, onJump: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            title?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.queue_source_title, it), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium, color = color)
            }
            Text(stringResource(R.string.queue_time_remaining, remainingMs?.let(::makeTimeString) ?: stringResource(R.string.unknown)),
                style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.65f))
        }
        IconButton(onClick = onSave) {
            Icon(painterResource(R.drawable.playlist_add), stringResource(R.string.queue_save_playlist), tint = color)
        }
        IconButton(onClick = onJump) {
            Icon(painterResource(R.drawable.location_on), stringResource(R.string.queue_jump_current), tint = color)
        }
    }
}

@Composable
internal fun QueueAutoplayFooter(enabled: Boolean, color: Color) {
    Spacer(Modifier.height(14.dp))
    Text(stringResource(R.string.queue_continue_playing), style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold, color = color)
    Spacer(Modifier.height(2.dp))
    Text(stringResource(if (enabled) R.string.queue_autoplaying_similar else R.string.queue_autoplay_off),
        style = MaterialTheme.typography.bodySmall, color = color.copy(alpha = 0.5f))
    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = color.copy(alpha = 0.08f), thickness = 1.dp)
}

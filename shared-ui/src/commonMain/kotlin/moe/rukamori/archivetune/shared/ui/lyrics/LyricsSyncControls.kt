package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun LyricsSyncControls(
    offsetMs: Long, adjust: (Long) -> Unit, reset: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { adjust(-100L) }, enabled = enabled) { Text(stringResource(Res.string.content_lyrics_offset_minus)) }
        Text(stringResource(Res.string.content_lyrics_offset_value, offsetMs), style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = { adjust(100L) }, enabled = enabled) { Text(stringResource(Res.string.content_lyrics_offset_plus)) }
        TextButton(onClick = reset, enabled = enabled && offsetMs != 0L) { Text(stringResource(Res.string.content_lyrics_offset_reset)) }
    }
}

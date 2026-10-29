package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.utils.isLocalMediaId
import moe.rukamori.archivetune.utils.makeTimeString

/** Rows observe the retained Android database, not the portable database that only desktop and iOS open. */
@Composable
fun PodcastEpisodeProgress(episodeId: String, modifier: Modifier = Modifier, showRemaining: Boolean = false) {
    val database = LocalDatabase.current
    val provider = if (episodeId.isLocalMediaId()) "local" else "youtube"
    val flow = remember(database, episodeId, provider) { database.podcastProgress.observe("$provider:$episodeId") }
    val stored by flow.collectAsStateWithLifecycle(initialValue = null)
    val progress = stored?.progress() ?: return
    if (!progress.finished && progress.positionMs <= 0L) return
    Column(modifier) {
        if (progress.finished) {
            Text(stringResource(R.string.podcast_episode_played), style = MaterialTheme.typography.labelSmall)
        } else {
            val fraction = progress.fraction
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(2.dp))
            }
            val duration = progress.durationMs
            if (showRemaining && duration != null && duration > 0L) {
                Text(
                    stringResource(R.string.podcast_episode_remaining, makeTimeString((duration - progress.positionMs).coerceAtLeast(0L))),
                    style = MaterialTheme.typography.labelSmall,
                )
            } else if (fraction == null) {
                Text(
                    stringResource(R.string.podcast_episode_position, makeTimeString(progress.positionMs)),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

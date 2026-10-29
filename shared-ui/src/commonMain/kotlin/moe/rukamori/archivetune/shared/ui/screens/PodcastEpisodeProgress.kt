package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.playback.PodcastProgress
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** One observed database projection for every episode surface; no per-row database or playback owner. */
val LocalPodcastProgress = compositionLocalOf<Map<String, PodcastProgress>> { emptyMap() }

@Composable
fun PodcastEpisodeProgress(ref: ContentRef, modifier: Modifier = Modifier) {
    val progress = LocalPodcastProgress.current["${ref.provider}:${ref.id}"] ?: return
    if (!progress.finished && progress.positionMs <= 0L) return
    Column(modifier.testTag("episode-progress-${ref.provider}:${ref.id}")) {
        if (progress.finished) {
            Text(stringResource(Res.string.podcast_episode_played), style = MaterialTheme.typography.labelSmall)
        } else {
            progress.fraction?.let { fraction ->
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(2.dp))
            }
            val remaining = progress.durationMs?.takeIf { it > 0L }?.let { (it - progress.positionMs).coerceAtLeast(0L) }
            Text(
                if (remaining == null) stringResource(Res.string.podcast_episode_position, progressTime(progress.positionMs))
                else stringResource(Res.string.podcast_episode_remaining, progressTime(remaining)),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun progressTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1_000L
    val minutes = seconds / 60
    return if (minutes < 60) "$minutes:${(seconds % 60).toString().padStart(2, '0')}"
    else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}

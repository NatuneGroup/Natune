package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.songmetadata.effectiveSong
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Android ListItem metrics: 72dp row, 56dp sleeve, 10dp radius, one-line semibold title. */
@Composable
internal fun PhoneContentRow(
    occurrence: ContentOccurrence,
    actions: ContentActions,
    artwork: ContentArtwork,
    modifier: Modifier,
    ordinal: Int?,
    trailingControls: (@Composable () -> Unit)?,
    showDuration: Boolean,
) {
    val display = effectiveSong(occurrence.item)
    val duration = contentTrackDuration(occurrence.item.durationMs)
    val subtitle = listOfNotNull(display.subtitle.takeIf { it.isNotBlank() }, duration.takeIf {
        !showDuration && occurrence.item.ref.kind in setOf(ContentKind.SONG, ContentKind.EPISODE)
    }).joinToString(" • ")
    Row(modifier.fillMaxWidth().clickable { actions.open(occurrence) }.testTag("occurrence-${occurrence.occurrenceId}")
        .heightIn(min = 72.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ordinal?.let { Text(it.toString(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(28.dp)) }
        Box(Modifier.padding(8.dp)) { artwork(occurrence.item, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp))) }
        Column(Modifier.weight(1f).padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(display.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            PodcastEpisodeProgress(occurrence.item.ref)
        }
        if (showDuration && duration != null) Text(duration, style = MaterialTheme.typography.bodySmall)
        trailingControls?.invoke()
        actions.more?.let { more ->
            IconButton(onClick = { more(occurrence) }, modifier = Modifier.testTag("more-${occurrence.occurrenceId}")) {
                Icon(painterResource(Res.drawable.m4hv_icon_more_vert), stringResource(Res.string.m4_more))
            }
        }
    }
}

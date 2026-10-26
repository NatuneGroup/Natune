@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.shared.ui.menu.playerqueue

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import moe.rukamori.archivetune.shared.ui.resources.*

/** The baseline's action grid is three tiles across. */
private const val PLAYER_QUEUE_GRID_COLUMNS = 3

/**
 * One section of the menu: the extra-large container tone the baseline's MenuSurfaceSection used. The caller supplies
 * the vertical spacing through [modifier], as the baseline did.
 */
@Composable
internal fun PlayerQueueSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(content = content)
    }
}

/**
 * One tappable row, built on the same [ListItem] the baseline used. [isError] tints the label for destructive actions
 * such as removal, and [supporting] adds the single supporting line the tempo row shows.
 */
@Composable
internal fun PlayerQueueRow(
    headline: String,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supporting: String? = null,
) {
    val headlineContent: @Composable () -> Unit = {
        Text(
            text = headline,
            color = if (isError) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
    val rowModifier = modifier.clickable(onClick = onClick)
    val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    if (supporting == null) {
        ListItem(
            headlineContent = headlineContent,
            modifier = rowModifier,
            leadingContent = leading,
            colors = colors,
        )
    } else {
        ListItem(
            headlineContent = headlineContent,
            modifier = rowModifier,
            supportingContent = {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = leading,
            colors = colors,
        )
    }
}

/** The inset divider the baseline drew between rows of one section. */
@Composable
internal fun PlayerQueueDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * One tile of the top action grid. [leading] is a slot so the menu can show a progress indicator while a canvas
 * refetch runs, in place of the icon.
 */
internal class PlayerQueueGridItem(
    val label: StringResource,
    val enabled: Boolean,
    val leading: @Composable () -> Unit,
    val onClick: () -> Unit,
)

@Composable
internal fun PlayerQueueGrid(
    items: List<PlayerQueueGridItem>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.chunked(PLAYER_QUEUE_GRID_COLUMNS).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowItems.forEach { item ->
                    PlayerQueueGridTile(item = item, modifier = Modifier.weight(1f))
                }
                repeat(PLAYER_QUEUE_GRID_COLUMNS - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PlayerQueueGridTile(
    item: PlayerQueueGridItem,
    modifier: Modifier,
) {
    FilledTonalButton(
        onClick = item.onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 96.dp),
        enabled = item.enabled,
        shape = ButtonDefaults.squareShape,
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                item.leading()
            }
            Text(
                text = stringResource(item.label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
        }
    }
}

/**
 * The now-playing block at the top of the menu: artwork, the "Now Playing" label, the title and the artist line. Under
 * the coordinated Apple Music presentation it sits on the sheet with a hairline below, as the baseline's header did.
 */
@Composable
internal fun PlayerQueueHeaderCard(
    track: PlayerQueueTrack,
    appleMusicExperience: Boolean,
    icon: PlayerQueueMenuIconSlot,
    artwork: PlayerQueueArtworkSlot,
) {
    val noTitle = stringResource(Res.string.m5pq_no_title)
    val nowPlaying = stringResource(Res.string.m5pq_now_playing)
    val title = track.title.ifBlank { noTitle }
    val subtitle = track.artists.joinToString(separator = " • ") { it.name }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val thumbnailShape = RoundedCornerShape(16.dp)
            val thumbnailUrl = track.thumbnailUrl
            if (thumbnailUrl.isNullOrBlank()) {
                Box(
                    modifier =
                        Modifier
                            .size(56.dp)
                            .clip(thumbnailShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    icon(PlayerQueueMenuIcon.MUSIC_NOTE, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(22.dp))
                }
            } else {
                artwork(thumbnailUrl, Modifier.size(56.dp).clip(thumbnailShape))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = nowPlaying,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(),
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee(),
                    )
                }
            }
        }
    }
    if (appleMusicExperience) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    } else {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            content()
        }
    }
}

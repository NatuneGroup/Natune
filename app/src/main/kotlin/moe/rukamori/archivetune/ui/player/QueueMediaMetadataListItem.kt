package moe.rukamori.archivetune.ui.player

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import moe.rukamori.archivetune.constants.QueueListItemHeight
import moe.rukamori.archivetune.constants.QueueListThumbnailSize
import moe.rukamori.archivetune.constants.ThumbnailCornerRadius
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.ui.component.ItemThumbnail
import moe.rukamori.archivetune.ui.component.ListItem
import moe.rukamori.archivetune.ui.component.PodcastEpisodeProgress
import moe.rukamori.archivetune.utils.joinByBullet
import moe.rukamori.archivetune.utils.makeTimeString

/**
 * Queue row: the same content as the library's MediaMetadataListItem at queue density (64 dp rows with 48 dp
 * artwork). The library row keeps its 72 dp / 56 dp sizes because every other list shares it.
 */
@Composable
fun QueueMediaMetadataListItem(
    mediaMetadata: MediaMetadata,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isActive: Boolean = false,
    isPlaying: Boolean = false,
    shouldLoadImage: Boolean = true,
    showActiveContainer: Boolean = true,
    textColorOverride: Color? = null,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    ListItem(
        title = mediaMetadata.title,
        progressContent = if (mediaMetadata.isPodcast) {
            { PodcastEpisodeProgress(mediaMetadata.id) }
        } else null,
        subtitle = {
            val text =
                remember(mediaMetadata) {
                    joinByBullet(
                        mediaMetadata.artists.joinToString { it.name },
                        makeTimeString(mediaMetadata.duration * 1000L),
                    )
                }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = mediaMetadata.thumbnailUrl,
                albumIndex = null,
                isSelected = isSelected,
                isActive = isActive,
                isPlaying = isPlaying,
                shouldLoadImage = shouldLoadImage,
                shape = RoundedCornerShape(ThumbnailCornerRadius),
                modifier = Modifier.size(QueueListThumbnailSize),
            )
        },
        trailingContent = trailingContent,
        modifier = modifier,
        isActive = isActive,
        showActiveContainer = showActiveContainer,
        textColorOverride = textColorOverride,
        rowHeight = QueueListItemHeight,
    )
}

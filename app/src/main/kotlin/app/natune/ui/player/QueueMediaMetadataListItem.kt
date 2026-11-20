package app.natune.ui.player

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
import app.natune.constants.QueueListItemHeight
import app.natune.constants.QueueListThumbnailSize
import app.natune.constants.ThumbnailCornerRadius
import app.natune.models.MediaMetadata
import app.natune.ui.component.ItemThumbnail
import app.natune.ui.component.ListItem
import app.natune.ui.component.PodcastEpisodeProgress
import app.natune.utils.joinByBullet
import app.natune.utils.makeTimeString

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

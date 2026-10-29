package moe.rukamori.archivetune.shared.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ContentArtwork
import moe.rukamori.archivetune.shared.ui.songmetadata.effectiveSong
import org.jetbrains.compose.resources.painterResource

/**
 * The compact card every non-hero shelf renders (BitChord ShelfCard): artwork with the hairline border, the title one
 * line below, and the subtitle under that. [artwork] is the host image slot, drawn at the modifier it is given. Artists
 * are circular. A frame that is not square keeps its own proportions and drops the hairline border, which exists to
 * pull a square sleeve off the page. [isActive] marks the track or collection that is current, and [isPlaying] adds the
 * listening mark. The owner decides what a tap does, so [onClick] is the only action here.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeFeedShelfCard(
    item: ContentItem,
    artwork: ContentArtwork,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isActive: Boolean = false,
    isPlaying: Boolean = false,
    thumbnailAspectRatio: Float = 1f,
    trailing: (@Composable () -> Unit)? = null,
) {
    val display = effectiveSong(item)
    val isCircular = item.ref.kind == ContentKind.ARTIST
    val shape: Shape = if (isCircular) CircleShape else RoundedCornerShape(HomeShelfCardCorner)
    val isWideFrame = !isCircular && abs(thumbnailAspectRatio - 1f) > 0.001f
    Column(
        modifier =
            modifier
                .width(HomeShelfCardWidth)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        // The artwork box keeps the square height whatever frame goes in it, so a shelf that mixes a wide still with
        // square sleeves stays one row high with one title baseline, and the skeleton standing in for it does not have
        // to move when the data does. A wide frame sits centred, and the space either side is the cost of one row height.
        Box(
            modifier = Modifier.fillMaxWidth().height(HomeShelfCardWidth),
            contentAlignment = Alignment.Center,
        ) {
            artwork(
                item,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(thumbnailAspectRatio)
                    .clip(shape)
                    .let { m -> if (isWideFrame) m else m.homeFeedThumbnailBorder(shape) },
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = display.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.W600,
                letterSpacing = (-0.2).sp,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isActive && isPlaying) {
                Icon(
                    painter = painterResource(Res.drawable.m4_icon_listening),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            trailing?.invoke()
        }
        Text(
            text = display.subtitle,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

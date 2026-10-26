package moe.rukamori.archivetune.shared.ui.home

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ContentActions
import moe.rukamori.archivetune.shared.ui.screens.ContentArtwork
import org.jetbrains.compose.resources.stringResource

/**
 * The common frame for every provider. Square sleeves are the default for songs, albums, playlists and the YouTube,
 * local, Spotify and QQ items alike; a host that knows a different frame supplies it per item through the home
 * screen's thumbnail ratio hook instead of this file guessing from provider metadata.
 */
const val HomeSquareCardRatio = 1f

/**
 * Podcasts and episodes have no owner sheet on the baseline home, so a long press does nothing for them. Every other
 * typed kind keeps the owner menu that the tap and the trailing control reach.
 */
fun homeCardHasMenu(item: ContentItem): Boolean =
    item.ref.kind != ContentKind.PODCAST && item.ref.kind != ContentKind.EPISODE

/** The playing track or its collection, matched by id the way the baseline home matches its now-playing ids. */
fun homeCardIsActive(item: ContentItem, activeIds: Set<String>): Boolean = item.ref.id in activeIds

/**
 * One shelf card with the common presentation: [ContentActions.open] is the tap, [ContentActions.more] is the long press
 * and the trailing menu control, and the active overlay follows [activeIds]/[playing]. Artists keep their circular
 * frame from the card itself, so the ratio hook only ever decides rectangular sleeves.
 */
@Composable
fun HomeShelfOccurrenceCard(
    occurrence: ContentOccurrence,
    artwork: ContentArtwork,
    actions: ContentActions,
    activeIds: Set<String>,
    playing: Boolean,
    thumbnailAspectRatio: (ContentItem) -> Float,
    modifier: Modifier = Modifier,
) {
    val item = occurrence.item
    val active = homeCardIsActive(item, activeIds)
    val more = actions.more
    val menu = more != null && homeCardHasMenu(item)
    val longClick: (() -> Unit)? = if (menu) {
        { more?.invoke(occurrence) }
    } else {
        null
    }
    val menuControl: (@Composable () -> Unit)? = if (menu) {
        { TextButton(onClick = { more?.invoke(occurrence) }) { Text(stringResource(Res.string.m4_more)) } }
    } else {
        null
    }
    HomeFeedShelfCard(
        item = item,
        artwork = artwork,
        onClick = { actions.open(occurrence) },
        modifier = modifier,
        onLongClick = longClick,
        isActive = active,
        isPlaying = active && playing,
        thumbnailAspectRatio = thumbnailAspectRatio(item),
        trailing = menuControl,
    )
}

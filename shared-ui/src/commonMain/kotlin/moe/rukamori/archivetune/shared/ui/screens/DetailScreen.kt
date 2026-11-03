package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.ui.glass.GlassScreenHeaderOverlay
import moe.rukamori.archivetune.shared.ui.glass.ScreenHeaderHazeBarZone
import moe.rukamori.archivetune.shared.ui.glass.glassHeaderSource
import moe.rukamori.archivetune.shared.ui.glass.rememberGlassScreenHeader
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.navigation.LocalBackToMain
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import org.jetbrains.compose.resources.stringResource

enum class DetailKind { Album, Artist, Playlist, Podcast }

class DetailActions(
    val content: ContentActions,
    val back: () -> Unit,
    val playAll: (() -> Unit)?,
    val shuffle: (() -> Unit)?,
    val toggleLibrary: (() -> Unit)?,
    val more: (() -> Unit)?,
)

/**
 * Metadata/error/empty branches never remove the header's navigation and retry actions. A playlist with Liquid Glass on
 * floats its back control as the glass pill over the list; every other case keeps the flat text back in the header.
 */
@Composable
fun DetailScreen(
    kind: DetailKind,
    header: ContentItem?,
    state: PagedContentState,
    actions: DetailActions,
    artwork: ContentArtwork,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = LocalShellContentPadding.current,
    /** The retained sort, order or period control of a playlist page. Other kinds ignore it and keep their layout. */
    control: PlaylistDetailControl? = null,
    /** A local playlist header's first rows' items, drawn as its 2x2 mosaic cover when it has no custom cover. */
    headerMosaicItems: List<ContentItem> = emptyList(),
) {
    // Glass is only recorded for the playlist header, so the other kinds never record the list into a backdrop nothing samples.
    val glass = rememberGlassScreenHeader()
    val glassHeader = kind == DetailKind.Playlist && glass.liquidGlassActive
    val playlistControl = control.takeIf { kind == DetailKind.Playlist }
    // The same admission gates the drag handle and the move buttons, so a drag never reaches an owner that refused a move.
    val moveAction = playlistControl.moveAction(state)
    val drag = rememberPlaylistRowDrag(listState, state.items, contentPadding, moveAction)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wideHeader = isWideDetailHero(maxWidth)
        LazyColumn(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .testTag("detail-${kind.name}")
                .playlistRowDragViewport(drag)
                .then(if (glassHeader) Modifier.glassHeaderSource(glass) else Modifier),
            state = listState,
            contentPadding = contentPadding,
        ) {
            item(key = "m4-detail-header") {
                Column(
                    Modifier.fillMaxWidth().padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp,
                        // The glass pill sits over the status bar and the first 64 dp, so the content starts below it.
                        top = if (glassHeader) statusTop + ScreenHeaderHazeBarZone + 16.dp else 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!glassHeader) TextButton(onClick = actions.back) { Text(stringResource(Res.string.m4_back)) }
                    if (wideHeader && header != null) {
                        Row(
                            Modifier.fillMaxWidth().testTag("detail-hero-wide"),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlaylistArtwork(
                                header,
                                headerMosaicItems,
                                artwork,
                                Modifier
                                    .size(if (kind == DetailKind.Artist) 180.dp else 200.dp)
                                    .clip(if (kind == DetailKind.Artist) CircleShape else RoundedCornerShape(16.dp)),
                            )
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(header.title, style = MaterialTheme.typography.headlineLarge)
                                Text(header.subtitle, style = MaterialTheme.typography.bodyLarge)
                                DetailHeroActions(kind, state, actions)
                            }
                        }
                    } else {
                        header?.let {
                            PlaylistArtwork(
                                it,
                                headerMosaicItems,
                                artwork,
                                Modifier
                                    .size(if (kind == DetailKind.Artist) 180.dp else 224.dp)
                                    .clip(if (kind == DetailKind.Artist) CircleShape else RoundedCornerShape(16.dp)),
                            )
                            Text(it.title, style = MaterialTheme.typography.headlineLarge)
                            Text(it.subtitle, style = MaterialTheme.typography.bodyLarge)
                        }
                        DetailHeroActions(kind, state, actions)
                    }
                }
            }
            if (playlistControl != null && playlistControl.isShownFor(state)) {
                item(key = "m4-detail-control") {
                    PlaylistDetailControlRow(playlistControl, Modifier.padding(horizontal = 16.dp))
                }
            }
            contentRows(
                state,
                actions.content,
                artwork,
                numbered = kind == DetailKind.Album || kind == DetailKind.Playlist,
                move = moveAction,
                drag = drag,
            )
        }
        if (glassHeader) {
            GlassScreenHeaderOverlay(
                header = glass,
                title = header?.title.orEmpty(),
                onBack = actions.back,
                onBackLongClick = LocalBackToMain.current ?: actions.back,
            )
        }
    }
}

internal fun isWideDetailHero(maxWidth: Dp): Boolean = maxWidth >= 600.dp

@Composable
private fun DetailHeroActions(
    kind: DetailKind,
    state: PagedContentState,
    actions: DetailActions,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.playAll?.let { play ->
            Button(
                onClick = play,
                enabled = state.items.isNotEmpty() && !state.loading,
                modifier = Modifier.testTag("detail-play"),
            ) { Text(stringResource(Res.string.m4_play)) }
        }
        // Podcast's baseline has no shuffle/library/overflow hero actions.
        if (kind != DetailKind.Podcast) {
            actions.shuffle?.let { shuffle ->
                OutlinedButton(
                    onClick = shuffle,
                    enabled = state.complete && state.items.isNotEmpty(),
                    modifier = Modifier.testTag("detail-shuffle"),
                ) { Text(stringResource(Res.string.m4_shuffle)) }
            }
        }
    }
    if (kind != DetailKind.Podcast) {
        Row {
            actions.toggleLibrary?.let { toggle ->
                TextButton(onClick = toggle) { Text(stringResource(Res.string.m4_library_action)) }
            }
            actions.more?.let { more ->
                TextButton(onClick = more, modifier = Modifier.testTag("detail-more")) {
                    Text(stringResource(Res.string.m4_more))
                }
            }
        }
    }
}

package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The shortcut cards above the mixed Library section, as the baseline draws them. Each flag follows its hide
 * preference. [likedCount] is null while the liked list is unread, so the card never shows a count it has not read.
 * [myTopSize] is null while the My top card is hidden.
 */
data class LibraryShortcutsUi(
    val liked: Boolean,
    val likedCount: Int?,
    val offline: Boolean,
    val cached: Boolean,
    val localFiles: Boolean,
    val myTopSize: Int?,
)

/**
 * The header of the mixed Library section: the shortcut cards, the recently played rail and the Top mixes section.
 * [recent] holds the newest distinct songs of the listening history, newest first; an empty list hides the rail as
 * the baseline does. [topMixes] is null while the Top mixes section is hidden by its preference.
 */
data class LibraryMixHeaderUi(
    val shortcuts: LibraryShortcutsUi,
    val recent: List<ContentOccurrence>,
    val topMixes: LibraryTopMixesUi?,
)

/** What the Top mixes section shows. A host that cannot build mixes says so in [Message] instead of hiding the section. */
sealed interface LibraryTopMixesUi {
    data class Message(val text: String) : LibraryTopMixesUi
}

/** [openRoute] opens a shortcut's route through the shell. [play] plays one recently played song on its own. */
class LibraryMixHeaderActions(
    val openRoute: (String) -> Unit,
    val play: (ContentOccurrence) -> Unit,
)

/** A host's header for the mixed section: what it shows and what its cards and rail do. */
class LibraryMixHeaderBinding(val state: LibraryMixHeaderUi, val actions: LibraryMixHeaderActions)

/** Draws the mixed section's header rows before its list rows. */
fun LazyListScope.libraryMixHeader(
    state: LibraryMixHeaderUi,
    actions: LibraryMixHeaderActions,
    artwork: ContentArtwork,
) {
    val shortcuts = state.shortcuts
    if (shortcuts.liked || shortcuts.offline || shortcuts.cached || shortcuts.localFiles || shortcuts.myTopSize != null) {
        item(key = "library-mix-shortcuts", contentType = "library-mix-shortcuts") {
            LibraryShortcutGrid(shortcuts, actions.openRoute)
        }
    }
    if (state.recent.isNotEmpty()) {
        item(key = "library-mix-recent", contentType = "library-mix-recent") {
            RecentlyPlayedRail(state.recent, actions.play, artwork)
        }
    }
    when (val mixes = state.topMixes) {
        null -> Unit
        is LibraryTopMixesUi.Message -> item(key = "library-mix-top-mixes", contentType = "library-mix-top-mixes") {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                LibraryMixHeading(stringResource(Res.string.m9_library_top_mixes))
                Text(
                    mixes.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun LibraryShortcutGrid(state: LibraryShortcutsUi, open: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.liked || state.offline) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShortcutSlot(state.liked) { modifier ->
                    ShortcutCard(
                        title = stringResource(Res.string.m9_library_liked_songs),
                        detail = state.likedCount?.let { stringResource(Res.string.m9_library_tracks_count, it) }.orEmpty(),
                        accent = colors.error,
                        tag = "liked",
                        modifier = modifier,
                    ) { open("auto_playlist/liked") }
                }
                ShortcutSlot(state.offline) { modifier ->
                    ShortcutCard(
                        title = stringResource(Res.string.m9_library_offline),
                        detail = stringResource(Res.string.m9_library_downloaded),
                        accent = colors.primary,
                        tag = "offline",
                        modifier = modifier,
                    ) { open("auto_playlist/downloaded") }
                }
            }
        }
        if (state.cached || state.localFiles) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShortcutSlot(state.cached) { modifier ->
                    ShortcutCard(
                        title = stringResource(Res.string.m9_library_cached),
                        detail = stringResource(Res.string.m9_library_instant_playback),
                        accent = colors.tertiary,
                        tag = "cached",
                        modifier = modifier,
                    ) { open("cache_playlist/cached") }
                }
                ShortcutSlot(state.localFiles) { modifier ->
                    ShortcutCard(
                        title = stringResource(Res.string.m9_library_local_files),
                        detail = stringResource(Res.string.m9_library_on_device),
                        accent = colors.secondary,
                        tag = "local",
                        modifier = modifier,
                    ) { open("local_songs") }
                }
            }
        }
        state.myTopSize?.let { size ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShortcutSlot(true) { modifier ->
                    ShortcutCard(
                        title = "${stringResource(Res.string.m4_my_top)} $size",
                        detail = stringResource(Res.string.m9_library_all_time),
                        accent = colors.secondary,
                        tag = "my-top",
                        modifier = modifier,
                    ) { open("top_playlist/$size") }
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A hidden card keeps its half of the row, so the visible card does not stretch across it, as in the baseline. */
@Composable
private fun RowScope.ShortcutSlot(shown: Boolean, card: @Composable (Modifier) -> Unit) {
    if (shown) card(Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
}

@Composable
private fun ShortcutCard(
    title: String,
    detail: String,
    accent: Color,
    tag: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.testTag("library-shortcut-$tag"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(accent.copy(alpha = 0.7f)))
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RecentlyPlayedRail(recent: List<ContentOccurrence>, play: (ContentOccurrence) -> Unit, artwork: ContentArtwork) {
    Column(Modifier.fillMaxWidth()) {
        LibraryMixHeading(stringResource(Res.string.m9_library_recently_played))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(recent, key = { it.occurrenceId }) { song ->
                Column(
                    Modifier.width(110.dp).clickable { play(song) }.testTag("library-recent-${song.occurrenceId}"),
                ) {
                    artwork(song.item, Modifier.size(110.dp).clip(RoundedCornerShape(28.dp)))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        song.item.title,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryMixHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

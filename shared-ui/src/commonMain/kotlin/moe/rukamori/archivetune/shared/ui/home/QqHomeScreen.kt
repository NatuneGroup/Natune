@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.shared.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.PaddingValues
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.glass.BusyCircularProgress
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ContentArtwork
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** One QQ Music chart: its songs are the owner's occurrences, in chart order. */
data class QqHomeSectionUi(val title: String, val label: String, val tracks: List<ContentOccurrence>)

/** The owner's load state. [Failed.detail] is owner-supplied text and is shown only when present. */
sealed interface QqHomeUi {
    data object Loading : QqHomeUi
    data object Empty : QqHomeUi
    data class Failed(val detail: String? = null) : QqHomeUi
    data class Ready(val sections: List<QqHomeSectionUi>) : QqHomeUi
}

/**
 * [playFromSection] starts the section's songs at the tapped track. When that track is already the playing item the
 * owner toggles play and pause instead, as every other song list does; this screen never decides that itself.
 * [refresh] serves the pull gesture and the retry actions, and the owner moves through [QqHomeUi.Loading].
 */
class QqHomeActions(
    val playFromSection: (QqHomeSectionUi, ContentOccurrence) -> Unit,
    val refresh: () -> Unit,
)

/**
 * The QQ Music Home page: the signed-in account's charts, one section each. The page has no navigation of its own;
 * a section is played rather than opened. Nothing here is required to exist, so a failed or empty feed is a state with
 * a retry, never an empty page or a crash.
 *
 * [refreshing] is the owner's reload state and drives the pull indicator. It defaults to false, so a caller without a
 * refresh flag keeps the gesture-only presentation.
 */
@Composable
fun QqHomeScreen(
    state: QqHomeUi,
    actions: QqHomeActions,
    artwork: ContentArtwork,
    activeRef: ContentRef?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
    listState: LazyListState = rememberLazyListState(),
    refreshing: Boolean = false,
) {
    Box(modifier.fillMaxSize()) {
        when (state) {
            QqHomeUi.Loading -> QqHomeStatePane(contentPadding) {
                BusyCircularProgress()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(Res.string.m4_loading))
            }
            QqHomeUi.Empty -> QqHomeStatePane(contentPadding) {
                Text(stringResource(Res.string.m4_empty), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(20.dp))
                Button(onClick = actions.refresh, modifier = Modifier.testTag("qq-home-retry")) {
                    Text(stringResource(Res.string.m4_retry))
                }
            }
            is QqHomeUi.Failed -> QqHomeStatePane(contentPadding) {
                Text(stringResource(Res.string.m4_qq_home_failed), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface)
                state.detail?.let { detail ->
                    Spacer(Modifier.height(8.dp))
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = actions.refresh, modifier = Modifier.testTag("qq-home-retry")) {
                    Text(stringResource(Res.string.m4_retry))
                }
            }
            is QqHomeUi.Ready -> QqHomeFeed(
                sections = state.sections,
                artwork = artwork,
                activeRef = activeRef,
                isPlaying = isPlaying,
                actions = actions,
                contentPadding = contentPadding,
                listState = listState,
                refreshing = refreshing,
            )
        }
    }
}

/** The sections, each a header and its songs. Keys carry the section position as well as the song, since one song can be ranked by several charts. */
@Composable
private fun QqHomeFeed(
    sections: List<QqHomeSectionUi>,
    artwork: ContentArtwork,
    activeRef: ContentRef?,
    isPlaying: Boolean,
    actions: QqHomeActions,
    contentPadding: PaddingValues,
    listState: LazyListState,
    refreshing: Boolean,
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = actions.refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("qq-home-feed"),
            state = listState,
            contentPadding = contentPadding,
        ) {
            sections.forEachIndexed { sectionIndex, section ->
                item(key = "m4-qq-section-$sectionIndex") {
                    HomeFeedSectionHeader(title = section.title, subtitle = section.label)
                }
                itemsIndexed(
                    items = section.tracks,
                    key = { trackIndex, _ -> "m4-qq-track-$sectionIndex-$trackIndex" },
                ) { _, track ->
                    QqTrackRow(
                        occurrence = track,
                        artwork = artwork,
                        active = track.item.ref == activeRef,
                        playing = isPlaying,
                        onClick = { actions.playFromSection(section, track) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QqTrackRow(
    occurrence: ContentOccurrence,
    artwork: ContentArtwork,
    active: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                occurrence.item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (active) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
        },
        supportingContent = { Text(occurrence.item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { artwork(occurrence.item, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))) },
        trailingContent = {
            if (active && playing) {
                Icon(
                    painter = painterResource(Res.drawable.m4_icon_listening),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("qq-track-${occurrence.occurrenceId}"),
    )
}

@Composable
private fun QqHomeStatePane(
    contentPadding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.Center) {
            content()
        }
    }
}

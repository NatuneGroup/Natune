package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

enum class ReleaseCategory { ALL, ALBUMS, SINGLES, EPS }
data class ReleaseUi(val item: ContentItem, val category: ReleaseCategory)
data class ReleasesUi(
    val releases: List<ReleaseUi>,
    val category: ReleaseCategory = ReleaseCategory.ALL,
    val query: String = "",
    val loading: Boolean = false,
    val failed: Boolean = false,
)
class ReleasesActions(
    val back: () -> Unit,
    val category: (ReleaseCategory) -> Unit,
    val query: (String) -> Unit,
    val open: (ContentRef) -> Unit,
    val more: ((ContentItem) -> Unit)?,
    val refresh: () -> Unit,
)

/** Repository policy/ranking order is retained, including favorite-artist priority. */
fun visibleReleases(rows: List<ReleaseUi>, category: ReleaseCategory, query: String): List<ReleaseUi> {
    val q = query.trim()
    return rows.filter { (category == ReleaseCategory.ALL || it.category == category) &&
        (q.isEmpty() || it.item.title.contains(q, true) || it.item.subtitle.contains(q, true)) }
}

@Composable
fun NewReleasesScreen(
    state: ReleasesUi,
    actions: ReleasesActions,
    artwork: ContentArtwork,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    val visible = visibleReleases(state.releases, state.category, state.query)
    val groups = if (state.category == ReleaseCategory.ALL) ReleaseCategory.entries.drop(1) else listOf(state.category)
    LazyVerticalGrid(GridCells.Adaptive(160.dp), modifier.fillMaxSize(), contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("header", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                ManagementHeader(stringResource(Res.string.m4_new_releases), actions.back)
                OutlinedTextField(state.query, actions.query, Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .testTag("release-query"), label = { Text(stringResource(Res.string.m4_search)) }, singleLine = true)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ReleaseCategory.entries, key = { it.name }) { category ->
                        val count = state.releases.count { category == ReleaseCategory.ALL || it.category == category }
                        FilterChip(state.category == category, { actions.category(category) },
                            modifier = Modifier.testTag("release-category-${category.name}"),
                            label = { Text(stringResource(Res.string.m4_release_count, releaseLabel(category), count)) })
                    }
                }
                TextButton(actions.refresh, enabled = !state.loading) { Text(stringResource(Res.string.m4_refresh)) }
                LoadFeedback(state.loading, state.failed, visible.isEmpty(), actions.refresh)
            }
        }
        groups.forEach { category ->
            val releases = visible.filter { it.category == category }
            if (releases.isNotEmpty()) {
                item("category-${category.name}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(releaseLabel(category), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
                }
                items(releases, key = { it.item.ref.collectionUiKey() }) { release ->
                    Column(Modifier.padding(horizontal = 8.dp).clickable { actions.open(release.item.ref) }
                        .testTag("release-${release.item.ref.id}")) {
                        artwork(release.item, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(release.item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(release.item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        actions.more?.let { more ->
                            TextButton({ more(release.item) }) { Text(stringResource(Res.string.m4_more)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun releaseLabel(category: ReleaseCategory): String = stringResource(when (category) {
    ReleaseCategory.ALL -> Res.string.m4_all
    ReleaseCategory.ALBUMS -> Res.string.m4_albums
    ReleaseCategory.SINGLES -> Res.string.m4_singles
    ReleaseCategory.EPS -> Res.string.m4_eps
})

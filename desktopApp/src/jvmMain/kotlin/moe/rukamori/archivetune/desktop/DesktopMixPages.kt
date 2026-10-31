package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SpotifySpDcKey
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.screens.LibraryMixHeaderActions
import moe.rukamori.archivetune.shared.ui.screens.LibraryMixHeaderBinding
import moe.rukamori.archivetune.shared.ui.screens.LibraryTopMixesUi
import moe.rukamori.archivetune.spotify.Spotify
import moe.rukamori.archivetune.spotify.models.SpotifyHomeFeed
import moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedItem

internal class DesktopMixBinding(
    val ui: LibraryTopMixesUi,
    val open: (ContentOccurrence) -> Unit,
    val refresh: () -> Unit,
)

@Composable
internal fun desktopAiMixBinding(
    graph: DesktopSessionGraph,
    owner: DesktopTopMixes,
    openRoute: (String) -> Unit,
): DesktopMixBinding {
    val saved by graph.settings.snapshots.collectAsState()
    val preferences = saved ?: PreferenceSnapshot.EMPTY
    LaunchedEffect(owner) { owner.ensureLoaded() }
    return DesktopMixBinding(owner.ui(preferences), { owner.play(it.occurrenceId) }) {
        if (preferences.mixGenerationAllowed()) owner.generate() else openRoute("settings/ai_integration")
    }
}

@Composable
internal fun desktopSpotifyMixBinding(
    graph: DesktopSessionGraph,
    accounts: DesktopSpotifyAccounts,
    openRoute: (String) -> Unit,
): DesktopMixBinding {
    val saved by graph.settings.snapshots.collectAsState()
    val accountKey = saved?.get(SpotifySpDcKey).orEmpty()
    var attempt by remember(accountKey) { mutableStateOf(0) }
    var state by remember(accountKey) { mutableStateOf(LibraryTopMixesUi.Cards(emptyList(), loading = true)) }
    LaunchedEffect(accountKey, attempt) {
        state = state.copy(loading = true, message = null)
        try {
            check(accounts.libraryAvailable())
            val rows = withTimeoutOrNull(20_000L) {
                check(accounts.restoreCatalogSession())
                val feed = accounts.catalogCall { Spotify.home(sectionItemsLimit = 20).getOrThrow() }
                desktopSpotifyMixRows(feed)
            } ?: error("Spotify mixes timed out")
            check(graph.settings.snapshots.value?.get(SpotifySpDcKey).orEmpty() == accountKey) { "Catalog account changed" }
            state = LibraryTopMixesUi.Cards(
                rows, message = DesktopStrings["content_spotify_mixes_empty"].takeIf { rows.isEmpty() },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            state = state.copy(loading = false, message = DesktopStrings["content_spotify_mixes_failed"])
            graph.report("spotify mixes not loaded: ${failure.javaClass.simpleName}")
        }
    }
    return DesktopMixBinding(
        state.copy(actionLabel = DesktopStrings["content_mixes_refresh"]),
        { openRoute(ArchiveRoutes.content(it.item.ref)) },
        { attempt += 1 },
    )
}

/** Only service-marked personalized/mix playlists qualify; a user playlist named "Mix" is not a personal mix. */
internal fun desktopSpotifyMixRows(feed: SpotifyHomeFeed): List<ContentOccurrence> = feed.sections
    .flatMap { it.items }.filterIsInstance<SpotifyHomeFeedItem.Playlist>()
    .filter { it.id.isNotBlank() && it.name.isNotBlank() && (
        !it.madeForUsername.isNullOrBlank() || it.format?.contains("mix", ignoreCase = true) == true
    ) }.distinctBy { it.id }.take(30).map {
        ContentOccurrence("spotify-mix:${it.id}", ContentItem(
            ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.PLAYLIST, it.id),
            it.name, it.description.orEmpty(), it.imageUrl,
        ))
    }

/** Keeps shortcuts, rails, source filters and their callbacks unchanged. */
internal fun ArchiveGraphPage.withDesktopMixes(mixes: DesktopMixBinding): ArchiveGraphPage {
    if (this !is ArchiveGraphPage.Library) return this
    val header = mixHeader ?: return this
    if (header.state.topMixes == null) return this
    val previous = header.actions
    return copy(mixHeader = LibraryMixHeaderBinding(
        header.state.copy(topMixes = mixes.ui),
        LibraryMixHeaderActions(
            previous.openRoute, previous.play, previous.open, previous.selectFilter, previous.retryRail,
            mixes.open, mixes.refresh,
        ),
        header.replacesRows,
    ))
}

package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.library.DefaultLibraryFilterOrderPreference
import moe.rukamori.archivetune.shared.domain.library.LibraryFilter
import moe.rukamori.archivetune.shared.domain.library.toLibraryFilterOrder
import moe.rukamori.archivetune.shared.domain.library.toLibraryFilterPreference
import moe.rukamori.archivetune.shared.domain.settings.ActiveHomeSourcesKey
import moe.rukamori.archivetune.shared.domain.settings.ArtworkProviderOrderKey
import moe.rukamori.archivetune.shared.domain.settings.LibraryChipOrderKey
import moe.rukamori.archivetune.shared.domain.settings.PlaylistTagOrderKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.QqMusicMusickeyKey
import moe.rukamori.archivetune.shared.domain.settings.QqMusicUinKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifySpDcKey
import moe.rukamori.archivetune.shared.ui.home.HomeSource
import moe.rukamori.archivetune.shared.ui.home.resolvedHomeSources
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_artwork_archivetune_canvas
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_artwork_local_embedded
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_artwork_original_metadata
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_artwork_spotify_canvas
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_artwork_tidal
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_home_sign_in_first
import moe.rukamori.archivetune.shared.ui.resources.m4_albums
import moe.rukamori.archivetune.shared.ui.resources.m4_artists
import moe.rukamori.archivetune.shared.ui.resources.m4_library
import moe.rukamori.archivetune.shared.ui.resources.m4_move_down
import moe.rukamori.archivetune.shared.ui.resources.m4_move_up
import moe.rukamori.archivetune.shared.ui.resources.m4_playlists
import moe.rukamori.archivetune.shared.ui.resources.m4_qq
import moe.rukamori.archivetune.shared.ui.resources.m4_songs
import moe.rukamori.archivetune.shared.ui.resources.m4_spotify
import moe.rukamori.archivetune.shared.ui.resources.m4_youtube
import moe.rukamori.archivetune.shared.ui.resources.m6_arrange_chips
import moe.rukamori.archivetune.shared.ui.resources.m6_arrange_tags
import moe.rukamori.archivetune.shared.ui.resources.m6_artwork_priority
import moe.rukamori.archivetune.shared.ui.resources.m6_cancel
import moe.rukamori.archivetune.shared.ui.resources.m6_home_screens
import moe.rukamori.archivetune.shared.ui.resources.m6_save
import moe.rukamori.archivetune.shared.ui.settings.PreferredArtworkProvider
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.canonicalHomeSources
import moe.rukamori.archivetune.shared.ui.settings.deserializeArtworkProviderOrder
import moe.rukamori.archivetune.shared.ui.settings.homeSourcesFromPreference
import moe.rukamori.archivetune.shared.ui.settings.serializeArtworkProviderOrder
import moe.rukamori.archivetune.shared.ui.settings.toPlaylistTagOrder
import moe.rukamori.archivetune.shared.ui.settings.toPlaylistTagPreference
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** One movable row of an arrange dialog: a stable id and its localized label. */
private class ArrangeEntry(val id: String, val label: StringResource)

/**
 * One reorder dialog over a draft list. Moving applies to the draft only; Save commits the whole order in a single
 * settings edit, so a dismissed or failed dialog never leaves a half-written order.
 */
@Composable
private fun SettingsArrangeDialog(
    title: StringResource,
    initial: List<ArrangeEntry>,
    onDismiss: () -> Unit,
    onSave: suspend (List<String>) -> Unit,
    onFailure: () -> Unit,
) {
    val labels = initial.associate { it.id to stringResource(it.label) }
    var order by remember { mutableStateOf(initial) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(title)) },
        text = {
            SettingsOrderList(order.map { it.id }, { id -> labels[id].orEmpty() }, !saving) { from, to ->
                order = order.toMutableList().apply { add(to, removeAt(from)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            onSave(order.map { it.id })
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            onFailure()
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text(stringResource(Res.string.m6_save)) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(Res.string.m6_cancel)) }
        },
    )
}

/** The Library chip order over the shared filter order codec; unknown or missing sections are never dropped. */
@Composable
fun ArrangeLibraryChipsDialog(
    preferences: PreferenceSnapshot,
    controller: SettingsController,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) {
    SettingsArrangeDialog(
        title = Res.string.m6_arrange_chips,
        initial = preferences[LibraryChipOrderKey, DefaultLibraryFilterOrderPreference]
            .toLibraryFilterOrder()
            .map { filter -> ArrangeEntry(filter.name, libraryFilterLabel(filter)) },
        onDismiss = onDismiss,
        onSave = { order ->
            controller.edit {
                this[LibraryChipOrderKey] =
                    order.mapNotNull { id -> LibraryFilter.entries.firstOrNull { it.name == id } }
                        .toLibraryFilterPreference()
            }
        },
        onFailure = onFailure,
    )
}

private fun libraryFilterLabel(filter: LibraryFilter): StringResource = when (filter) {
    LibraryFilter.LIBRARY -> Res.string.m4_library
    LibraryFilter.PLAYLISTS -> Res.string.m4_playlists
    LibraryFilter.SONGS -> Res.string.m4_songs
    LibraryFilter.ARTISTS -> Res.string.m4_artists
    LibraryFilter.ALBUMS -> Res.string.m4_albums
}

/** The artwork-provider priority over the shared codec, with the labels the Android dialog shows. */
@Composable
fun ArrangeArtworkProvidersDialog(
    preferences: PreferenceSnapshot,
    controller: SettingsController,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) {
    SettingsArrangeDialog(
        title = Res.string.m6_artwork_priority,
        initial = deserializeArtworkProviderOrder(preferences[ArtworkProviderOrderKey])
            .map { provider -> ArrangeEntry(provider.name, artworkProviderLabel(provider)) },
        onDismiss = onDismiss,
        onSave = { order ->
            controller.edit {
                this[ArtworkProviderOrderKey] =
                    order.mapNotNull { id -> PreferredArtworkProvider.entries.firstOrNull { it.name == id } }
                        .serializeArtworkProviderOrder()
            }
        },
        onFailure = onFailure,
    )
}

private fun artworkProviderLabel(provider: PreferredArtworkProvider): StringResource = when (provider) {
    PreferredArtworkProvider.LOCAL_EMBEDDED -> Res.string.ios_lane_artwork_local_embedded
    PreferredArtworkProvider.ORIGINAL_METADATA -> Res.string.ios_lane_artwork_original_metadata
    PreferredArtworkProvider.TIDAL -> Res.string.ios_lane_artwork_tidal
    PreferredArtworkProvider.SPOTIFY_CANVAS -> Res.string.ios_lane_artwork_spotify_canvas
    PreferredArtworkProvider.ARCHIVETUNE_CANVAS -> Res.string.ios_lane_artwork_archivetune_canvas
}

/**
 * The playlist-tag order over the live tag catalog. Tags are runtime names, so the generic label dialog cannot show
 * them; this one keeps the same move/save contract with the tag's own name.
 */
@Composable
fun ArrangePlaylistTagsDialog(
    tagsRepository: moe.rukamori.archivetune.shared.domain.playlisttags.PlaylistTagsRepository,
    preferences: PreferenceSnapshot,
    controller: SettingsController,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) {
    val tags by tagsRepository.observeTags().collectAsState(initial = null)
    val catalog = tags
    if (catalog == null) return
    var order by remember(catalog) {
        mutableStateOf(preferences[PlaylistTagOrderKey, ""].toPlaylistTagOrder(catalog.map { it.id }))
    }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(Res.string.m6_arrange_tags)) },
        text = {
            SettingsOrderList(order, { id -> catalog.firstOrNull { it.id == id }?.name ?: id }, !saving) { from, to ->
                order = order.toMutableList().apply { add(to, removeAt(from)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            controller.edit { this[PlaylistTagOrderKey] = order.toPlaylistTagPreference() }
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            onFailure()
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text(stringResource(Res.string.m6_save)) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(Res.string.m6_cancel)) }
        },
    )
}

/**
 * The Home screens picker. What it edits is the stored set when there is one, otherwise the resolved default, so the
 * checkboxes and the summary agree with the CSV. YouTube is always on and cannot be toggled; a source whose account
 * is not signed in is annotated but can still be chosen, as on Android. Confirming an untouched picker leaves an
 * unset key unset, so a fresh install keeps resolving defaults as accounts come and go.
 */
@Composable
fun HomeScreensDialog(
    preferences: PreferenceSnapshot,
    controller: SettingsController,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) {
    val storedRaw = preferences[ActiveHomeSourcesKey, ""]
    val spotifyAvailable = preferences[SpotifySpDcKey, ""].isNotBlank()
    val qqAvailable =
        preferences[QqMusicUinKey, ""].isNotBlank() && preferences[QqMusicMusickeyKey, ""].isNotBlank()
    val stored = homeSourcesFromPreference(storedRaw)
    val selection = stored.ifEmpty { resolvedHomeSources(storedRaw, spotifyAvailable, qqAvailable) }
    var working by remember { mutableStateOf(selection) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(Res.string.m6_home_screens)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(HomeSource.entries, key = { _, source -> source.name }) { _, source ->
                    val checked = source in working
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            enabled = !saving && source != HomeSource.YOUTUBE,
                            onCheckedChange = {
                                working = canonicalHomeSources(
                                    if (checked) working - source else working + source,
                                )
                            },
                        )
                        Text(stringResource(homeSourceLabel(source)))
                        val unavailable = when (source) {
                            HomeSource.YOUTUBE -> false
                            HomeSource.SPOTIFY -> !spotifyAvailable
                            HomeSource.QQ -> !qqAvailable
                        }
                        if (unavailable) {
                            Text(
                                stringResource(Res.string.ios_lane_home_sign_in_first),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val chosen = canonicalHomeSources(working).ifEmpty { listOf(HomeSource.YOUTUBE) }
                    if (chosen.toSet() == selection.toSet()) {
                        onDismiss()
                        return@TextButton
                    }
                    saving = true
                    scope.launch {
                        try {
                            controller.edit {
                                this[ActiveHomeSourcesKey] = chosen.joinToString(",") { it.name }
                            }
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            onFailure()
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text(stringResource(Res.string.m6_save)) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(Res.string.m6_cancel)) }
        },
    )
}

private fun homeSourceLabel(source: HomeSource): StringResource = when (source) {
    HomeSource.YOUTUBE -> Res.string.m4_youtube
    HomeSource.SPOTIFY -> Res.string.m4_spotify
    HomeSource.QQ -> Res.string.m4_qq
}

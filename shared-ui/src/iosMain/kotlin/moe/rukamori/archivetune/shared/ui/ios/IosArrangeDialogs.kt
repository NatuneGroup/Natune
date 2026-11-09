package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.Composable
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.settings.*

@Composable
internal fun IosArrangeLibraryChipsDialog(preferences: PreferenceSnapshot, controller: SettingsController,
    onDismiss: () -> Unit, onFailure: () -> Unit) = ArrangeLibraryChipsDialog(preferences, controller, onDismiss, onFailure)

@Composable
internal fun IosArrangeArtworkProvidersDialog(preferences: PreferenceSnapshot, controller: SettingsController,
    onDismiss: () -> Unit, onFailure: () -> Unit) = ArrangeArtworkProvidersDialog(preferences, controller, onDismiss, onFailure)

@Composable
internal fun IosArrangePlaylistTagsDialog(graph: IosSessionGraph, preferences: PreferenceSnapshot, controller: SettingsController,
    onDismiss: () -> Unit, onFailure: () -> Unit) = ArrangePlaylistTagsDialog(graph.playlistTags, preferences, controller, onDismiss, onFailure)

@Composable
internal fun IosHomeScreensDialog(preferences: PreferenceSnapshot, controller: SettingsController,
    onDismiss: () -> Unit, onFailure: () -> Unit) = HomeScreensDialog(preferences, controller, onDismiss, onFailure)

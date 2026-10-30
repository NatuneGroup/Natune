package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import moe.rukamori.archivetune.shared.domain.library.LibraryFilter
import moe.rukamori.archivetune.shared.domain.library.toLibraryFilterOrder
import moe.rukamori.archivetune.shared.domain.library.toLibraryFilterPreference
import moe.rukamori.archivetune.shared.domain.playlisttags.PlaylistTagsRepository
import moe.rukamori.archivetune.shared.domain.settings.ActiveHomeSourcesKey
import moe.rukamori.archivetune.shared.domain.settings.ArtworkProviderOrderKey
import moe.rukamori.archivetune.shared.domain.settings.LibraryChipOrderKey
import moe.rukamori.archivetune.shared.domain.settings.PlaylistTagOrderKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.QqMusicMusickeyKey
import moe.rukamori.archivetune.shared.domain.settings.QqMusicUinKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifySpDcKey
import moe.rukamori.archivetune.shared.ui.settings.SettingsController

/** The general-settings editors this host draws itself, one at a time. */
internal enum class DesktopGeneralEditor { HOME_SCREENS, LIBRARY_CHIPS, PLAYLIST_TAGS, ARTWORK_PROVIDERS }

/**
 * The stored preference formats the editors read and write. They are the Android app's, so a backup carries the same
 * values either way.
 */
internal object DesktopGeneralEditorFormats {
    /** The Android `HomeSource` names, in the order its picker lists them. */
    val homeSources: List<String> = listOf("YOUTUBE", "SPOTIFY", "QQ")

    /** The Android `PreferredArtworkProvider` names in its default order. */
    val defaultArtworkProviders: List<String> =
        listOf("LOCAL_EMBEDDED", "ORIGINAL_METADATA", "TIDAL", "SPOTIFY_CANVAS", "ARCHIVETUNE_CANVAS")

    /** Unknown or repeated names are dropped and missing providers are appended in default order. */
    fun artworkProviderOrder(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return defaultArtworkProviders
        val parsed = raw.split(',').map(String::trim).filter { it in defaultArtworkProviders }.distinct()
        return parsed + defaultArtworkProviders.filterNot(parsed::contains)
    }

    fun artworkProviderPreference(order: List<String>): String = artworkProviderOrder(order.joinToString(",")).joinToString(",")

    /** Saved ids first, in their saved order, then every other available tag in catalog order. */
    fun playlistTagOrder(raw: String, availableIds: List<String>): List<String> {
        val available = availableIds.toSet()
        val saved = raw.split(',').map(String::trim).filter { it.isNotEmpty() && it in available }.distinct()
        return saved + availableIds.filterNot(saved::contains)
    }

    fun playlistTagPreference(order: List<String>): String = order.distinct().joinToString(",")

    /** Unknown or repeated names are dropped; case is ignored as the Android reader ignores it. */
    fun parseHomeSources(raw: String): List<String> =
        raw.split(',').mapNotNull { name -> homeSources.firstOrNull { it.equals(name.trim(), ignoreCase = true) } }.distinct()

    fun spotifyHomeAvailable(preferences: PreferenceSnapshot): Boolean = preferences[SpotifySpDcKey, ""].isNotBlank()

    fun qqHomeAvailable(preferences: PreferenceSnapshot): Boolean =
        preferences[QqMusicUinKey, ""].isNotBlank() && preferences[QqMusicMusickeyKey, ""].isNotBlank()

    /**
     * What the picker starts with: the stored set when there is one, otherwise the default the Android app resolves for
     * an unset key (YouTube, plus each signed-in page).
     */
    fun homeSourceSelection(preferences: PreferenceSnapshot): List<String> =
        parseHomeSources(preferences[ActiveHomeSourcesKey, ""]).ifEmpty {
            buildList {
                add("YOUTUBE")
                if (spotifyHomeAvailable(preferences)) add("SPOTIFY")
                if (qqHomeAvailable(preferences)) add("QQ")
            }
        }

    /**
     * The value to store, or null when the choice equals the starting selection. Confirming an untouched picker must
     * leave an unset key unset, or a signed-out confirmation would pin the set to YouTube alone for good.
     */
    fun homeSourcesToStore(initial: List<String>, chosen: Collection<String>): String? {
        if (chosen.toSet() == initial.toSet()) return null
        return homeSources.filter { it in chosen }.joinToString(",")
    }

    fun <T> move(list: List<T>, index: Int, offset: Int): List<T> {
        val target = index + offset
        if (index !in list.indices || target !in list.indices) return list
        return list.toMutableList().apply { add(target, removeAt(index)) }
    }
}

/** Shows [editor] over the current page and stores what the user confirms. */
@Composable
internal fun DesktopGeneralEditorHost(
    editor: DesktopGeneralEditor,
    controller: SettingsController,
    playlistTags: PlaylistTagsRepository,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot ?: return
    val scope = rememberCoroutineScope()
    fun save(change: suspend () -> Unit) {
        scope.launch {
            try {
                change()
                onDismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
            }
        }
    }
    when (editor) {
        DesktopGeneralEditor.LIBRARY_CHIPS -> {
            val initial = preferences[LibraryChipOrderKey, ""].toLibraryFilterOrder()
            DesktopOrderDialog(
                title = DesktopSettingsText["library_chips_title"],
                initial = initial.map { it.name to DesktopSettingsText["library_chip_${it.name}"] },
                onDismiss = onDismiss,
                onConfirm = { order ->
                    val filters = order.mapNotNull { name -> LibraryFilter.entries.firstOrNull { it.name == name } }
                    save { controller.edit { this[LibraryChipOrderKey] = filters.toLibraryFilterPreference() } }
                },
                onReset = { save { controller.edit { this[LibraryChipOrderKey] = null } } },
            )
        }
        DesktopGeneralEditor.ARTWORK_PROVIDERS -> {
            val initial = DesktopGeneralEditorFormats.artworkProviderOrder(preferences[ArtworkProviderOrderKey, ""])
            DesktopOrderDialog(
                title = DesktopSettingsText["artwork_providers_title"],
                initial = initial.map { it to DesktopSettingsText["artwork_provider_$it"] },
                onDismiss = onDismiss,
                onConfirm = { order ->
                    save {
                        controller.edit {
                            this[ArtworkProviderOrderKey] = DesktopGeneralEditorFormats.artworkProviderPreference(order)
                        }
                    }
                },
                onReset = { save { controller.edit { this[ArtworkProviderOrderKey] = null } } },
            )
        }
        DesktopGeneralEditor.PLAYLIST_TAGS -> {
            val tags by remember { playlistTags.observeTags() }.collectAsState(null)
            val loaded = tags
            val initial = loaded?.let { list ->
                val names = list.associate { it.id to it.name }
                DesktopGeneralEditorFormats.playlistTagOrder(preferences[PlaylistTagOrderKey, ""], list.map { it.id })
                    .map { id -> id to names.getValue(id) }
            }
            DesktopOrderDialog(
                title = DesktopSettingsText["playlist_tags_order_title"],
                initial = initial,
                emptyText = DesktopSettingsText["playlist_tags_order_empty"],
                onDismiss = onDismiss,
                onConfirm = { order ->
                    save {
                        controller.edit { this[PlaylistTagOrderKey] = DesktopGeneralEditorFormats.playlistTagPreference(order) }
                    }
                },
                onReset = { save { controller.edit { this[PlaylistTagOrderKey] = null } } },
            )
        }
        DesktopGeneralEditor.HOME_SCREENS -> {
            val initial = remember { DesktopGeneralEditorFormats.homeSourceSelection(preferences) }
            DesktopHomeScreensDialog(
                initial = initial,
                spotifyAvailable = DesktopGeneralEditorFormats.spotifyHomeAvailable(preferences),
                qqAvailable = DesktopGeneralEditorFormats.qqHomeAvailable(preferences),
                onDismiss = onDismiss,
                onConfirm = { chosen ->
                    val stored = DesktopGeneralEditorFormats.homeSourcesToStore(initial, chosen)
                    if (stored == null) onDismiss() else save { controller.edit { this[ActiveHomeSourcesKey] = stored } }
                },
            )
        }
    }
}

/** A reorder list with move buttons. A null [initial] is still loading. */
@Composable
internal fun DesktopOrderDialog(
    title: String,
    initial: List<Pair<String, String>>?,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    onReset: (() -> Unit)? = null,
    emptyText: String = DesktopSettingsText["editor_empty"],
) {
    var order by remember(initial) { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            when {
                initial == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text(DesktopSettingsText["editor_loading"], Modifier.padding(start = 16.dp))
                }
                order.isEmpty() -> Text(emptyText, style = MaterialTheme.typography.bodyMedium)
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(order, key = { _, entry -> entry.first }) { index, entry ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.second, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = { order = DesktopGeneralEditorFormats.move(order, index, -1) }, enabled = index > 0) {
                                Icon(Icons.Filled.KeyboardArrowUp, DesktopSettingsText["editor_move_up"])
                            }
                            IconButton(
                                onClick = { order = DesktopGeneralEditorFormats.move(order, index, 1) },
                                enabled = index < order.lastIndex,
                            ) {
                                Icon(Icons.Filled.KeyboardArrowDown, DesktopSettingsText["editor_move_down"])
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(order.map { it.first }) }, enabled = !order.isEmpty()) {
                Text(DesktopSettingsText["editor_save"])
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onReset != null) TextButton(onClick = onReset) { Text(DesktopSettingsText["editor_reset"]) }
                TextButton(onClick = onDismiss) { Text(DesktopSettingsText["editor_cancel"]) }
            }
        },
    )
}

/** The Home pages picker. A page whose account is signed out stays selectable, so a parked choice survives. */
@Composable
private fun DesktopHomeScreensDialog(
    initial: List<String>,
    spotifyAvailable: Boolean,
    qqAvailable: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var chosen by remember { mutableStateOf(initial.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(DesktopSettingsText["home_screens_title"]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(DesktopSettingsText["home_screens_description"], style = MaterialTheme.typography.bodyMedium)
                DesktopGeneralEditorFormats.homeSources.forEach { source ->
                    val available = when (source) {
                        "SPOTIFY" -> spotifyAvailable
                        "QQ" -> qqAvailable
                        else -> true
                    }
                    fun toggle() { chosen = if (source in chosen) chosen - source else chosen + source }
                    Row(Modifier.fillMaxWidth().clickable { toggle() }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = source in chosen, onCheckedChange = { toggle() })
                        Column(Modifier.weight(1f)) {
                            Text(DesktopSettingsText["home_source_$source"], style = MaterialTheme.typography.bodyLarge)
                            if (!available) {
                                Text(DesktopSettingsText["home_source_needs_sign_in"], style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosen) }, enabled = chosen.isNotEmpty()) { Text(DesktopSettingsText["editor_save"]) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(DesktopSettingsText["editor_cancel"]) } },
    )
}

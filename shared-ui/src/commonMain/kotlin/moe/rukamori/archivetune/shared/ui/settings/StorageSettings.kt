package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

enum class SettingsCacheKind { SONG, IMAGE, CANVAS, LYRICS }

/** Folder selection must finish the actual migration before returning a restart token; null is cancel. */
@Composable
fun StorageSettings(
    controller: SettingsController,
    usedBytes: Map<SettingsCacheKind, Long>,
    currentStorageLabel: String?,
    storageBusy: Boolean,
    pickAndCommitStorage: suspend () -> String?,
    clearCache: suspend (SettingsCacheKind) -> Unit,
    applyCacheLimits: suspend (PreferenceSnapshot) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    var clearing by remember { mutableStateOf<SettingsCacheKind?>(null) }
    var picking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val trimmerAvailable = preferences[MaxImageCacheSizeKey, 512] != 0 || preferences[MaxSongCacheSizeKey, 1024] != 0
    LaunchedEffect(trimmerAvailable, preferences[SmartTrimmerKey]) {
        if (!trimmerAvailable && preferences[SmartTrimmerKey, false])
            controller.edit { if (this[MaxImageCacheSizeKey, 512] == 0 && this[MaxSongCacheSizeKey, 1024] == 0) this[SmartTrimmerKey] = false }
    }
    Column(modifier) {
        currentStorageLabel?.let { Text(it) }
        usedBytes.forEach { (kind, bytes) ->
            Text(stringResource(kind.title()) + ": " + stringResource(Res.string.m6_cache_bytes, bytes.coerceAtLeast(0)))
        }
        SettingsPage(storageSettingsRows(preferences, storageBusy || picking, { kind -> clearing = kind }, {
            if (!picking && !storageBusy) {
                picking = true
                scope.launch {
                    try { pickAndCommitStorage()?.let { controller.storageCommitted(it) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { onFailure() }
                    finally { picking = false }
                }
            }
        }), preferences, controller, scrollTo, onFailure = onFailure, modifier = Modifier.weight(1f),
            onCommitted = { change ->
                applyCacheLimits(change.after)
                listOf(SettingsCacheKind.SONG to MaxSongCacheSizeKey, SettingsCacheKind.IMAGE to MaxImageCacheSizeKey,
                    SettingsCacheKind.CANVAS to MaxCanvasCacheSizeKey).forEach { (kind, key) ->
                    if (key in change.changedKeys && change.after[key] == 0) clearCache(kind)
                }
            })
    }
    clearing?.let { kind ->
        SettingsConfirmActionDialog(kind.title(), { clearCache(kind) }, { clearing = null }, onFailure)
    }
}

fun storageSettingsRows(
    preferences: PreferenceSnapshot,
    storageBusy: Boolean,
    clear: (SettingsCacheKind) -> Unit,
    pickFolder: () -> Unit,
): List<SettingsRow> = buildList {
    fun capacity(anchor: String, title: StringResource, key: IntPreferenceKey, default: Int, canvas: Boolean = false) {
        add(SettingsRow.IntChoice(anchor, title, key, default, cacheCapacityOptions(canvas), write = { selected ->
            this[key] = selected
            if (this[MaxSongCacheSizeKey, 1024] == 0 && this[MaxImageCacheSizeKey, 512] == 0) this[SmartTrimmerKey] = false
        }))
    }
    add(SettingsRow.Action("storage_folder", Res.string.m6_storage_folder, pickFolder, enabled = !storageBusy))
    capacity("max_song_cache_size", Res.string.m6_song_cache_limit, MaxSongCacheSizeKey, 1024)
    add(SettingsRow.Action("clear_song_cache", Res.string.m6_clear_song_cache, { clear(SettingsCacheKind.SONG) }))
    capacity("max_image_cache_size", Res.string.m6_image_cache_limit, MaxImageCacheSizeKey, 512)
    add(SettingsRow.Action("clear_image_cache", Res.string.m6_clear_image_cache, { clear(SettingsCacheKind.IMAGE) }))
    capacity("max_cache_size", Res.string.m6_canvas_cache_limit, MaxCanvasCacheSizeKey, 256, canvas = true)
    add(SettingsRow.Action("clear_canvas_cache", Res.string.m6_clear_canvas_cache, { clear(SettingsCacheKind.CANVAS) }))
    add(SettingsRow.Action("clear_lyrics_cache", Res.string.m6_clear_lyrics_cache, { clear(SettingsCacheKind.LYRICS) }))
    add(SettingsRow.Toggle("smart_trimmer", Res.string.m6_smart_trimmer, SmartTrimmerKey, false,
        enabled = preferences[MaxSongCacheSizeKey, 1024] != 0 || preferences[MaxImageCacheSizeKey, 512] != 0))
}

private fun SettingsCacheKind.title(): StringResource = when (this) {
    SettingsCacheKind.SONG -> Res.string.m6_clear_song_cache
    SettingsCacheKind.IMAGE -> Res.string.m6_clear_image_cache
    SettingsCacheKind.CANVAS -> Res.string.m6_clear_canvas_cache
    SettingsCacheKind.LYRICS -> Res.string.m6_clear_lyrics_cache
}

internal fun cacheCapacityOptions(canvas: Boolean): List<SettingsRow.IntOption> = buildList {
    add(SettingsRow.IntOption(0, Res.string.m6_cache_disabled))
    if (canvas) add(SettingsRow.IntOption(64, Res.string.m6_cache_64))
    add(SettingsRow.IntOption(128, Res.string.m6_cache_128))
    add(SettingsRow.IntOption(256, Res.string.m6_cache_256))
    add(SettingsRow.IntOption(512, Res.string.m6_cache_512))
    add(SettingsRow.IntOption(1024, Res.string.m6_cache_1024))
    add(SettingsRow.IntOption(2048, Res.string.m6_cache_2048))
    add(SettingsRow.IntOption(4096, Res.string.m6_cache_4096))
    add(SettingsRow.IntOption(8192, Res.string.m6_cache_8192))
    add(SettingsRow.IntOption(-1, Res.string.m6_cache_unlimited))
}

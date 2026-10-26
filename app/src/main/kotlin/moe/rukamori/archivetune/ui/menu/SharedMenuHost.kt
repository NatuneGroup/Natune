/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.ui.menu

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.kmp.settings.AndroidMenuSharing
import moe.rukamori.archivetune.playlistexport.ExportPlaylistAsCsvUseCase
import moe.rukamori.archivetune.playlistexport.ObserveExportablePlaylistsUseCase
import moe.rukamori.archivetune.shared.ui.menu.MenuShareHandler

/** Keep the selected menu alive until TDLib/local sharing succeeds, so cancellation is not success. */
@Composable
internal fun rememberSongShareAction(
    mediaId: String,
    local: Boolean,
    mimeType: String?,
    onShared: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dismissed by rememberUpdatedState(onShared)
    var busy by remember { mutableStateOf(false) }
    return {
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    val activity = requireNotNull(context.menuActivity())
                    check(MenuShareHandler(AndroidMenuSharing(activity)).song(mediaId, local, mimeType))
                    dismissed()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { Toast.makeText(context, R.string.error_unknown, Toast.LENGTH_SHORT).show() }
                finally { busy = false }
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PlaylistExportMenuEntryPoint {
    fun exporter(): ExportPlaylistAsCsvUseCase
    fun playlists(): ObserveExportablePlaylistsUseCase
}

/** Reuses the retained nonempty CSV use case, naming/escaping and SAF writer; no new export format. */
@Composable
internal fun rememberPlaylistExportAction(
    playlistId: String,
    scope: CoroutineScope,
    onExported: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val entry = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, PlaylistExportMenuEntryPoint::class.java)
    }
    val completed by rememberUpdatedState(onExported)
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { destination ->
        val selected = pending
        pending = null
        if (destination != null && selected != null) {
            busy = true
            scope.launch {
                try {
                    entry.exporter()(selected, destination)
                    Toast.makeText(context, R.string.export_playlist_success, Toast.LENGTH_SHORT).show()
                    completed()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { Toast.makeText(context, R.string.export_playlist_failed, Toast.LENGTH_SHORT).show() }
                finally { busy = false }
            }
        }
    }
    return {
        if (!busy && pending == null) {
            busy = true
            scope.launch {
                try {
                    val selected = entry.playlists()().first().firstOrNull { it.id == playlistId }
                        ?: error("Playlist is not exportable")
                    pending = selected.id
                    launcher.launch(selected.suggestedFileName)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    pending = null
                    Toast.makeText(context, R.string.export_playlist_failed, Toast.LENGTH_SHORT).show()
                } finally { busy = false }
            }
        }
    }
}

private tailrec fun Context.menuActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.menuActivity()
    else -> null
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.menu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One canvas video the host found for a song. [key] is the host's own identity and comes back to [CanvasSaveMenuHost.save];
 * [label] is the provider name, shown as given.
 */
data class CanvasSaveSource(val key: String, val label: String)

/**
 * Host work behind the Save canvas action. Both calls run off the main thread and this module makes no network or file
 * call of its own. [fetchSources] treats a failure the same as an empty result, as the baseline did.
 */
interface CanvasSaveMenuHost {
    /** Finds the canvas sources for [songId]. An empty list means no source was found. */
    suspend fun fetchSources(songId: String): List<CanvasSaveSource>

    /** Caches [source] as the canvas of [songId]. True only after the host's cache write has succeeded. */
    suspend fun save(songId: String, source: CanvasSaveSource): Boolean
}

/**
 * The state of one Save canvas action: the source lookup, the save in flight, and the source picker that waits for a
 * choice. Each outcome is reported once through [onMessage] after the matching work finished, so the host never shows a
 * saved message for a failed write.
 */
class CanvasSaveMenuState internal constructor(
    private val songId: String,
    private val host: CanvasSaveMenuHost,
    private val scope: CoroutineScope,
    private val onMessage: (StringResource) -> Unit,
) {
    /** True while the sources for the song are being looked up. */
    var fetching by mutableStateOf(false)
        private set

    /** True while the chosen source is being cached. */
    var saving by mutableStateOf(false)
        private set

    /** The sources waiting for a choice, or null when the picker is closed. */
    var sources by mutableStateOf<List<CanvasSaveSource>?>(null)
        private set

    /** Looks up the sources. It opens the picker when some exist, and otherwise reports that none are available. */
    fun request() {
        if (fetching || saving) return
        fetching = true
        scope.launch {
            val found = try {
                host.fetchSources(songId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            fetching = false
            if (found.isEmpty()) {
                onMessage(Res.string.m6_canvas_unavailable)
            } else {
                sources = found
            }
        }
    }

    /** Saves the chosen source and reports the result. The picker closes before the save starts. */
    fun pick(source: CanvasSaveSource) {
        sources = null
        saving = true
        scope.launch {
            val saved = try {
                host.save(songId, source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            saving = false
            onMessage(if (saved) Res.string.m6_canvas_saved else Res.string.m6_canvas_save_failed)
        }
    }

    /** Closes the picker without saving. */
    fun dismissPicker() {
        sources = null
    }
}

/**
 * Creates the Save canvas state for [songId]. The host keeps [host] for as long as the song is shown. [onMessage] receives
 * the outcome text, and the host shows it as its own toast or snackbar.
 */
@Composable
fun rememberCanvasSaveMenuState(
    songId: String,
    host: CanvasSaveMenuHost,
    onMessage: (StringResource) -> Unit,
): CanvasSaveMenuState {
    val scope = rememberCoroutineScope()
    val currentOnMessage by rememberUpdatedState(onMessage)
    return remember(songId, host) {
        CanvasSaveMenuState(songId, host, scope) { currentOnMessage(it) }
    }
}

/**
 * The Save canvas overflow action. Its label reads "Saving Canvas" while the save runs, and it is disabled during a
 * lookup or save. Running it opens the source picker through [CanvasSourcePicker] rather than from this action.
 */
fun CanvasSaveMenuState.saveCanvasOverflowAction(): OverflowAction = OverflowAction(
    id = "save_canvas",
    label = if (saving) Res.string.m6_canvas_saving else Res.string.m6_save_canvas,
    enabled = !fetching && !saving,
    run = { request() },
)

/**
 * The source picker. Mount it outside the overflow menu, because the menu closes as soon as the action succeeds and the
 * picker must outlive it. It draws nothing while [CanvasSaveMenuState.sources] is null.
 */
@Composable
fun CanvasSourcePicker(state: CanvasSaveMenuState) {
    val available = state.sources ?: return
    AlertDialog(
        onDismissRequest = state::dismissPicker,
        title = { Text(stringResource(Res.string.m6_canvas_source_title)) },
        text = {
            Column {
                available.forEach { source ->
                    TextButton(
                        onClick = { state.pick(source) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(source.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = state::dismissPicker) {
                Text(stringResource(Res.string.m6_cancel))
            }
        },
    )
}

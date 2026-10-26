/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence

/**
 * The one row menu of the desktop host. A row's overflow opens it on that row's occurrence. It offers only the queue
 * commands the desktop queue owner answers. The rest of the baseline song menu needs library, download, share and artist
 * owners that this host does not connect yet, so those rows are left out instead of appearing as no-ops.
 */
internal class DesktopItemMenuState {
    /** The row the menu is open for, or null while it is closed. The dialog reads it, so a click shows the menu at once. */
    var target: ContentOccurrence? by mutableStateOf(null)
        private set

    fun open(occurrence: ContentOccurrence) {
        target = occurrence
    }

    fun dismiss() {
        target = null
    }

    /**
     * Queues the open row with [kind] in the one queue. The menu closes before the command runs, so a second click cannot
     * queue the row twice. A refused command is written to the local log, as every other desktop action is.
     */
    fun enqueue(graph: DesktopSessionGraph, kind: UserQueuedKind) {
        val occurrence = target ?: return
        target = null
        graph.scope.launch {
            try {
                graph.queue.insert(listOf(occurrence), kind)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("the row was not queued: ${failure.javaClass.simpleName}")
            }
        }
    }
}

/**
 * The row menu as a dialog over the page it opened on. The navigation host does not key the route body, so the effect
 * is keyed on the route itself: leaving a page closes its menu, and a menu opened on one page is never shown again on
 * another page.
 */
@Composable
internal fun DesktopItemMenuDialog(menu: DesktopItemMenuState, graph: DesktopSessionGraph, routeKey: String) {
    DisposableEffect(routeKey) { onDispose { menu.dismiss() } }
    val occurrence = menu.target ?: return
    AlertDialog(
        onDismissRequest = menu::dismiss,
        title = { Text(occurrence.item.title) },
        text = {
            Column {
                TextButton(
                    onClick = { menu.enqueue(graph, UserQueuedKind.PLAY_NEXT) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(DesktopStrings["play_next"]) }
                TextButton(
                    onClick = { menu.enqueue(graph, UserQueuedKind.ADD_TO_QUEUE) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(DesktopStrings["add_to_queue"]) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = menu::dismiss) { Text(DesktopStrings["cancel"]) } },
    )
}

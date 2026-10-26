package moe.rukamori.archivetune.shared.ui.ios

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
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m5pq_add_to_queue
import moe.rukamori.archivetune.shared.ui.resources.m6_cancel
import moe.rukamori.archivetune.shared.ui.resources.m6_play_next
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSUUID

/**
 * The one row menu of the iOS host. A row's overflow opens it on that row's occurrence. It offers the queue
 * commands the iOS queue owner answers. The rest of the baseline song menu needs library, download, share and
 * artist owners that this host does not connect yet, so those rows are left out instead of appearing as no-ops.
 */
internal class IosItemMenuState {
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
     * Queues the open row with [kind] in the one queue. The menu closes before the command runs, so a second tap cannot
     * queue the row twice. A refused command is written to the local log, as every other iOS action is.
     */
    fun enqueue(graph: IosSessionGraph, kind: UserQueuedKind) {
        val occurrence = target ?: return
        target = null
        graph.scope.launch {
            try {
                graph.queue.insert(listOf(occurrence.queuedEntry()), kind)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("the row was not queued: ${failure::class.simpleName}")
            }
        }
    }
}

/**
 * A row keeps the occurrence id of its list, and the queue already holds that id once the list plays, so it would refuse
 * the insert. Every queue command therefore gets its own occurrence of the same item.
 */
private fun ContentOccurrence.queuedEntry(): ContentOccurrence =
    ContentOccurrence("queued:${NSUUID().UUIDString}", item, setVideoId)

/**
 * The row menu as a dialog over the page it opened on. The navigation host does not key the route body, so the effect
 * is keyed on the route itself: leaving a page closes its menu, and a menu opened on one page is never shown again on
 * another page.
 */
@Composable
internal fun IosItemMenuDialog(menu: IosItemMenuState, graph: IosSessionGraph, routeKey: String) {
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
                ) { Text(stringResource(Res.string.m6_play_next)) }
                TextButton(
                    onClick = { menu.enqueue(graph, UserQueuedKind.ADD_TO_QUEUE) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(Res.string.m5pq_add_to_queue)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = menu::dismiss) { Text(stringResource(Res.string.m6_cancel)) } },
    )
}

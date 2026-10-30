package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import java.awt.EventQueue
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.desktop.integration.DesktopHostServices
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.settings.BooleanPreferenceKey
import moe.rukamori.archivetune.shared.ui.ArchiveTuneRoot
import moe.rukamori.archivetune.shared.ui.theme.ArchiveTuneTheme

/**
 * Opens the one session graph for this process. A failure is logged by class name only and returns null, so the
 * window shows [DesktopUnavailableRoot] instead of a memory, plaintext or empty stand-in.
 */
internal fun openDesktopSession(instanceDirectory: Path): DesktopSessionGraph? = try {
    runBlocking { DesktopSessionGraph.open(instanceDirectory) }
} catch (failure: Exception) {
    desktopDiagnostic("session graph unavailable: ${failure.javaClass.simpleName}")
    null
} catch (failure: LinkageError) {
    desktopDiagnostic("session graph unavailable: ${failure.javaClass.simpleName}")
    null
}

/**
 * Mounts the root over [graph] inside [window]. The window services are created once per window on the AWT event
 * thread, borrow the graph's player, metadata and scope, and are closed when this composition leaves. Window activity
 * feeds the root's visible and resumed inputs. Hiding the window never pauses playback.
 */
@Composable
internal fun DesktopMountedRoot(window: Window, graph: DesktopSessionGraph, closeRequests: DesktopCloseRequests, quit: () -> Unit) {
    val services = remember(window, graph) {
        DesktopHostServices(
            window = window,
            controller = graph.player,
            metadata = graph.metadata,
            scope = graph.scope,
            dispatchToOwner = { action -> EventQueue.invokeLater { action() } },
            next = { graph.scope.launch { graph.skipNext() } },
            previous = { graph.scope.launch { graph.skipPrevious() } },
            openUri = { uri -> openDesktopUri(uri, graph::report) },
            quit = quit,
            reportUnavailable = graph::report,
        )
    }
    DisposableEffect(services) {
        // The close request hides the window only through these services, so it is registered while they are alive.
        closeRequests.attach { services.hideOnClose(preferTray = graph.hidesWindowOnClose()) }
        onDispose {
            closeRequests.detach()
            services.close()
        }
    }
    // The platform back gesture in this window is Alt+Left. The dispatcher consumes only that press, so no control sees it.
    val backRequests = remember(graph) { MutableSharedFlow<Unit>(extraBufferCapacity = 4) }
    DisposableEffect(window, backRequests) {
        val dispatcher = KeyEventDispatcher { event ->
            val back = event.getID() == KeyEvent.KEY_PRESSED && event.keyCode == KeyEvent.VK_LEFT && event.isAltDown &&
                event.component?.let { SwingUtilities.getWindowAncestor(it) } === window
            if (back) backRequests.tryEmit(Unit)
            back
        }
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        focus.addKeyEventDispatcher(dispatcher)
        onDispose { focus.removeKeyEventDispatcher(dispatcher) }
    }
    // Recognition runs on this window's UI scope and gives the microphone back when the window's composition leaves.
    val uiScope = rememberCoroutineScope()
    val recognition = remember(graph, uiScope) { DesktopRecognitionMount(graph, uiScope) }
    DisposableEffect(recognition) { onDispose { recognition.close() } }
    // Feedback reviews live with the window, so a review stays open while the user moves between pages.
    val feedback = remember(graph, uiScope) { DesktopFeedbackMount(graph, uiScope) }
    DisposableEffect(feedback) { onDispose { feedback.close() } }
    val host = remember(graph, backRequests, recognition, feedback) {
        desktopHostBindings(graph, backRequests, recognition, feedback, quit)
    }
    val activity by services.windowActivity.activity.collectAsState()
    ArchiveTuneRoot(
        host = host,
        // A minimized or occluded window is not visible: frames, blur and shimmer stop. Audio keeps playing.
        visible = activity.visible && !activity.minimized && !activity.occluded,
        resumed = activity.resumed,
        opticalCapabilities = DesktopOpticalCapabilities,
    )
}

/**
 * The window's close request. The window hides to the tray only while its services are attached, the observed preference
 * allows it and a tray exists. Every other close takes the explicit exit, so no process is left without a window or a tray.
 */
internal class DesktopCloseRequests {
    private var hideToTray: (() -> Boolean)? = null

    /** Registered on the AWT event thread once the window services exist. */
    fun attach(hide: () -> Boolean) {
        hideToTray = hide
    }

    fun detach() {
        hideToTray = null
    }

    /** Runs on the AWT event thread. A hidden window keeps the process running; otherwise [exit] runs. */
    fun request(exit: () -> Unit) {
        if (hideToTray?.invoke() != true) exit()
    }
}

/**
 * The desktop close preference, written by the Window group's close-to-tray row on the settings home page. True hides
 * the window to the tray instead of exiting, but only where a tray exists; without one a close still exits.
 */
internal val DesktopHideToTrayKey = BooleanPreferenceKey("desktop_hide_to_tray")

private fun DesktopSessionGraph.hidesWindowOnClose(): Boolean = settings.snapshots.value?.get(DesktopHideToTrayKey) == true

/** The explicit boundary for a failed open. It names the gap and offers no library, playback or settings action. */
@Composable
internal fun DesktopUnavailableRoot() {
    ArchiveTuneTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(DesktopStrings["host_graph_unavailable"], textAlign = TextAlign.Center)
        }
    }
}

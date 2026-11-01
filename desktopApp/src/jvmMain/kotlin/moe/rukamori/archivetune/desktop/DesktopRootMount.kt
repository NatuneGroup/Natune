package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.desktop.integration.DesktopHostServices
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.settings.BooleanPreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.RepeatModeKey
import moe.rukamori.archivetune.shared.ui.ArchiveTuneRoot
import moe.rukamori.archivetune.shared.ui.theme.ArchiveTuneTheme

/**
 * Opens the one session graph for this process. A failure is logged by class name only and returns null, so the
 * window shows [DesktopUnavailableRoot] instead of a memory, plaintext or empty stand-in. A cancellation is rethrown
 * rather than logged as a failure.
 */
internal suspend fun openDesktopSession(instanceDirectory: Path): DesktopSessionGraph? = try {
    DesktopSessionGraph.open(instanceDirectory)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    desktopDiagnostic("session graph unavailable: ${failure.javaClass.simpleName}")
    null
} catch (failure: LinkageError) {
    desktopDiagnostic("session graph unavailable: ${failure.javaClass.simpleName}")
    null
}

/** Where the one session graph stands. The window mounts the root that matches, so no root reads a null graph. */
internal sealed interface DesktopSessionState {
    /** The open is still running on the loader's background coroutine. */
    data object Loading : DesktopSessionState

    class Ready(val graph: DesktopSessionGraph) : DesktopSessionState

    /** The open failed; [DesktopUnavailableRoot] names the gap and offers no action. */
    data object Unavailable : DesktopSessionState
}

/**
 * Owns the one open of the session graph for the process. The open starts from the window's composition and runs off the
 * launch thread, so the window appears at once with [DesktopLoadingRoot]. [close] gives an open still in flight, and then a
 * graph that opened, a bounded wait each. Past the bound the process exits without them, as it would for any background work.
 */
internal class DesktopSessionLoader(private val instanceDirectory: Path) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<DesktopSessionState>(DesktopSessionState.Loading)
    val state: StateFlow<DesktopSessionState> = mutableState.asStateFlow()

    @Volatile private var opening: Job? = null

    /** Starts the open once. The window composition calls this, because it is the first point that needs the graph. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        opening = scope.launch {
            val graph = try {
                openDesktopSession(instanceDirectory)
            } catch (failure: Throwable) {
                // openDesktopSession already handles the expected failures; any other one must not leave the window waiting.
                mutableState.value = DesktopSessionState.Unavailable
                throw failure
            }
            mutableState.value = graph?.let { DesktopSessionState.Ready(it) } ?: DesktopSessionState.Unavailable
        }
    }

    /** Process exit, after the window has stopped asking for work. */
    fun close() {
        val inFlight = opening
        if (inFlight != null) runBlocking { withTimeoutOrNull(CLOSE_TIMEOUT_MS) { inFlight.join() } }
        val graph = (mutableState.value as? DesktopSessionState.Ready)?.graph ?: return
        // The graph's own close is non-cancellable, so the bound applies to this wait rather than to the close.
        val closing = scope.launch { graph.close() }
        runBlocking { withTimeoutOrNull(CLOSE_TIMEOUT_MS) { closing.join() } }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MS = 5_000L
    }
}

/**
 * The window's content. It starts the loader from composition and mounts the root once the graph is ready, showing
 * [DesktopLoadingRoot] until then and [DesktopUnavailableRoot] if the open fails.
 */
@Composable
internal fun DesktopSessionWindowRoot(
    window: Window,
    sessions: DesktopSessionLoader,
    closeRequests: DesktopCloseRequests,
    windowState: WindowState,
    quit: () -> Unit,
) {
    LaunchedEffect(sessions) { sessions.start() }
    val session by sessions.state.collectAsState()
    when (val current = session) {
        is DesktopSessionState.Ready -> DesktopMountedRoot(window, current.graph, closeRequests, quit, windowState)
        DesktopSessionState.Loading -> DesktopLoadingRoot()
        DesktopSessionState.Unavailable -> DesktopUnavailableRoot()
    }
}

/**
 * Mounts the root over [graph] inside [window]. The window services are created once per window on the AWT event
 * thread, borrow the graph's player, metadata and scope, and are closed when this composition leaves. Window activity
 * feeds the root's visible and resumed inputs. Hiding the window never pauses playback.
 */
@Composable
internal fun DesktopMountedRoot(
    window: Window,
    graph: DesktopSessionGraph,
    closeRequests: DesktopCloseRequests,
    quit: () -> Unit,
    windowState: WindowState,
) {
    val services = remember(window, graph) {
        DesktopHostServices(
            window = window,
            controller = graph.player,
            metadata = graph.metadata,
            transport = graph.transport,
            scope = graph.scope,
            dispatchToOwner = { action -> EventQueue.invokeLater { action() } },
            next = { graph.scope.launch { graph.skipNext() } },
            previous = { graph.scope.launch { graph.skipPrevious() } },
            setShuffle = { enabled ->
                graph.scope.launch {
                    try {
                        if (!graph.queue.setShuffle(enabled)) graph.report("shuffle was not applied: a radio queue keeps server order")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        graph.report("shuffle was not applied: ${failure.javaClass.simpleName}")
                    }
                }
            },
            setRepeat = { repeat ->
                graph.scope.launch {
                    try {
                        graph.settings.edit { set(RepeatModeKey, repeat.storedValue) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        graph.report("repeat was not saved: ${failure.javaClass.simpleName}")
                    }
                }
            },
            setVolume = { volume -> graph.setVolume(volume) },
            openUri = { uri -> openDesktopUri(uri, graph::report) },
            quit = quit,
            // A room guest leaves queue order and position to the room host, matching the Android room rule.
            guestLocked = { graph.listenTogether.isGuestInRoom() },
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
        // The mouse's side buttons are no key event; button 4 is the platform's back gesture like Alt+Left.
        val mouse = AWTEventListener { event ->
            val press = event as? MouseEvent
            if (press != null && press.getID() == MouseEvent.MOUSE_PRESSED && press.button == 4 &&
                press.component?.let { SwingUtilities.getWindowAncestor(it) } === window) {
                backRequests.tryEmit(Unit)
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(mouse, AWTEvent.MOUSE_EVENT_MASK)
        onDispose {
            focus.removeKeyEventDispatcher(dispatcher)
            Toolkit.getDefaultToolkit().removeAWTEventListener(mouse)
        }
    }
    // Recognition runs on this window's UI scope and gives the microphone back when the window's composition leaves.
    val uiScope = rememberCoroutineScope()
    val recognition = remember(graph, uiScope) { DesktopRecognitionMount(graph, uiScope) }
    DisposableEffect(recognition) { onDispose { recognition.close() } }
    // Feedback reviews live with the window, so a review stays open while the user moves between pages.
    val feedback = remember(graph, uiScope) { DesktopFeedbackMount(graph, uiScope) }
    DisposableEffect(feedback) { onDispose { feedback.close() } }
    val host = remember(graph, backRequests, recognition, feedback) {
        desktopHostBindings(graph, backRequests, recognition, feedback, quit,
            toggleFullscreen = {
                windowState.placement =
                    if (windowState.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating
                    else WindowPlacement.Fullscreen
            })
    }
    val activity by services.windowActivity.activity.collectAsState()
    val platformDark by services.systemDark.collectAsState()
    // The platform watcher wins where it reports; everywhere else Compose's own reading answers.
    val effectiveDark = platformDark ?: isSystemInDarkTheme()
    CompositionLocalProvider(
        LocalDesktopWindowShown provides (activity.visible && !activity.minimized),
        LocalDesktopSystemDark provides effectiveDark,
    ) {
        ArchiveTuneRoot(
            host = host,
            // A minimized or occluded window is not visible: frames, blur and shimmer stop. Audio keeps playing.
            visible = activity.visible && !activity.minimized && !activity.occluded,
            resumed = activity.resumed,
            opticalCapabilities = DesktopOpticalCapabilities,
            systemDark = effectiveDark,
        )
    }
}

/** The OS light/dark reading as the host watchers report it, or null where no watcher answered yet. */
internal val LocalDesktopSystemDark = compositionLocalOf<Boolean?> { null }

/**
 * Whether this window is showing and not minimized. Occlusion is left out on purpose: a fullscreen video is its own
 * window and covers this one, so an occlusion gate would close the very video that caused it.
 */
internal val LocalDesktopWindowShown = compositionLocalOf { false }

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

/** The window while the session graph opens. A spinner needs no text, so it adds no string resource. */
@Composable
internal fun DesktopLoadingRoot() {
    ArchiveTuneTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

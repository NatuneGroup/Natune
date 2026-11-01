package moe.rukamori.archivetune.desktop.integration

import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.linux.HyprlandWorkspaceVisibility
import moe.rukamori.archivetune.desktop.integration.linux.LinuxAppearanceTheme
import moe.rukamori.archivetune.desktop.integration.linux.LinuxSystemIntegration
import moe.rukamori.archivetune.desktop.integration.linux.LinuxWindowVisibility
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode

/** The real host binding: borrows (never creates/closes) the app's one player/queue owner. */
class DesktopHostServices(
    private val window: Window,
    controller: MusicPlayerController,
    metadata: StateFlow<DesktopMediaMetadata?>,
    transport: StateFlow<DesktopTransportState>,
    scope: CoroutineScope,
    dispatchToOwner: (() -> Unit) -> Unit,
    next: () -> Unit,
    previous: () -> Unit,
    setShuffle: (Boolean) -> Unit,
    setRepeat: (QueueRepeatMode) -> Unit,
    setVolume: (Float) -> Unit,
    openUri: (String) -> Unit,
    quit: () -> Unit,
    /** True while the user is a Listen Together guest: transport keeps play/pause but refuses queue and position. */
    private val guestLocked: () -> Boolean = { false },
    private val reportUnavailable: (String) -> Unit,
) : AutoCloseable {
    val windowActivity = DesktopWindowActivityObserver(window)
    val filePickers = DesktopFilePickers(window)
    private val controls = DesktopMediaControls({ controller.snapshot.value }, dispatchToOwner) {
        if (!guestLocked() || it.guestAllowed()) {
            controller.consumeDesktopTransport(it, next, previous, setShuffle, setRepeat, setVolume)
        }
    }
    private val darkTheme = MutableStateFlow<Boolean?>(null)

    /** The OS light/dark state where the platform reports it; null leaves the caller's own theme query in charge. */
    val systemDark: StateFlow<Boolean?> = darkTheme
    @Volatile private var integration: DesktopSystemIntegration? = null
    private val visibility: AutoCloseable?
    private val workspace: AutoCloseable?
    private val appearance: AutoCloseable?
    private val collector: Job
    init {
        require(EventQueue.isDispatchThread()) { "Attach desktop host services on the AWT event thread" }
        val linux = System.getProperty("os.name").startsWith("Linux")
        val raise = { EventQueue.invokeLater { window.isVisible = true; if (window is Frame) window.extendedState = Frame.NORMAL; window.toFront() }; Unit }
        val exit = { EventQueue.invokeLater(quit); Unit }
        val attach = {
            try {
                integration = if (linux) {
                    LinuxSystemIntegration(controls, raise, exit, { uri -> if (!guestLocked()) openUri(uri) })
                } else {
                    NativeSystemIntegration(window, controls, raise, exit, windowActivity::nativeActivity,
                        systemTheme = { dark -> darkTheme.value = dark })
                }
                if (!linux) darkTheme.value = runCatching {
                    when (val dark = NativeDesktopBridge.systemThemeDark()) {
                        0 -> false
                        1 -> true
                        else -> null
                    }
                }.getOrNull()
            } catch (error: Exception) {
                reportUnavailable("${DesktopStrings["integration_unavailable"]}: ${error.javaClass.simpleName}")
            } catch (error: LinkageError) {
                reportUnavailable("${DesktopStrings["integration_unavailable"]}: ${error.javaClass.simpleName}")
            }
        }
        // On macOS the native session binds NSApp's main window, which only exists once the AWT window is
        // ordered on screen. Services attach during first composition, before that order-in, so the attach
        // waits for windowOpened; an already-open window attaches at once.
        if (linux || window.isVisible) attach()
        else window.addWindowListener(object : WindowAdapter() {
            override fun windowOpened(event: WindowEvent) {
                window.removeWindowListener(this)
                attach()
            }
        })
        visibility = if (linux) try {
            LinuxWindowVisibility(window, windowActivity::nativeActivity)
        } catch (error: Exception) {
            reportUnavailable("X11 visibility: ${error.javaClass.simpleName}"); null
        } catch (error: LinkageError) {
            reportUnavailable("X11 visibility: ${error.javaClass.simpleName}"); null
        } else null
        workspace = if (linux && HyprlandWorkspaceVisibility.supported()) try {
            HyprlandWorkspaceVisibility((window as? Frame)?.title, windowActivity::nativeWorkspaceHidden)
        } catch (error: Exception) {
            reportUnavailable("Hyprland visibility: ${error.javaClass.simpleName}"); null
        } catch (error: LinkageError) {
            reportUnavailable("Hyprland visibility: ${error.javaClass.simpleName}"); null
        } else null
        appearance = if (linux) try {
            LinuxAppearanceTheme(onDark = { dark -> darkTheme.value = dark })
        } catch (error: Exception) {
            reportUnavailable("Desktop appearance: ${error.javaClass.simpleName}"); null
        } catch (error: LinkageError) {
            reportUnavailable("Desktop appearance: ${error.javaClass.simpleName}"); null
        } else null
        collector = scope.launch {
            combine(controller.snapshot, metadata, transport, ::Triple).collect { (snapshot, display, state) ->
                try { integration?.update(snapshot, display, state) }
                catch (error: Exception) { reportUnavailable("${DesktopStrings["integration_unavailable"]}: ${error.javaClass.simpleName}") }
            }
        }
    }
    /** False tells the caller to use the normal close/exit path, never leave an unreachable process. */
    fun hideOnClose(preferTray: Boolean): Boolean {
        require(EventQueue.isDispatchThread())
        if (!shouldCloseToTray(preferTray, integration?.trayAvailable == true)) return false
        window.isVisible = false
        return true
    }
    override fun close() {
        collector.cancel()
        visibility?.close()
        workspace?.close()
        appearance?.close()
        windowActivity.close()
        filePickers.close()
        integration?.close()
    }
}

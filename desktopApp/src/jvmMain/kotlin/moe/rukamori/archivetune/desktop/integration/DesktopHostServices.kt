/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration

import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.linux.LinuxSystemIntegration
import moe.rukamori.archivetune.desktop.integration.linux.LinuxWindowVisibility
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController

/** The real host binding: borrows (never creates/closes) the app's one player/queue owner. */
class DesktopHostServices(
    private val window: Window,
    controller: MusicPlayerController,
    metadata: StateFlow<DesktopMediaMetadata?>,
    scope: CoroutineScope,
    dispatchToOwner: (() -> Unit) -> Unit,
    next: () -> Unit,
    previous: () -> Unit,
    openUri: (String) -> Unit,
    quit: () -> Unit,
    private val reportUnavailable: (String) -> Unit,
) : AutoCloseable {
    val windowActivity = DesktopWindowActivityObserver(window)
    val filePickers = DesktopFilePickers(window)
    private val controls = DesktopMediaControls({ controller.snapshot.value }, dispatchToOwner) {
        controller.consumeDesktopTransport(it, next, previous)
    }
    private val integration: DesktopSystemIntegration?
    private val visibility: AutoCloseable?
    private val collector: Job
    init {
        require(EventQueue.isDispatchThread()) { "Attach desktop host services on the AWT event thread" }
        val linux = System.getProperty("os.name").startsWith("Linux")
        val raise = { EventQueue.invokeLater { window.isVisible = true; if (window is Frame) window.extendedState = Frame.NORMAL; window.toFront() }; Unit }
        val exit = { EventQueue.invokeLater(quit); Unit }
        integration = try {
            if (linux) LinuxSystemIntegration(controls, raise, exit, openUri)
            else NativeSystemIntegration(window, controls, raise, exit, windowActivity::nativeActivity)
        } catch (error: Exception) {
            reportUnavailable("${DesktopStrings["integration_unavailable"]}: ${error.javaClass.simpleName}")
            null
        } catch (error: LinkageError) {
            reportUnavailable("${DesktopStrings["integration_unavailable"]}: ${error.javaClass.simpleName}")
            null
        }
        visibility = if (linux) try {
            LinuxWindowVisibility(window, windowActivity::nativeActivity)
        } catch (error: Exception) {
            reportUnavailable("X11 visibility: ${error.javaClass.simpleName}"); null
        } catch (error: LinkageError) {
            reportUnavailable("X11 visibility: ${error.javaClass.simpleName}"); null
        } else null
        collector = scope.launch {
            combine(controller.snapshot, metadata) { snapshot, display -> snapshot to display }.collect { (snapshot, display) ->
                try { integration?.update(snapshot, display) }
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
        windowActivity.close()
        filePickers.close()
        integration?.close()
    }
}

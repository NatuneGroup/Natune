package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import moe.rukamori.archivetune.desktop.integration.DesktopLaunchIntake
import moe.rukamori.archivetune.desktop.integration.DesktopSingleInstance
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.desktop.integration.DesktopProtocolRegistration
import java.nio.file.Path

fun main(args: Array<String>) {
    // A restart after a staged restore starts this process while the old one is still leaving the instance lock.
    DesktopRelaunch.awaitPrevious(args)
    val uri = args.firstOrNull { Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:.*").matches(it) }
    // A stored relocation pointer wins over the default so a settings-committed move survives restart.
    val instanceDirectory = DesktopSettingsServices.currentInstanceDirectory()
    val instance = DesktopSingleInstance.acquire(instanceDirectory, uri, DesktopLaunchIntake::receive) ?: return
    // Installed once this process owns the instance directory, so a second launch never rotates the log files or
    // overwrites the stored report.
    DesktopDiagnosticLog.install(instanceDirectory)
    DesktopCrashCapture.install(instanceDirectory)
    // Order is load-bearing: the window-class override builds the AWT toolkit, and the renderer
    // properties must exist before application { } composes the first window.
    DesktopRuntime.forceLinuxWindowClass()
    DesktopRuntime.prepare()
    DesktopLaunchIntake.registerAppleEvents()
    if (System.getProperty("os.name").startsWith("Windows")) {
        try {
            if (!DesktopProtocolRegistration.registerPackagedWindows()) System.err.println("URI registration unavailable outside packaged launcher")
        } catch (error: LinkageError) { System.err.println("URI registration: ${error.javaClass.simpleName}") }
    }
    // The session graph opens only after this process holds the instance lock, so a second launch never touches the data.
    // The loader starts that open from the window's composition, so the window appears before the open finishes.
    val sessions = DesktopSessionLoader(instanceDirectory)
    DesktopMemoryHygiene.capRendererCaches()
    // A window close hides to the tray only when the mounted window allows it; any other close takes the explicit exit.
    val closeRequests = DesktopCloseRequests()
    val savedWindow = DesktopWindowBounds.load(instanceDirectory)
    var windowState: WindowState? = null
    try { application {
        val state = rememberWindowState(
            placement = savedWindow.placement,
            position = savedWindow.position,
            size = savedWindow.size,
        )
        windowState = state
        Window(
            onCloseRequest = { closeRequests.request { exitApplication() } },
            title = DesktopStrings["app_name"],
            state = state,
        ) {
            val density = LocalDensity.current.density
            LaunchedEffect(window, density) { DesktopWindowBounds.applyMinimumSize(window, density) }
            DisposableEffect(window) {
                DesktopLaunchIntake.attachWindow(window)
                onDispose { DesktopLaunchIntake.detachWindow(window) }
            }
            DesktopSessionWindowRoot(window, sessions, closeRequests) { exitApplication() }
        }
    } } finally {
        windowState?.let { DesktopWindowBounds.save(instanceDirectory, it) }
        // Window services leave with the window composition; the graph closes after the window has stopped asking for work.
        // The wait is bounded, so an open still in flight cannot hold the process at exit.
        sessions.close()
        instance.close()
    }
}

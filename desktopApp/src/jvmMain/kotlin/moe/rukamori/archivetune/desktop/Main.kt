/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import moe.rukamori.archivetune.desktop.integration.DesktopLaunchIntake
import moe.rukamori.archivetune.desktop.integration.DesktopSingleInstance
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.desktop.integration.DesktopProtocolRegistration
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val uri = args.firstOrNull { Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:.*").matches(it) }
    val instanceDirectory = System.getProperty("archivetune.instance.directory")?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".archivetune", "desktop")
    val instance = DesktopSingleInstance.acquire(instanceDirectory, uri, DesktopLaunchIntake::receive) ?: return
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
    val graph = openDesktopSession(instanceDirectory)
    // A window close hides to the tray only when the mounted window allows it; any other close takes the explicit exit.
    val closeRequests = DesktopCloseRequests()
    try { application {
        Window(
            onCloseRequest = { closeRequests.request { exitApplication() } },
            title = DesktopStrings["app_name"],
            state = rememberWindowState(size = DpSize(1024.dp, 720.dp)),
        ) {
            DisposableEffect(window) {
                DesktopLaunchIntake.attachWindow(window)
                onDispose { DesktopLaunchIntake.detachWindow(window) }
            }
            val session = graph
            if (session != null) {
                DesktopMountedRoot(window, session, closeRequests) { exitApplication() }
            } else {
                DesktopUnavailableRoot()
            }
        }
    } } finally {
        // Window services leave with the window composition; the graph closes after the window has stopped asking for work.
        graph?.let { runBlocking { it.close() } }
        instance.close()
    }
}

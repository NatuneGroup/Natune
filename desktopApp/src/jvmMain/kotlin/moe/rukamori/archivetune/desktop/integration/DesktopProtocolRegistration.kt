package moe.rukamori.archivetune.desktop.integration

import java.nio.file.Files
import java.nio.file.Path

object DesktopProtocolRegistration {
    /** Only a packaged Windows launcher has a stable URI-handler path. macOS uses its Info.plist. */
    fun registerPackagedWindows(): Boolean {
        if (!System.getProperty("os.name").startsWith("Windows")) return false
        val launcher = System.getProperty("jpackage.app-path") ?: return false
        val path = Path.of(launcher)
        require(path.isAbsolute && Files.isRegularFile(path) && path.fileName.toString().endsWith(".exe", ignoreCase = true))
        NativeDesktopBridge.ensureLoaded()
        return NativeDesktopBridge.registerUriScheme(path.toString())
    }
}

package moe.rukamori.archivetune.shared.ui.shortcuts

/** The JVM desktop host runs only on macOS or on Windows/Linux, so the OS name settles the primary modifier. */
actual val currentShortcutPlatform: ShortcutPlatform =
    if (System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) {
        ShortcutPlatform.MAC
    } else {
        ShortcutPlatform.WINDOWS_LINUX
    }

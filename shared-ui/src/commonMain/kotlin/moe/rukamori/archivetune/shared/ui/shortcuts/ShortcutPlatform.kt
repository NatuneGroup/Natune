package moe.rukamori.archivetune.shared.ui.shortcuts

/**
 * Where the shortcut modifier lands on this keyboard: Cmd on Apple hardware (macOS desktop, iPad and iPhone hardware
 * keyboards), Ctrl on Windows, Linux and Android. Spotify draws the same line between its Mac and Windows bindings.
 */
enum class ShortcutPlatform {
    MAC,
    WINDOWS_LINUX,
}

/** Resolved from the running platform, so a registry binding never has to ask which OS it is on. */
expect val currentShortcutPlatform: ShortcutPlatform

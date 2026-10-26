/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings

import org.jetbrains.compose.resources.StringResource

/**
 * Short outcomes that a shared settings page hands to the host to show as a toast or snackbar. Shared code draws no
 * platform toast of its own, and a notice is emitted only after the matching state change has committed, so the host
 * never announces a success that did not persist.
 */
sealed interface SettingsNotice {
    /**
     * A page message from the shared text resources. [count], when present, is the single format argument of the
     * resource, for example "Removed %1$d". The host resolves it in its own locale.
     */
    data class Message(val text: StringResource, val count: Int? = null) : SettingsNotice

    /** The host rejected the entered ARL. The dialog stays open and shows the same message inline. */
    data object DeezerArlRejected : SettingsNotice

    /** A verified ARL was committed. The host formats its own signed-in text from [accountName]. */
    data class DeezerSignedIn(val accountName: String) : SettingsNotice

    /** The Deezer session was cleared, after the host dropped its in-memory ARL. */
    data object DeezerSignedOut : SettingsNotice

    /** Amazon authorization was requested while no instance is configured. Nothing is launched. */
    data object AmazonInstanceRequired : SettingsNotice
}

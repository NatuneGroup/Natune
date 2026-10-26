@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.ComposeUIViewController
import moe.rukamori.archivetune.shared.domain.platform.IosCapabilities
import platform.UIKit.UIViewController

/**
 * The Xcode host renders the active shared root; it never imports the inactive :shared module. [root] is the mounted
 * app, and [overlay] draws above it, for the host's dialogs.
 */
fun MainViewController(
    root: @Composable () -> Unit = { ArchiveTuneRoot() },
    overlay: @Composable () -> Unit = {},
): UIViewController = ComposeUIViewController {
    root()
    overlay()
}

/** Available to Swift diagnostics without making Linux metadata a runtime capability probe. */
fun iosVerificationStatus(): String = IosCapabilities.verification

fun iosAdapterStatuses(): Map<String, String> =
    IosCapabilities.adapters.mapValues { "${IosCapabilities.verification}:${it.value}" }

fun iosUnavailableCapabilities(): List<String> = IosCapabilities.unavailable.sorted()

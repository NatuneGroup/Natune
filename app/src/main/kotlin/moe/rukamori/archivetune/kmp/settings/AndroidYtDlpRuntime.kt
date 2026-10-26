/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.kmp.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.ui.settings.YtDlpRuntimeStatus
import moe.rukamori.archivetune.ytdlp.CompactYtDlp

suspend fun readAndroidYtDlpRuntime(context: Context): YtDlpRuntimeStatus = withContext(Dispatchers.IO) {
    YtDlpRuntimeStatus(
        pythonAvailable = CompactYtDlp.resolvePythonExecutable(context)?.canExecute() == true,
        scriptAvailable = CompactYtDlp.resolveYtDlpBinary(context)?.isFile == true,
    )
}

package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.Composable
import moe.rukamori.archivetune.shared.ui.SafeAreaPadding
import moe.rukamori.archivetune.shared.ui.settings.RestoreRecoveryScreen

@Composable
internal fun IosRestoreRecoveryScreen(graph: IosSessionGraph, safeArea: SafeAreaPadding) {
    RestoreRecoveryScreen(graph.backup.previousLibraryAvailable, { previous -> graph.backup.resolveRestoreRecovery(restorePrevious = previous) },
        safeArea, restart = { IosBackupRestartDialog(graph) })
}

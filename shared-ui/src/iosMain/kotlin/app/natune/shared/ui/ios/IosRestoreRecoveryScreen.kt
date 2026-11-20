package app.natune.shared.ui.ios

import androidx.compose.runtime.Composable
import app.natune.shared.ui.SafeAreaPadding
import app.natune.shared.ui.settings.RestoreRecoveryScreen

@Composable
internal fun IosRestoreRecoveryScreen(graph: IosSessionGraph, safeArea: SafeAreaPadding) {
    RestoreRecoveryScreen(graph.backup.previousLibraryAvailable, { previous -> graph.backup.resolveRestoreRecovery(restorePrevious = previous) },
        safeArea, restart = { IosBackupRestartDialog(graph) })
}

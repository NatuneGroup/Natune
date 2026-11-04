package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.ui.SafeAreaPadding
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_restore_blocked_empty
import moe.rukamori.archivetune.shared.ui.resources.ios_restore_blocked_message
import moe.rukamori.archivetune.shared.ui.resources.ios_restore_blocked_previous
import moe.rukamori.archivetune.shared.ui.resources.ios_restore_blocked_title
import moe.rukamori.archivetune.shared.ui.safeAreaPadding
import moe.rukamori.archivetune.shared.ui.theme.ArchiveTuneTheme
import org.jetbrains.compose.resources.stringResource

/**
 * The only surface while a blocked restore holds the session: the graph runs on a throwaway database, so the
 * normal navigation and every ordinary write stay unmounted. Both resolutions quarantine the displaced files —
 * nothing is deleted — and the restart dialog then carries the session out, since iOS cannot relaunch itself.
 */
@Composable
internal fun IosRestoreRecoveryScreen(graph: IosSessionGraph, safeArea: SafeAreaPadding) {
    val scope = rememberCoroutineScope()
    ArchiveTuneTheme {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeAreaPadding(safeArea),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(Res.string.ios_restore_blocked_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(stringResource(Res.string.ios_restore_blocked_message), textAlign = TextAlign.Center)
                if (graph.backup.previousLibraryAvailable) {
                    TextButton(
                        onClick = { scope.launch { graph.backup.resolveRestoreRecovery(restorePrevious = true) } },
                    ) {
                        Text(stringResource(Res.string.ios_restore_blocked_previous))
                    }
                }
                TextButton(
                    onClick = { scope.launch { graph.backup.resolveRestoreRecovery(restorePrevious = false) } },
                ) {
                    Text(stringResource(Res.string.ios_restore_blocked_empty))
                }
            }
        }
        IosBackupRestartDialog(graph)
    }
}

package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The restart a staged restore needs. iOS has no relaunch mechanism, so Quit now ends the process and the next launch
 * applies the restore; Later keeps the current library and settings until the next start.
 */
@Composable
fun BackupRestartDialog(requested: Boolean, onDismiss: () -> Unit, quit: () -> Unit) {
    if (!requested) return
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(stringResource(Res.string.ios_backup_restart_title)) },
        text = { Text(stringResource(Res.string.ios_backup_restart_message)) },
        confirmButton = {
            TextButton(onClick = { quit() }) {
                Text(stringResource(Res.string.ios_backup_restart_quit))
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text(stringResource(Res.string.ios_backup_restart_later))
            }
        },
    )
}

/**
 * The open-time report for a staged restore that failed: the previous library and settings are already back in
 * place, the pending set rests under the failed-staging name, and this dialog holds the notice until answered.
 */
@Composable
fun BackupRestoreFailedDialog(failed: Boolean, onDismiss: () -> Unit) {
    if (!failed) return
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(stringResource(Res.string.ios_backup_restore_failed_title)) },
        text = { Text(stringResource(Res.string.ios_backup_restore_failed_message)) },
        confirmButton = {
            TextButton(onClick = { onDismiss() }) {
                Text(stringResource(Res.string.ios_backup_restore_failed_dismiss))
            }
        },
    )
}

/**
 * The recovery surface for a restore that left the library unresolvable: the session runs on a throwaway
 * database, so this dialog replaces the normal state until the user picks a resolution. Both actions move the
 * displaced files into a dated quarantine — nothing is deleted — and the restart dialog then carries the
 * session out.
 */
@Composable
fun RestoreBlockedDialog(blocked: Boolean, previousLibraryAvailable: Boolean, resolve: suspend (Boolean) -> Unit) {
    if (!blocked) return
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { /* a blocked library resolves only by choosing */ },
        title = { Text(stringResource(Res.string.ios_restore_blocked_title)) },
        text = { Text(stringResource(Res.string.ios_restore_blocked_message)) },
        confirmButton = {
            if (previousLibraryAvailable) {
                TextButton(
                    onClick = { scope.launch { resolve(true) } },
                ) {
                    Text(stringResource(Res.string.ios_restore_blocked_previous))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { scope.launch { resolve(false) } },
            ) {
                Text(stringResource(Res.string.ios_restore_blocked_empty))
            }
        },
    )
}

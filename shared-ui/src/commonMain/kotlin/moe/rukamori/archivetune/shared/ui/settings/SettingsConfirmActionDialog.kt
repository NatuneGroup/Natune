/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingsConfirmActionDialog(
    title: StringResource,
    onConfirm: suspend () -> Unit,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(title)) },
        text = { if (busy) CircularProgressIndicator() else Text(stringResource(Res.string.m6_confirm_stored_action)) },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try { onConfirm(); onDismiss() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { onFailure() }
                finally { busy = false }
            }
        }) { Text(stringResource(Res.string.m6_confirm)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.m6_cancel)) } },
    )
}

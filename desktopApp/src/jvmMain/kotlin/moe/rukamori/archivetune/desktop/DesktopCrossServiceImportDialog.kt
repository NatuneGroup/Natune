package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings

@Composable
internal fun DesktopCrossServiceImportDialog(
    importer: DesktopCrossServiceImport,
    openPlaylist: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(DesktopStrings["cross_service_import_hint"]) }
    var done by remember { mutableStateOf<DesktopCrossServiceImport.ImportResult?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(DesktopStrings["cross_service_import_title"]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(DesktopStrings["cross_service_import_url_label"]) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(status, style = MaterialTheme.typography.bodyMedium)
                if (busy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator()
                        LinearProgressIndicator(modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    status = DesktopStrings["cross_service_import_fetching"]
                    scope.launch {
                        try {
                            importer.import(url.trim()) { progress ->
                                status = when (progress) {
                                    is DesktopCrossServiceImport.ImportProgress.Fetching -> DesktopStrings["cross_service_import_fetching"]
                                    is DesktopCrossServiceImport.ImportProgress.Resolving ->
                                        DesktopStrings["cross_service_import_resolving"].format(progress.processed, progress.total)
                                    is DesktopCrossServiceImport.ImportProgress.Done ->
                                        DesktopStrings["cross_service_import_done"].format(progress.result.imported, progress.result.total)
                                    is DesktopCrossServiceImport.ImportProgress.Failed -> progress.message
                                }
                                if (progress is DesktopCrossServiceImport.ImportProgress.Done) done = progress.result
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            status = failure.message ?: DesktopStrings["menu_action_failed"]
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(DesktopStrings["cross_service_import_start"]) }
        },
        dismissButton = {
            Row {
                done?.let { result ->
                    TextButton(onClick = { openPlaylist(result.playlistId); onDismiss() }) {
                        Text(DesktopStrings["cross_service_import_open"])
                    }
                }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(DesktopStrings["close"]) }
            }
        },
    )
}

package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.setValue

data class YtDlpRuntimeStatus(val pythonAvailable: Boolean, val scriptAvailable: Boolean)

/** External plugin diagnostics, not the unrelated external-download-app settings or bundled Python. */
@Composable
fun YtDlpRuntimeSettings(readRuntime: suspend () -> YtDlpRuntimeStatus) {
    var status by remember { mutableStateOf<YtDlpRuntimeStatus?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        if (loading) return
        loading = true
        failed = false
        try { status = readRuntime() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true; status = null }
        finally { loading = false }
    }
    LaunchedEffect(readRuntime) { refresh() }
    Column {
        val back = LocalSettingsBack.current
        if (back != null) SettingsBackHeader(back)
        Text(stringResource(Res.string.m6_ytdlp), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(Res.string.m6_ytdlp_description))
        status?.let {
            ListItem(
                headlineContent = { Text(stringResource(Res.string.m6_python_runtime)) },
                supportingContent = { Text(stringResource(if (it.pythonAvailable) Res.string.m6_available else Res.string.m6_unavailable)) },
            )
            ListItem(
                headlineContent = { Text(stringResource(Res.string.m6_ytdlp_script)) },
                supportingContent = { Text(stringResource(if (it.scriptAvailable) Res.string.m6_available else Res.string.m6_unavailable)) },
            )
        }
        if (failed) Text(stringResource(Res.string.m6_runtime_failed))
        Button(enabled = !loading, onClick = { scope.launch { refresh() } }) { Text(stringResource(Res.string.m6_refresh)) }
    }
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.shared.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import moe.rukamori.archivetune.shared.domain.diagnostics.CrashReport
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The actions of the crash screen. [report] opens the feedback review with the redacted report and is null while no
 * feedback service is configured; [delete] removes the stored report without sending it and is null when closing
 * already removes it. [notice] is the next-launch explanation of what Report crash does, shown under the error card.
 */
class CrashReportActions(
    val copyToClipboard: (String) -> Unit,
    val shareLogs: (String) -> Unit,
    val report: (() -> Unit)?,
    val delete: (() -> Unit)?,
    val close: () -> Unit,
    val notice: String? = null,
)

/**
 * The Android crash screen over the whole window: the error card with the first line and time, the device rows, the
 * selectable stack trace, Share Logs, copy all and close. A host without an in-process crash activity shows it at the
 * next launch, or at once when the process outlived the failure, so Restart becomes Report crash.
 */
@Composable
fun CrashReportDialog(report: CrashReport, appName: String, actions: CrashReportActions) {
    Dialog(
        onDismissRequest = actions.close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        CrashReportScreen(report, appName, actions)
    }
}

@Composable
fun CrashReportScreen(report: CrashReport, appName: String, actions: CrashReportActions, modifier: Modifier = Modifier) {
    val reportText = remember(report, appName) { report.reportText(appName) }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { actions.shareLogs(reportText) },
                    icon = { Icon(painterResource(Res.drawable.m6yp_icon_share), null) },
                    text = { Text(stringResource(Res.string.m11_crash_share_logs)) },
                )
            },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(Res.string.m11_crash_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        )
                    },
                    actions = {
                        IconButton(onClick = { actions.copyToClipboard(reportText) }) {
                            Icon(painterResource(Res.drawable.m6yp_icon_select_all), null)
                        }
                        IconButton(onClick = actions.close) { Icon(painterResource(Res.drawable.m4_icon_close), null) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(18.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                painterResource(Res.drawable.m11_icon_error),
                                null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                stringResource(Res.string.m11_crash_application_crashed),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        Text(
                            report.preview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.92f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (report.timestampText.isNotBlank()) {
                            Text(
                                report.timestampText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.82f),
                            )
                        }
                    }
                }
                actions.notice?.let { notice ->
                    Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CrashCard(stringResource(Res.string.m11_crash_device_info)) {
                    report.deviceInfo.forEachIndexed { index, (key, value) ->
                        if (index != 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            Text(
                                key,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(0.42f),
                            )
                            Text(
                                value,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(0.58f),
                            )
                        }
                    }
                }
                CrashCard(stringResource(Res.string.m11_crash_stack_trace)) {
                    SelectionContainer {
                        Text(
                            report.stackTrace,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    actions.report?.let { onReport ->
                        Button(
                            onClick = onReport,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) { Text(stringResource(Res.string.m11_crash_report)) }
                    }
                    Button(
                        onClick = actions.close,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(Res.string.m11_crash_close)) }
                }
                actions.delete?.let { delete ->
                    TextButton(onClick = delete, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(Res.string.m11_crash_delete))
                    }
                }
                // Room for the Share Logs button, so it never covers the last action.
                Spacer(Modifier.height(72.dp))
            }
        }
    }
}

@Composable
private fun CrashCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            content()
        }
    }
}

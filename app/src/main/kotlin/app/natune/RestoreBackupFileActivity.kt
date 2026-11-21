/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package app.natune

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import app.natune.ui.component.DefaultDialog
import app.natune.ui.menu.LoadingScreen
import app.natune.ui.theme.ArchiveTuneTheme
import app.natune.ui.screens.settings.AccountPasswordMode
import app.natune.ui.screens.settings.BackupOptionsDialog
import app.natune.viewmodels.BackupCategory
import app.natune.viewmodels.BackupRestoreViewModel

@AndroidEntryPoint
class RestoreBackupFileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            Toast.makeText(this, R.string.restore_file_not_found, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContent {
            ArchiveTuneTheme {
                RestoreBackupFileScreen(
                    uri = uri,
                    onNavigateBack = { finish() },
                )
            }
        }
    }
}

private sealed class BackupScreenState {
    data object Validating : BackupScreenState()

    data class Ready(
        val availableCategories: Set<BackupCategory>,
    ) : BackupScreenState()

    data class Error(
        val message: String,
    ) : BackupScreenState()

    data object Restoring : BackupScreenState()
}

@Composable
private fun RestoreBackupFileScreen(
    uri: Uri,
    onNavigateBack: () -> Unit,
    viewModel: BackupRestoreViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var screenState by remember { mutableStateOf<BackupScreenState>(BackupScreenState.Validating) }
    var showRestoreDialog by remember { mutableStateOf(false) }

    val backupRestoreProgress by viewModel.backupRestoreProgress.collectAsStateWithLifecycle()

    LaunchedEffect(uri) {
        val result = viewModel.validateBackup(context, uri)
        if (result.isValid) {
            screenState = BackupScreenState.Ready(availableCategories = result.availableCategories)
            showRestoreDialog = true
        } else {
            screenState = BackupScreenState.Error(result.errorMessage ?: context.getString(R.string.restore_corrupted))
        }
    }

    when (val state = screenState) {
        is BackupScreenState.Validating -> {
            DefaultDialog(
                onDismiss = onNavigateBack,
                title = { Text(stringResource(R.string.restore_in_progress)) },
                buttons = {
                    TextButton(onClick = onNavigateBack, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            ) {
                Text(stringResource(R.string.restore_step_verifying))
            }
        }

        is BackupScreenState.Error -> {
            DefaultDialog(
                onDismiss = onNavigateBack,
                icon = { Icon(painterResource(R.drawable.error), null) },
                title = { Text(stringResource(R.string.restore_failed)) },
                buttons = {
                    TextButton(onClick = onNavigateBack, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is BackupScreenState.Ready -> {
            if (showRestoreDialog) {
                BackupOptionsDialog(
                    title = stringResource(R.string.restore_options_title),
                    confirmLabel = stringResource(R.string.action_restore),
                    availableCategories = BackupCategory.entries.filter { it in state.availableCategories },
                    accountPassword = AccountPasswordMode.ENTER,
                    onConfirm = { categories, password ->
                        showRestoreDialog = false
                        screenState = BackupScreenState.Restoring
                        viewModel.restore(context, uri, categories, password)
                    },
                    onDismiss = onNavigateBack,
                )
            }
        }

        is BackupScreenState.Restoring -> {
            LoadingScreen(
                isVisible = true,
                value = backupRestoreProgress?.percent ?: 0,
                title = backupRestoreProgress?.title,
                stepText = backupRestoreProgress?.step,
                indeterminate = backupRestoreProgress?.indeterminate ?: true,
            )
        }
    }
}


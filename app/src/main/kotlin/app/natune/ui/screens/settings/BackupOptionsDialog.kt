package app.natune.ui.screens.settings

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.natune.R
import app.natune.ui.component.DefaultDialog
import app.natune.viewmodels.BackupCategory
import app.natune.viewmodels.BackupRestoreViewModel
import app.natune.viewmodels.BackupValidationResult

/** How the dialog asks for the password that protects a backup's sign-ins. */
internal enum class AccountPasswordMode { CREATE, ENTER }

/** `Natune_<time>.zip`, the name every manual backup is offered under. */
internal fun backupFileName(context: android.content.Context): String =
    "${context.getString(R.string.app_name)}_${java.time.LocalDateTime.now()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))}.zip"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BackupOptionsDialog(
    title: String,
    availableCategories: List<BackupCategory> = BackupCategory.entries,
    confirmLabel: String,
    onConfirm: (Set<BackupCategory>, String?) -> Unit,
    onDismiss: () -> Unit,
    accountPassword: AccountPasswordMode? = null,
) {
    // Sign-ins are only ever written encrypted, so a new backup starts with Accounts unticked; a restore offers
    // whatever the archive holds, as before.
    var selected by remember(availableCategories) {
        mutableStateOf(
            if (accountPassword == AccountPasswordMode.CREATE) availableCategories.toSet() - BackupCategory.ACCOUNT
            else availableCategories.toSet(),
        )
    }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val needsPassword = accountPassword != null && BackupCategory.ACCOUNT in selected
    // On restore the field is optional: older backups carry sign-ins without a password, and a protected one
    // refuses a wrong or empty password with its own message before anything changes.
    val passwordReady = !needsPassword || accountPassword == AccountPasswordMode.ENTER ||
        (password.isNotEmpty() && password == confirmation)

    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(title) },
        buttons = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(android.R.string.cancel))
            }
            TextButton(
                onClick = { onConfirm(selected, password.takeIf { needsPassword }) },
                shapes = ButtonDefaults.shapes(),
                enabled = selected.isNotEmpty() && passwordReady,
            ) {
                Text(confirmLabel)
            }
        },
    ) {
        Spacer(Modifier.height(8.dp))
        availableCategories.forEach { category ->
            val isChecked = category in selected
            val labelRes =
                when (category) {
                    BackupCategory.LIBRARY -> R.string.backup_category_library
                    BackupCategory.ACCOUNT -> R.string.backup_category_account
                    BackupCategory.SETTINGS -> R.string.backup_category_settings
                }
            val descRes =
                when (category) {
                    BackupCategory.LIBRARY -> R.string.backup_category_library_desc
                    BackupCategory.ACCOUNT -> R.string.backup_category_account_desc
                    BackupCategory.SETTINGS -> R.string.backup_category_settings_desc
                }
            val iconRes =
                when (category) {
                    BackupCategory.LIBRARY -> R.drawable.library_music
                    BackupCategory.ACCOUNT -> R.drawable.account
                    BackupCategory.SETTINGS -> R.drawable.settings
                }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = Color.Transparent,
                onClick = {
                    selected = if (isChecked) selected - category else selected + category
                },
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp)
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconBubble(
                        icon = painterResource(iconRes),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        size = 40.dp,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(descRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + category else selected - category
                        },
                    )
                }
            }
        }
        if (needsPassword) {
            AccountPasswordFields(
                mode = requireNotNull(accountPassword),
                password = password,
                onPasswordChange = { password = it },
                confirmation = confirmation,
                onConfirmationChange = { confirmation = it },
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun AccountPasswordFields(
    mode: AccountPasswordMode,
    password: String,
    onPasswordChange: (String) -> Unit,
    confirmation: String,
    onConfirmationChange: (String) -> Unit,
) {
    val hidden = androidx.compose.ui.text.input.PasswordVisualTransformation()
    val keyboard = androidx.compose.foundation.text.KeyboardOptions(
        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text(
            stringResource(
                if (mode == AccountPasswordMode.CREATE) R.string.backup_account_password_create_desc
                else R.string.backup_account_password_enter_desc,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.backup_account_password)) },
            singleLine = true,
            visualTransformation = hidden,
            keyboardOptions = keyboard,
            modifier = Modifier.fillMaxWidth(),
        )
        if (mode == AccountPasswordMode.CREATE) {
            OutlinedTextField(
                value = confirmation,
                onValueChange = onConfirmationChange,
                label = { Text(stringResource(R.string.backup_account_password_confirm)) },
                singleLine = true,
                visualTransformation = hidden,
                keyboardOptions = keyboard,
                isError = confirmation.isNotEmpty() && confirmation != password,
                supportingText = if (confirmation.isNotEmpty() && confirmation != password) {
                    { Text(stringResource(R.string.backup_account_password_mismatch)) }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Intent restores use the same inspection and category choices as file-picker restores. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BackupRestoreIntentDialog(uri: Uri, viewModel: BackupRestoreViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var validation by remember(uri) { mutableStateOf<BackupValidationResult?>(null) }
    LaunchedEffect(uri) { validation = viewModel.validateBackup(context, uri) }
    val result = validation
    if (result == null || !result.isValid) {
        DefaultDialog(
            onDismiss = onDismiss,
            title = { Text(stringResource(if (result == null) R.string.restore_in_progress else R.string.restore_failed)) },
            buttons = {
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(if (result == null) android.R.string.cancel else android.R.string.ok))
                }
            },
        ) {
            Text(result?.errorMessage ?: stringResource(R.string.restore_step_verifying))
        }
        return
    }
    BackupOptionsDialog(
        title = stringResource(R.string.restore_options_title),
        availableCategories = BackupCategory.entries.filter { it in result.availableCategories },
        confirmLabel = stringResource(R.string.action_restore),
        accountPassword = AccountPasswordMode.ENTER,
        onConfirm = { selected, password ->
            onDismiss()
            viewModel.restore(context, uri, selected, password)
        },
        onDismiss = onDismiss,
    )
}

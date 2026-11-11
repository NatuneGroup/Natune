package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.viewmodels.BackupCategory

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BackupOptionsDialog(
    title: String,
    availableCategories: List<BackupCategory> = BackupCategory.entries,
    confirmLabel: String,
    onConfirm: (Set<BackupCategory>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(availableCategories) { mutableStateOf(availableCategories.toSet()) }

    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(title) },
        buttons = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(android.R.string.cancel))
            }
            TextButton(
                onClick = { onConfirm(selected) },
                shapes = ButtonDefaults.shapes(),
                enabled = selected.isNotEmpty(),
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
        Spacer(Modifier.height(4.dp))
    }
}

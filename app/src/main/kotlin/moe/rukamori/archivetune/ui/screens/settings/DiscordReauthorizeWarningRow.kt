@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import moe.rukamori.archivetune.R

internal fun DiscordReauthorizeWarningRow(
    onReauthorize: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = stringResource(R.string.discord_reauthorize_required_title),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            supportingContent = {
                Text(stringResource(R.string.discord_reauthorize_required_description))
            },
            leadingContent = {
                Icon(
                    painter = painterResource(R.drawable.error),
                    contentDescription = null,
                )
            },
            trailingContent = {
                TextButton(
                    onClick = onReauthorize,
                    enabled = enabled,
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.discord_reauthorize_action))
                }
            },
            colors =
                ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = MaterialTheme.colorScheme.onErrorContainer,
                    supportingColor = MaterialTheme.colorScheme.onErrorContainer,
                    leadingIconColor = MaterialTheme.colorScheme.onErrorContainer,
                    trailingIconColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
        )
    }
}


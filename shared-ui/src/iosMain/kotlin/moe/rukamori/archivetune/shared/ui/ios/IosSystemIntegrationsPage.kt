package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun IosSystemIntegrationsPage() {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text(stringResource(Res.string.ios_system_integrations), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(Res.string.ios_widgets_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
        Text(stringResource(Res.string.ios_widgets_description))
        Text(stringResource(Res.string.ios_carplay_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
        Text(stringResource(Res.string.ios_carplay_description))
    }
}

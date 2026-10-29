package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.SwitchPreference

@Composable
internal fun QueuePersistencePreference(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchPreference(
        title = { Text(stringResource(R.string.persistent_queue)) },
        description = stringResource(R.string.persistent_queue_desc),
        icon = { Icon(painterResource(R.drawable.queue_music), null) },
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
internal fun InfiniteQueuePreference(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier) {
    Column(modifier) {
        SwitchPreference(
            title = { Text(stringResource(R.string.queue_infinite)) },
            description = stringResource(R.string.queue_infinite_description),
            icon = { Icon(painterResource(R.drawable.all_inclusive), null) },
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

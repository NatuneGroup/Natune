package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.PreventDuplicateTracksInQueueKey
import moe.rukamori.archivetune.constants.QueueAddAfterManualKey
import moe.rukamori.archivetune.constants.QueueLockedKey
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.utils.PreferenceSnapshot

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

/**
 * The hand-queued-order and queue-safety rows of the queue group. Each row reads its own snapshot
 * state inside its item, so the settings screen stays a list of rows instead of carrying a state
 * pair for every switch — which is what pushes PlayerSettings over the file budget.
 */
internal fun LazyListScope.queuePolicyRows(prefs: PreferenceSnapshot, positions: PreferencePositions) {
    item {
        val (queueAddAfterManual, onQueueAddAfterManualChange) =
            prefs.state(QueueAddAfterManualKey, defaultValue = true)
        Column(modifier = positions.modifierFor("queue_after_manual")) {
            SwitchPreference(
                title = { Text(stringResource(R.string.queue_add_after_manual)) },
                description = stringResource(R.string.queue_add_after_manual_desc),
                icon = { Icon(painterResource(R.drawable.queue_music), null) },
                checked = queueAddAfterManual,
                onCheckedChange = onQueueAddAfterManualChange,
            )
        }
    }

    item {
        val (preventDuplicates, onPreventDuplicatesChange) =
            prefs.state(PreventDuplicateTracksInQueueKey, defaultValue = false)
        PreventDuplicatesInQueuePreference(
            preventDuplicates,
            onPreventDuplicatesChange,
            positions.modifierFor("prevent_duplicates_in_queue"),
        )
    }

    item {
        val (queueLocked, onQueueLockedChange) =
            prefs.state(QueueLockedKey, defaultValue = false)
        QueueLockedPreference(queueLocked, onQueueLockedChange, positions.modifierFor("queue_locked"))
    }
}

@Composable
internal fun PreventDuplicatesInQueuePreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        SwitchPreference(
            title = { Text(stringResource(R.string.queue_prevent_duplicates)) },
            description = stringResource(R.string.queue_prevent_duplicates_desc),
            icon = { Icon(painterResource(R.drawable.block), null) },
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
internal fun QueueLockedPreference(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier) {
    Column(modifier) {
        SwitchPreference(
            title = { Text(stringResource(R.string.queue_locked)) },
            description = stringResource(R.string.queue_locked_desc),
            icon = { Icon(painterResource(R.drawable.lock), null) },
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

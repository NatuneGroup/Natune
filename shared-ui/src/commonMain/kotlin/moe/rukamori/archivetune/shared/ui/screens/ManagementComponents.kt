package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.navigation.LocalBackToMain
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

internal val defaultCollectionTextOrder = Comparator<String> { a, b -> a.compareTo(b, ignoreCase = true) }

/** UI key only: opaque providers/IDs may themselves contain separators. Never persist this encoding. */
internal fun ContentRef.collectionUiKey(): String = "${provider.length}:$provider:${kind.name}:$id"

@Composable
internal fun ManagementHeader(title: String, back: () -> Unit) {
    val backToMain = LocalBackToMain.current
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Long-press returns to the main tab root, as the baseline back buttons do. Same text action as before.
        Box(
            Modifier.clip(RoundedCornerShape(20.dp))
                .combinedClickable(role = Role.Button, onClick = back, onLongClick = backToMain)
                .defaultMinSize(minHeight = 40.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(Res.string.m4_back), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun LoadFeedback(loading: Boolean, failed: Boolean, empty: Boolean, retry: () -> Unit) {
    when {
        loading -> Text(stringResource(Res.string.m4_loading), Modifier.padding(16.dp))
        failed -> Column(Modifier.padding(16.dp)) {
            Text(stringResource(Res.string.m4_load_failed))
            TextButton(retry) { Text(stringResource(Res.string.m4_retry)) }
        }
        empty -> Text(stringResource(Res.string.m4_empty), Modifier.padding(16.dp))
    }
}

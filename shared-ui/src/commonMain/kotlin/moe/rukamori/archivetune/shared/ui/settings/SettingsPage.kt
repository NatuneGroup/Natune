package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.glass.motionAllowed
import moe.rukamori.archivetune.shared.ui.marquee.SettingsMarqueeText
import moe.rukamori.archivetune.shared.ui.resources.*
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun SettingsPage(
    rows: List<SettingsRow>,
    preferences: PreferenceSnapshot,
    controller: SettingsController,
    scrollTo: String? = null,
    resumed: Boolean = true,
    motionEnabled: Boolean = motionAllowed(),
    onFailure: () -> Unit,
    modifier: Modifier = Modifier,
    onCommitted: suspend (SettingsChange) -> Unit = {},
    /** Host-owned sections (diagnostics, audits) that must share the page scroll and anchors. */
    extraItems: LazyListScope.() -> Unit = {},
    /** Host-owned sections above the rows (for example a sign-in card) that share the page scroll. */
    leadingItems: LazyListScope.() -> Unit = {},
    /** How many items [leadingItems] emits, so a `scrollTo` anchor still lands on its own row. */
    leadingItemCount: Int = 0,
) {
    val scope = rememberCoroutineScope()
    val latestCommitted by rememberUpdatedState(onCommitted)
    val listState = rememberLazyListState()
    var editing by remember { mutableStateOf<String?>(null) }
    fun commit(change: PreferenceEditor.() -> Unit) {
        scope.launch {
            try { latestCommitted(controller.edit(change)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { onFailure() }
        }
    }
    LaunchedEffect(scrollTo, rows.map { it.anchor }) {
        val index = rows.indexOfFirst { it.anchor == scrollTo }
        if (index >= 0) listState.scrollToItem(leadingItemCount + index)
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        leadingItems()
        itemsIndexed(rows, key = { _, row -> row.anchor }) { index, row ->
            if (row is SettingsRow.Custom) {
                row.content()
                return@itemsIndexed
            }
            if (row is SettingsRow.Header) {
                Text(
                    stringResource(row.title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("setting:${row.anchor}")
                        .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
                )
                return@itemsIndexed
            }
            val visible by remember(index) { derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.index == index } } }
            val enabled = row.enabled
            ListItem(
                headlineContent = { Text(stringResource((row as? SettingsRow.Text)?.titleFor?.invoke(preferences) ?: row.title)) },
                supportingContent = {
                    // A switch with a state-dependent line (on or paused) shows that line instead of the static one.
                    val description = (row as? SettingsRow.Toggle)?.let { toggle ->
                        toggle.descriptionFor?.invoke(toggle.read(preferences))
                    } ?: row.description
                    if (row is SettingsRow.Status) {
                        Text(row.value, maxLines = if (row.fullText) Int.MAX_VALUE else 2)
                    } else if (row is SettingsRow.Choice) {
                        val stored = row.read(preferences)
                        val option = row.options.firstOrNull { it.value == stored }
                            ?: row.options.firstOrNull { it.value == row.default } ?: row.options.first()
                        Column {
                            Text(stringResource(option.label))
                            description?.let {
                                SettingsMarqueeText(
                                    text = stringResource(it),
                                    active = visible && resumed && motionEnabled,
                                    phaseKey = row.anchor,
                                )
                            }
                        }
                    } else if (description != null) {
                        SettingsMarqueeText(
                            text = stringResource(description),
                            active = visible && resumed && motionEnabled,
                            phaseKey = row.anchor,
                        )
                    } else if (row is SettingsRow.IntChoice) {
                        val stored = preferences[row.key, row.default]
                        val option = row.options.firstOrNull { it.value == stored }
                            ?: row.options.firstOrNull { it.value == row.default } ?: row.options.first()
                        Text(stringResource(option.label))
                    } else if (row is SettingsRow.Number) {
                        Text(numberValueText(row.read(preferences), row.unit))
                    } else if (row is SettingsRow.Decimal) {
                        Text(decimalValueText(preferences[row.key, row.default].let { if (it.isFinite()) it else row.default }, row.unit))
                    }
                },
                trailingContent = {
                    if (row is SettingsRow.Toggle) Switch(
                        checked = row.read(preferences),
                        onCheckedChange = { checked -> commit { row.write(this, checked) } },
                        enabled = enabled,
                        modifier = Modifier.testTag("toggle:${row.anchor}"),
                    )
                },
                modifier = Modifier.testTag("setting:${row.anchor}")
                    .clickable(enabled = enabled && (row !is SettingsRow.Status || row.run != null)) {
                    when (row) {
                        is SettingsRow.Toggle -> commit { row.write(this, !row.read(snapshot())) }
                        is SettingsRow.Action -> row.run()
                        is SettingsRow.Status -> row.run?.invoke()
                        else -> editing = row.anchor
                    }
                },
            )
        }
        extraItems()
    }
    // Re-resolve the active row so a style/capability change cannot leave a stale enabled dialog.
    val row = rows.firstOrNull { it.anchor == editing && it.enabled }
    LaunchedEffect(editing, row != null) { if (editing != null && row == null) editing = null }
    if (row is SettingsRow.Choice) {
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(row.title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                row.options.forEach { option ->
                    TextButton(enabled = option.enabled,
                        onClick = { commit { row.write(this, option.value) }; editing = null }) {
                        Column {
                            Text(stringResource(option.label))
                            option.description?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(Res.string.m6_cancel)) } },
        )
    }
    if (row is SettingsRow.IntChoice) {
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(row.title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                row.options.forEach { option ->
                    TextButton(onClick = { commit { row.write(this, option.value) }; editing = null }) {
                        Text(stringResource(option.label))
                    }
                }
            } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(Res.string.m6_cancel)) } },
        )
    }
    if (row is SettingsRow.Text || row is SettingsRow.Number) {
        // The surrounding condition admits only text and number rows; the else branches keep these expressions total.
        val stored = when (row) {
            is SettingsRow.Text -> preferences[row.key, row.default]
            is SettingsRow.Number -> row.read(preferences).toString()
            else -> ""
        }
        var draft by remember(row.anchor, stored) { mutableStateOf(stored) }
        val valid = when (row) {
            is SettingsRow.Text -> row.valid(draft)
            is SettingsRow.Number -> draft.toIntOrNull()?.let { it in row.range } == true
            else -> false
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(row.title)) },
            text = { OutlinedTextField(
                value = draft, onValueChange = { draft = it }, singleLine = row !is SettingsRow.Text || row.singleLine,
                visualTransformation = if (row is SettingsRow.Text && row.masked) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.testTag("settings-draft"),
            ) },
            confirmButton = { TextButton(enabled = valid, onClick = {
                when (row) {
                    is SettingsRow.Text -> commit { row.write(this, draft) }
                    is SettingsRow.Number -> commit { row.write(this, draft.toInt()) }
                    else -> Unit
                }
                editing = null
            }) { Text(stringResource(Res.string.m6_save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(Res.string.m6_cancel)) } },
        )
    }
    if (row is SettingsRow.Decimal) {
        val stored = preferences[row.key, row.default].let { if (it.isFinite()) it else row.default }
        var draft by remember(row.anchor, stored) { mutableFloatStateOf(stored.coerceIn(row.range)) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(row.title)) },
            text = { Column {
                Text(decimalValueText(draft, row.unit))
                Slider(
                    value = draft, onValueChange = { draft = row.normalize(it).coerceIn(row.range) }, valueRange = row.range, steps = row.steps,
                    onValueChangeFinished = { commit { this[row.key] = draft } },
                    modifier = Modifier.testTag("settings-slider"),
                )
            } },
            confirmButton = { TextButton(onClick = { editing = null }) { Text(stringResource(Res.string.m6_done)) } },
        )
    }
}

@Composable
private fun numberValueText(value: Int, unit: SettingsUnit): String = when (unit) {
    SettingsUnit.NONE -> value.toString()
    SettingsUnit.SECONDS -> stringResource(Res.string.m6_duration_seconds_short, value)
    SettingsUnit.PERCENT -> "$value%"
}

@Composable
private fun decimalValueText(value: Float, unit: SettingsUnit): String = when (unit) {
    SettingsUnit.NONE -> value.toString()
    SettingsUnit.SECONDS -> stringResource(Res.string.m6_duration_seconds_short, value.roundToInt())
    SettingsUnit.PERCENT -> "${(value * 100f).roundToInt()}%"
}

@Composable
fun SettingsRestartDialog(controller: SettingsController, busy: Boolean, restartHost: suspend () -> Unit) {
    val state by controller.restart.collectAsState()
    val scope = rememberCoroutineScope()
    if (state.visible) AlertDialog(
        onDismissRequest = { scope.launch { controller.later() } },
        title = { Text(stringResource(Res.string.m6_restart_title)) },
        text = { Text(stringResource(Res.string.m6_restart_message)) },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            scope.launch { controller.restartNow(busy, restartHost) }
        }) { Text(stringResource(Res.string.m6_restart_now)) } },
        dismissButton = { TextButton(onClick = { scope.launch { controller.later() } }) { Text(stringResource(Res.string.m6_later)) } },
    )
}

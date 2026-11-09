package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.desktop.LocalDesktopListScrollbar
import moe.rukamori.archivetune.shared.ui.glass.motionAllowed
import moe.rukamori.archivetune.shared.ui.marquee.SettingsMarqueeText
import moe.rukamori.archivetune.shared.ui.navigation.LocalDesktopLayout
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.theme.DesktopType
import org.jetbrains.compose.resources.stringResource

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
    /**
     * Host-owned sections above the rows (for example a sign-in card) that share the page scroll.
     */
    leadingItems: LazyListScope.() -> Unit = {},
    /** How many items [leadingItems] emits, so a `scrollTo` anchor still lands on its own row. */
    leadingItemCount: Int = 0,
    header: (@Composable () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val latestCommitted by rememberUpdatedState(onCommitted)
    val listState = rememberLazyListState()
    // A host without a consumer for a row omits it; the stored preference and its anchor remain
    // untouched.
    val reference = LocalPhoneSettingsReference.current
    val unavailableAnchors = LocalUnavailableSettingsAnchors.current
    val pageEntries = phoneSettingsEntries(rows.withoutUnsupportedRows(LocalUnsupportedSettingsAnchors.current), reference, unavailableAnchors)
    val pageRows = pageEntries.map { it.row }
    var editing by remember { mutableStateOf<String?>(null) }
    fun commit(change: PreferenceEditor.() -> Unit) {
        scope.launch {
            try {
                latestCommitted(controller.edit(change))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onFailure()
            }
        }
    }
    LaunchedEffect(scrollTo, pageRows.map { it.anchor }) {
        val index = pageEntries.indexOfFirst { entry -> entry.row.anchor == scrollTo || entry.reference?.anchor == scrollTo ||
            entry.reference?.aliases?.any { it == scrollTo } == true }
        if (index >= 0) listState.scrollToItem(leadingItemCount + index)
    }
    val back = LocalSettingsBack.current
    Column(modifier.fillMaxSize()) {
        // A host with no system back gesture supplies the pop, so one pinned control heads the
        // list.
        if (header != null) header() else if (back != null) SettingsBackHeader(back, reference?.title)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val scrollbar = LocalDesktopListScrollbar.current
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                contentPadding = if (reference != null) PaddingValues(bottom = 24.dp) else PaddingValues()) {
                leadingItems()
                itemsIndexed(pageRows, key = { _, row -> row.anchor }) { index, row ->
                    val entry = pageEntries[index]
                    val unavailable = entry.unavailable(unavailableAnchors)
                    if (row is SettingsRow.Custom && !unavailable) {
                        row.content()
                        return@itemsIndexed
                    }
                    if (row is SettingsRow.Header) {
                        Text(
                            stringResource(row.title),
                            style =
                                if (LocalDesktopLayout.current) DesktopType.sectionTitle()
                                else MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier =
                                Modifier.testTag("setting:${row.anchor}")
                                    .padding(
                                        start = 16.dp,
                                        top = if (reference != null) 12.dp else 16.dp,
                                        end = 16.dp,
                                        bottom = if (reference != null) 6.dp else 4.dp,
                                    ),
                        )
                        return@itemsIndexed
                    }
                    val visible by
                        remember(index) {
                            derivedStateOf {
                                listState.layoutInfo.visibleItemsInfo.any { it.index == leadingItemCount + index }
                            }
                        }
                    val rowInteraction = remember(row.anchor) { MutableInteractionSource() }
                    val indication = LocalIndication.current
                    val enabled = row.enabled && !unavailable
                    val groupStart = pageRows.take(index).indexOfLast { it is SettingsRow.Header } + 1
                    val groupEnd = pageRows.drop(index).indexOfFirst { it is SettingsRow.Header }.let { if (it < 0) pageRows.size else index + it }
                    SettingsPresentationEntry(
                        reference = entry.reference,
                        phone = reference != null,
                        index = index - groupStart,
                        count = groupEnd - groupStart,
                        enabled = enabled,
                        interactionSource = rowInteraction,
                        headlineContent = {
                            Text(
                                stringResource(
                                    (row as? SettingsRow.Text)?.titleFor?.invoke(preferences)
                                        ?: entry.reference?.title ?: row.title,
                                ),
                            )
                        },
                        supportingContent = {
                            // A switch with a state-dependent line (on or paused) shows that line
                            // instead of the static one.
                            val description =
                                (row as? SettingsRow.Toggle)?.let { toggle ->
                                    toggle.descriptionFor?.invoke(toggle.read(preferences))
                                } ?: row.description
                            if (unavailable) {
                                Text(stringResource(Res.string.phone_not_available_on_ios))
                            } else if (row is SettingsRow.RuntimeChoice) {
                                val current = preferences[row.key, row.default]
                                Text(row.options.firstOrNull { it.first == current }?.second ?: current)
                            } else if (row is SettingsRow.Status) {
                                Text(row.value, maxLines = if (row.fullText) Int.MAX_VALUE else 2)
                            } else if (row is SettingsRow.Choice) {
                                val stored = row.read(preferences)
                                val option =
                                    row.options.firstOrNull { it.value == stored }
                                        ?: row.options.firstOrNull { it.value == row.default }
                                        ?: row.options.first()
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
                                val option =
                                    row.options.firstOrNull { it.value == stored }
                                        ?: row.options.firstOrNull { it.value == row.default }
                                        ?: row.options.first()
                                Text(stringResource(option.label))
                            } else if (row is SettingsRow.Number) {
                                Text(numberValueText(row.read(preferences), row.unit))
                            } else if (row is SettingsRow.Decimal) {
                                Text(
                                    decimalValueText(
                                        preferences[row.key, row.default].let {
                                            if (it.isFinite()) it else row.default
                                        },
                                        row.unit,
                                    ),
                                )
                            }
                        },
                        trailingContent = {
                            if (row is SettingsRow.Unavailable && row.kind == PhoneSettingKind.TOGGLE) {
                                Switch(checked = false, onCheckedChange = null, enabled = false)
                            } else if (row is SettingsRow.Toggle)
                                Switch(
                                    checked = row.read(preferences),
                                    onCheckedChange = { checked ->
                                        commit { row.write(this, checked) }
                                    },
                                    enabled = enabled,
                                    modifier = Modifier.testTag("toggle:${row.anchor}"),
                                )
                        },
                        modifier =
                            Modifier.testTag("setting:${row.anchor}").clickable(
                                interactionSource = rowInteraction,
                                indication = if (reference != null) null else indication,
                                enabled =
                                    enabled && (row !is SettingsRow.Status || row.run != null),
                            ) {
                                when (row) {
                                    is SettingsRow.Toggle ->
                                        commit { row.write(this, !row.read(snapshot())) }
                                    is SettingsRow.Action -> row.run()
                                    is SettingsRow.Status -> row.run?.invoke()
                                    else -> editing = row.anchor
                                }
                            },
                    )
                }
                extraItems()
            }
            // A thin desktop scrollbar at the right edge of a settings list, as the reference
            // desktop build draws.
            // Touch hosts provide the no-op, so their list keeps the exact same scrolling without a
            // bar.
            scrollbar(
                listState,
                Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
    // Re-resolve the active row so a style/capability change cannot leave a stale enabled dialog.
    val row = pageEntries.firstOrNull { it.row.anchor == editing && it.row.enabled && !it.unavailable(unavailableAnchors) }?.row
    LaunchedEffect(editing, row != null) { if (editing != null && row == null) editing = null }
    if (row is SettingsRow.RuntimeChoice) {
        PhoneSettingsSelectionSheet(stringResource(row.title), row.options, preferences[row.key, row.default],
            onDismiss = { editing = null }, onSelect = { selected -> editing = null; commit { row.write(this, selected) } })
    }
    if (row is SettingsRow.Choice && reference != null) {
        val descriptions = row.options.associate { it.value to it.description?.let { label -> stringResource(label) } }
        PhoneSettingsSelectionSheet(stringResource(row.title), row.options.map { it.value to stringResource(it.label) }, row.read(preferences),
            onDismiss = { editing = null }, onSelect = { selected -> editing = null; commit { row.write(this, selected) } },
            enabled = { value -> row.options.firstOrNull { it.value == value }?.enabled == true },
            description = { value -> descriptions[value] })
    }
    if (row is SettingsRow.Choice && reference == null) {
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(row.title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    row.options.forEach { option ->
                        TextButton(
                            enabled = option.enabled,
                            onClick = {
                                commit { row.write(this, option.value) }
                                editing = null
                            },
                        ) {
                            Column {
                                Text(stringResource(option.label))
                                option.description?.let {
                                    Text(
                                        stringResource(it),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text(stringResource(Res.string.m6_cancel))
                }
            },
        )
    }
    if (row is SettingsRow.IntChoice && reference != null) {
        PhoneSettingsSelectionSheet(stringResource(row.title), row.options.map { it.value.toString() to stringResource(it.label) }, preferences[row.key, row.default].toString(),
            onDismiss = { editing = null }, onSelect = { selected -> editing = null; commit { row.write(this, selected.toInt()) } })
    }
    if (row is SettingsRow.IntChoice && reference == null) {
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(row.title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    row.options.forEach { option ->
                        TextButton(
                            onClick = {
                                commit { row.write(this, option.value) }
                                editing = null
                            },
                        ) {
                            Text(stringResource(option.label))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text(stringResource(Res.string.m6_cancel))
                }
            },
        )
    }
    if (row is SettingsRow.Text || row is SettingsRow.Number) {
        // The surrounding condition admits only text and number rows; the else branches keep these
        // expressions total.
        val stored =
            when (row) {
                is SettingsRow.Text -> preferences[row.key, row.default]
                is SettingsRow.Number -> row.read(preferences).toString()
                else -> ""
            }
        var draft by remember(row.anchor, stored) { mutableStateOf(stored) }
        val valid =
            when (row) {
                is SettingsRow.Text -> row.valid(draft)
                is SettingsRow.Number -> draft.toIntOrNull()?.let { it in row.range } == true
                else -> false
            }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(row.title)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = row !is SettingsRow.Text || row.singleLine,
                    visualTransformation =
                        if (row is SettingsRow.Text && row.masked) PasswordVisualTransformation()
                        else VisualTransformation.None,
                    modifier = Modifier.testTag("settings-draft"),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        when (row) {
                            is SettingsRow.Text -> commit { row.write(this, draft) }
                            is SettingsRow.Number -> commit { row.write(this, draft.toInt()) }
                            else -> Unit
                        }
                        editing = null
                    },
                ) {
                    Text(stringResource(Res.string.m6_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text(stringResource(Res.string.m6_cancel))
                }
            },
        )
    }
    if (row is SettingsRow.Decimal) {
        val stored =
            preferences[row.key, row.default].let { if (it.isFinite()) it else row.default }
        var draft by
            remember(row.anchor, stored) { mutableFloatStateOf(stored.coerceIn(row.range)) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(row.title)) },
            text = {
                Column {
                    Text(decimalValueText(draft, row.unit))
                    Slider(
                        value = draft,
                        onValueChange = { draft = row.normalize(it).coerceIn(row.range) },
                        valueRange = row.range,
                        steps = row.steps,
                        onValueChangeFinished = { commit { this[row.key] = draft } },
                        modifier = Modifier.testTag("settings-slider"),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { editing = null }) {
                    Text(stringResource(Res.string.m6_done))
                }
            },
        )
    }
}

@Composable
private fun numberValueText(value: Int, unit: SettingsUnit): String =
    when (unit) {
        SettingsUnit.NONE -> value.toString()
        SettingsUnit.SECONDS -> stringResource(Res.string.m6_duration_seconds_short, value)
        SettingsUnit.PERCENT -> "$value%"
    }

@Composable
private fun decimalValueText(value: Float, unit: SettingsUnit): String =
    when (unit) {
        SettingsUnit.NONE -> value.toString()
        SettingsUnit.SECONDS ->
            stringResource(Res.string.m6_duration_seconds_short, value.roundToInt())
        SettingsUnit.PERCENT -> "${(value * 100f).roundToInt()}%"
    }

@Composable
fun SettingsRestartDialog(
    controller: SettingsController,
    busy: Boolean,
    restartHost: suspend () -> Unit,
) {
    val state by controller.restart.collectAsState()
    val scope = rememberCoroutineScope()
    if (state.visible)
        AlertDialog(
            onDismissRequest = { scope.launch { controller.later() } },
            title = { Text(stringResource(Res.string.m6_restart_title)) },
            text = { Text(stringResource(Res.string.m6_restart_message)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch { controller.restartNow(busy, restartHost) }
                    },
                ) {
                    Text(stringResource(Res.string.m6_restart_now))
                }
            },
            dismissButton = {
                TextButton(onClick = { scope.launch { controller.later() } }) {
                    Text(stringResource(Res.string.m6_later))
                }
            },
        )
}

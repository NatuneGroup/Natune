/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import moe.rukamori.archivetune.ui.component.EditTextPreference
import moe.rukamori.archivetune.ui.component.ListPreference
import moe.rukamori.archivetune.ui.component.NumberEditTextPreference
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.PreferenceGroupTitle
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.component.preferenceGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.runtime.key
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.modules.ModuleSettingType
import moe.rukamori.archivetune.ui.player.EditorialMorphShape
import moe.rukamori.archivetune.viewmodels.*

internal object ModuleSettingsLayout {
    val ContentMaxWidth = 720.dp
    val CardGap = 8.dp
    val CardPadding = 16.dp
    val HeroPadding = 20.dp
    val AvatarSize = 44.dp
    val FloatingActionClearance = 96.dp
}

internal enum class ModuleTone { POSITIVE, NEUTRAL, ATTENTION, CRITICAL }

internal fun moduleTone(@StringRes status: Int, failed: Boolean = false): ModuleTone = when {
    failed -> ModuleTone.CRITICAL
    status == R.string.modules_ready || status == R.string.modules_resolved -> ModuleTone.POSITIVE
    status == R.string.modules_needs_configuration || status == R.string.modules_no_match -> ModuleTone.ATTENTION
    status == R.string.modules_failed || status == R.string.modules_unavailable || status == R.string.modules_timeout -> ModuleTone.CRITICAL
    else -> ModuleTone.NEUTRAL
}

@Composable
@ReadOnlyComposable
internal fun ModuleTone.accentColor(): Color = when (this) {
    ModuleTone.POSITIVE -> MaterialTheme.colorScheme.primary
    ModuleTone.ATTENTION -> MaterialTheme.colorScheme.tertiary
    ModuleTone.CRITICAL -> MaterialTheme.colorScheme.error
    ModuleTone.NEUTRAL -> MaterialTheme.colorScheme.outline
}

@Composable
@ReadOnlyComposable
private fun ModuleTone.containerColor(): Color = when (this) {
    ModuleTone.POSITIVE -> MaterialTheme.colorScheme.primaryContainer
    ModuleTone.ATTENTION -> MaterialTheme.colorScheme.tertiaryContainer
    ModuleTone.CRITICAL -> MaterialTheme.colorScheme.errorContainer
    ModuleTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHighest
}

@Composable
@ReadOnlyComposable
private fun ModuleTone.onContainerColor(): Color = when (this) {
    ModuleTone.POSITIVE -> MaterialTheme.colorScheme.onPrimaryContainer
    ModuleTone.ATTENTION -> MaterialTheme.colorScheme.onTertiaryContainer
    ModuleTone.CRITICAL -> MaterialTheme.colorScheme.onErrorContainer
    ModuleTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun LazyItemScope.moduleItemMotion(): Modifier {
    val motion = MaterialTheme.motionScheme
    return Modifier.animateItem(fadeInSpec = motion.defaultEffectsSpec(), placementSpec = motion.defaultSpatialSpec(),
        fadeOutSpec = motion.fastEffectsSpec())
}

@Composable
internal fun moduleStatusLabel(row: ModuleRowUi): String =
    if (row.playbackStatus) stringResource(R.string.modules_playback_status, stringResource(row.status)) else stringResource(row.status)

@Composable
internal fun ModuleStatusLine(@StringRes status: Int, label: String, modifier: Modifier = Modifier) {
    val color by animateColorAsState(moduleTone(status).accentColor(), MaterialTheme.motionScheme.defaultEffectsSpec(), label = "moduleStatus")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (status == R.string.modules_checking) LoadingIndicator(Modifier.fillMaxSize(), color = color)
            else Box(Modifier.size(8.dp).background(color, CircleShape))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ModuleCounter(value: Int, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            val direction = if (targetState > initialState) 1 else -1
            (slideInVertically(motion.defaultSpatialSpec()) { it * direction } + fadeIn(motion.defaultEffectsSpec())) togetherWith
                (slideOutVertically(motion.defaultSpatialSpec()) { -it * direction } + fadeOut(motion.fastEffectsSpec())) using
                SizeTransform(clip = true)
        },
        label = "moduleCounter",
    ) { Text("%d".format(it), style = style, color = color) }
}

@Composable
internal fun ModuleMorphBadge(active: Boolean, size: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val motion = MaterialTheme.motionScheme
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val progress by animateFloatAsState(if (active) 1f else 0f, motion.slowSpatialSpec(), label = "moduleBadgeMorph")
    val container by animateColorAsState(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        motion.defaultEffectsSpec(), label = "moduleBadgeContainer")
    val contentColor by animateColorAsState(if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        motion.defaultEffectsSpec(), label = "moduleBadgeContent")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().graphicsLayer { rotationZ = progress * 90f }
            .background(container, EditorialMorphShape(morph, progress.coerceIn(0f, 1f))))
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

@Composable
internal fun ModuleDetails(detail: ModuleDetailUi, model: ModuleSettingsUiModel, onAction: (ModuleSettingsAction) -> Unit) {
    val focus = LocalFocusManager.current
    val save = remember(focus, onAction) { { focus.clearFocus(); onAction(ModuleSettingsAction.Save) } }
    var informationExpanded by rememberSaveable(detail.row.id) { mutableStateOf(false) }
    val sourceTitle = stringResource(R.string.modules_playback_source)
    val qualityTitle = stringResource(R.string.modules_quality_title)
    val priorityTitle = stringResource(R.string.modules_priority_title)
    val informationTitle = stringResource(R.string.modules_metadata_information)
    val quality = detail.fields.firstOrNull { it.key == "quality" }
    val connection = detail.fields.firstOrNull { it.key == "mode" }
    val configuration = detail.fields.filter { it.key != quality?.key && it.key != connection?.key }
    val showSave = detail.fields.isNotEmpty() && detail.dirty
    val bottomPadding by animateDpAsState(SettingsDimensions.ScreenBottomPadding +
        if (showSave) ModuleSettingsLayout.FloatingActionClearance else 0.dp,
        MaterialTheme.motionScheme.defaultSpatialSpec(), label = "moduleDetailsBottomPadding")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = ModuleSettingsLayout.ContentMaxWidth).fillMaxWidth().fillMaxHeight().imePadding(),
            contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(ModuleSettingsLayout.CardGap),
        ) {
            item(key = "hero", contentType = "hero") { ModuleDetailHero(detail, model, onAction, moduleItemMotion()) }
            if (!model.enabled) item(key = "playback_off", contentType = "notice") {
                Surface(Modifier.fillMaxWidth().padding(horizontal = SettingsDimensions.ScreenHorizontalPadding),
                    shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.info), null, Modifier.size(20.dp))
                        Text(stringResource(R.string.modules_off), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (model.message != null || model.busy != null) item(key = "message", contentType = "message") {
                ModuleMessage(model, onAction, moduleItemMotion().padding(horizontal = SettingsDimensions.ScreenHorizontalPadding))
            }
            preferenceGroup(title = sourceTitle) {
                connection?.let { field -> item { ModuleField(field, model, onAction, Modifier) } }
                if (detail.supportsPool) {
                    item { ModuleOwnAccountPreference(detail, model, onAction) }
                    if (detail.poolInstalled) {
                        item { ModulePoolPreference(detail, model, onAction) }
                        item { ModuleAccountOrderPreference(detail, model, onAction) }
                    }
                } else if (connection == null) item {
                    PreferenceEntry(title = { Text(stringResource(R.string.modules_capabilities)) }, content = {
                        detail.capabilities.forEach { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                    })
                }
                configuration.forEach { field -> item { ModuleField(field, model, onAction, Modifier) } }
                if (detail.authentication) item {
                    PreferenceEntry(title = { Text(stringResource(R.string.modules_session_verify)) },
                        icon = { Icon(painterResource(R.drawable.login), null) },
                        onClick = { onAction(ModuleSettingsAction.Authorize(detail.row.id)) }, isEnabled = model.busy == null)
                }
            }
            quality?.let { field ->
                preferenceGroup(title = qualityTitle) { item { ModuleField(field, model, onAction, Modifier) } }
            }
            preferenceGroup(title = priorityTitle) { item { ModulePriorityPanel(detail, model, onAction, Modifier) } }
            if (detail.information.isNotEmpty() || detail.description.isNotBlank()) {
                item(key = "information_header", contentType = "heading") {
                    Row(Modifier.fillMaxWidth().clickable { informationExpanded = !informationExpanded }
                        .padding(horizontal = SettingsDimensions.ScreenHorizontalPadding, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        PreferenceGroupTitle(informationTitle, modifier = Modifier.weight(1f))
                        Icon(painterResource(if (informationExpanded) R.drawable.expand_less else R.drawable.expand_more),
                            stringResource(if (informationExpanded) R.string.modules_hide_details else R.string.modules_show_details))
                    }
                }
                if (informationExpanded) preferenceGroup {
                    if (detail.description.isNotBlank()) item {
                        PreferenceEntry(title = { Text(stringResource(R.string.modules_provider_description)) }, description = detail.description)
                    }
                    detail.information.forEach { information -> item { ModuleInformation(information, onAction) } }
                }
            }
            item(key = "diagnostics_header", contentType = "heading") { ModuleSectionHeading(R.string.modules_diagnostics) }
            item(key = "diagnostics", contentType = "diagnostics") {
                Box(Modifier.padding(horizontal = SettingsDimensions.ScreenHorizontalPadding)) { ModuleDiagnosticsPanel(detail, model, onAction) }
            }
            item(key = "maintenance_header", contentType = "heading") { ModuleSectionHeading(R.string.modules_updates) }
            item(key = "maintenance", contentType = "maintenance") {
                Box(Modifier.padding(horizontal = SettingsDimensions.ScreenHorizontalPadding)) { ModuleMaintenancePanel(detail, model, onAction) }
            }
        }
        ModuleSaveToolbar(visible = showSave, enabled = detail.fields.none { it.error != null } && model.busy == null, onSave = save)
    }
}

@Composable
private fun ModuleDetailHero(detail: ModuleDetailUi, model: ModuleSettingsUiModel, onAction: (ModuleSettingsAction) -> Unit, modifier: Modifier) {
    PreferenceGroup(modifier = modifier) {
        item {
            SwitchPreference(title = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(detail.row.name, style = MaterialTheme.typography.headlineSmallEmphasized)
                    Surface(shape = CircleShape, color = if (detail.row.enabled) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Text(stringResource(if (detail.row.enabled) R.string.modules_provider_enabled else R.string.modules_provider_disabled),
                            style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }, icon = {
                detail.row.icon?.let { ModuleProviderIcon(it, size = SettingsDimensions.SegmentedIconSize) }
                    ?: Icon(painterResource(R.drawable.extension), null)
            }, checked = detail.row.enabled, isEnabled = model.busy == null,
                onCheckedChange = { onAction(ModuleSettingsAction.Enable(detail.row.id, it)) })
        }
    }
}

@Composable
private fun ModuleOwnAccountPreference(detail: ModuleDetailUi, model: ModuleSettingsUiModel, onAction: (ModuleSettingsAction) -> Unit) {
    SwitchPreference(title = { Text(stringResource(R.string.modules_use_own_account)) },
        description = stringResource(if (detail.publicInstance) R.string.modules_public_instance_active else if (detail.ownAccountConfigured)
            R.string.modules_account_configured else R.string.modules_account_sign_in_required),
        checked = detail.accountSettings.useOwnAccount && !detail.publicInstance,
        isEnabled = model.busy == null && !detail.publicInstance,
        onCheckedChange = { onAction(ModuleSettingsAction.AccountSetting(detail.row.id,
            moe.rukamori.archivetune.shared.domain.settings.ModuleAccountToggle.USE_OWN_ACCOUNT, it)) })
}

@Composable
private fun ModulePoolPreference(detail: ModuleDetailUi, model: ModuleSettingsUiModel, onAction: (ModuleSettingsAction) -> Unit) {
    SwitchPreference(title = { Text(stringResource(R.string.modules_use_archivepool)) },
        description = stringResource(if (detail.poolReady) R.string.modules_pool_fallback_description else R.string.modules_pool_configuration_required),
        checked = detail.accountSettings.poolEnabled(detail.poolInstalled), isEnabled = model.busy == null,
        onCheckedChange = { onAction(ModuleSettingsAction.AccountSetting(detail.row.id,
            moe.rukamori.archivetune.shared.domain.settings.ModuleAccountToggle.USE_POOL, it)) })
}

@Composable
private fun ModuleAccountOrderPreference(detail: ModuleDetailUi, model: ModuleSettingsUiModel, onAction: (ModuleSettingsAction) -> Unit) {
    SwitchPreference(title = { Text(stringResource(R.string.modules_own_account_first)) },
        description = stringResource(R.string.modules_own_account_first_description), checked = detail.accountSettings.ownAccountFirst,
        isEnabled = model.busy == null && !detail.publicInstance && detail.accountSettings.useOwnAccount &&
            detail.accountSettings.poolEnabled(detail.poolInstalled),
        onCheckedChange = { onAction(ModuleSettingsAction.AccountSetting(detail.row.id,
            moe.rukamori.archivetune.shared.domain.settings.ModuleAccountToggle.OWN_ACCOUNT_FIRST, it)) })
}

@Composable
private fun ModulePriorityPanel(detail: ModuleDetailUi, model: ModuleSettingsUiModel, onAction: (ModuleSettingsAction) -> Unit, modifier: Modifier) {
    PreferenceEntry(modifier = modifier, title = { Text(stringResource(R.string.modules_position, detail.row.position, model.rows.size)) },
        description = stringResource(R.string.modules_priority_description), icon = {
            ModuleCounter(detail.row.position, MaterialTheme.typography.labelLarge)
        }, trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                FilledTonalIconButton(onClick = { onAction(ModuleSettingsAction.Move(detail.row.id, -1)) },
                    shapes = IconButtonDefaults.shapes(), enabled = model.busy == null && detail.row.position > 1) {
                    Icon(painterResource(R.drawable.arrow_upward), stringResource(R.string.modules_move_up))
                }
                FilledTonalIconButton(onClick = { onAction(ModuleSettingsAction.Move(detail.row.id, 1)) },
                    shapes = IconButtonDefaults.shapes(), enabled = model.busy == null && detail.row.position < model.rows.size) {
                    Icon(painterResource(R.drawable.arrow_downward), stringResource(R.string.modules_move_down))
                }
            }
        })
}

@Composable
internal fun ModuleSectionHeading(title: Int, description: Int? = null) {
    Column(Modifier.fillMaxWidth().padding(start = SettingsDimensions.SectionHeaderHorizontalPadding,
        end = SettingsDimensions.SectionHeaderHorizontalPadding, top = 20.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() })
        description?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun ModuleInformation(item: ModuleInfoUi, onAction: (ModuleSettingsAction) -> Unit, modifier: Modifier = Modifier) {
    PreferenceEntry(modifier = modifier, title = {
        Text(stringResource(if (item.label == R.string.modules_metadata_repository) R.string.modules_source_code else item.label))
    }, description = item.value, icon = if (item.link) { { Icon(painterResource(R.drawable.link), null) } } else null,
        trailingContent = if (item.link) { { Icon(painterResource(R.drawable.open_in_new), null) } } else null,
        onClick = if (item.link) { { onAction(ModuleSettingsAction.OpenLink(item.value)) } } else null)
}

@Composable
private fun ModuleDiagnosticsPanel(detail: ModuleDetailUi, model: ModuleSettingsUiModel, onAction: (ModuleSettingsAction) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.largeIncreased, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(ModuleSettingsLayout.CardPadding).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ModuleDiagnosticTile(R.string.modules_initialization, detail.initialization)
            ModuleDiagnosticTile(R.string.modules_last_playback, detail.playback)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (detail.authentication) FilledTonalButton(
                    onClick = remember(detail.row.id, onAction) { { onAction(ModuleSettingsAction.Authorize(detail.row.id)) } },
                    shapes = ButtonDefaults.shapes(), enabled = model.busy == null && !detail.dirty) {
                    Icon(painterResource(R.drawable.login), null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.modules_session_verify))
                }
                FilledTonalButton(onClick = remember(detail.row.id, onAction) { { onAction(ModuleSettingsAction.Check(detail.row.id)) } },
                    shapes = ButtonDefaults.shapes(), enabled = model.busy == null) {
                    Icon(painterResource(R.drawable.check), null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.modules_check))
                }
                OutlinedButton(onClick = remember(onAction) { { onAction(ModuleSettingsAction.CopyDiagnostics) } }, shapes = ButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.copy), null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.modules_diagnostics_copy))
                }
            }
        }
    }
}

@Composable
private fun ModuleDiagnosticTile(title: Int, diagnostic: ModuleDiagnosticUi?) {
    val motion = MaterialTheme.motionScheme
    val status = diagnostic?.status ?: R.string.modules_not_checked
    val tone = moduleTone(status, diagnostic?.failure != null)
    val container by animateColorAsState(tone.containerColor(), motion.defaultEffectsSpec(), label = "diagnosticContainer")
    val content by animateColorAsState(tone.onContainerColor(), motion.defaultEffectsSpec(), label = "diagnosticContent")
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(40.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(when (tone) {
                ModuleTone.POSITIVE -> R.drawable.check
                ModuleTone.CRITICAL -> R.drawable.error
                else -> R.drawable.info
            }), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(status), style = MaterialTheme.typography.titleMediumEmphasized,
                color = if (tone == ModuleTone.CRITICAL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (diagnostic != null) {
                Text(stringResource(R.string.modules_diagnostic_time, diagnostic.time, diagnostic.elapsedMs), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (diagnostic.status == R.string.modules_ready) Text(stringResource(R.string.modules_ready_description), style = MaterialTheme.typography.bodySmall)
                diagnostic.phase?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                diagnostic.candidateCount?.let { Text(stringResource(R.string.modules_candidates_checked, it), style = MaterialTheme.typography.bodySmall) }
                diagnostic.rejection?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                diagnostic.failure?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (diagnostic.upstream.isNotBlank()) Text(stringResource(R.string.modules_upstream, diagnostic.upstream), style = MaterialTheme.typography.bodyMedium)
                if (diagnostic.variant.isNotBlank()) Text(stringResource(R.string.modules_variant, diagnostic.variant), style = MaterialTheme.typography.bodyMedium)
                if (diagnostic.code.isNotBlank()) Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Text(diagnostic.code, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun ModuleMaintenancePanel(detail: ModuleDetailUi, model: ModuleSettingsUiModel, onAction: (ModuleSettingsAction) -> Unit) {
    val interactive = model.busy == null
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.largeIncreased, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(ModuleSettingsLayout.CardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (detail.row.canUpdate) FilledTonalButton(onClick = remember(detail.row.id, onAction) { { onAction(ModuleSettingsAction.Update(detail.row.id)) } },
                shapes = ButtonDefaults.shapes(), enabled = interactive, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.update), null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.modules_check_update))
            } else Text(stringResource(R.string.modules_updates_file), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = remember(detail.row.id, onAction) { { onAction(ModuleSettingsAction.Remove(detail.row.id, false)) } },
                shapes = ButtonDefaults.shapes(), enabled = interactive, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, if (interactive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))) {
                Icon(painterResource(R.drawable.delete), null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.modules_remove))
            }
        }
    }
}

@Composable
private fun BoxScope.ModuleSaveToolbar(visible: Boolean, enabled: Boolean, onSave: () -> Unit) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter).imePadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        enter = slideInVertically(motion.defaultSpatialSpec()) { it } + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.85f) +
            fadeIn(motion.defaultEffectsSpec()),
        exit = slideOutVertically(motion.fastSpatialSpec()) { it } + scaleOut(motion.fastSpatialSpec(), targetScale = 0.85f) +
            fadeOut(motion.fastEffectsSpec()),
    ) {
        val colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors()
        Surface(shape = CircleShape, color = colors.toolbarContainerColor, contentColor = colors.toolbarContentColor,
            shadowElevation = FloatingToolbarDefaults.ContainerExpandedElevationWithFab) {
            Row(Modifier.heightIn(min = FloatingToolbarDefaults.ContainerSize).padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.modules_unsaved_changes), style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Button(onClick = onSave, shapes = ButtonDefaults.shapes(), enabled = enabled) {
                    Icon(painterResource(R.drawable.check), null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

@Composable
private fun ModuleField(field: ModuleFieldUi, model: ModuleSettingsUiModel, onAction: (ModuleSettingsAction) -> Unit, modifier: Modifier) {
    key(field.key) {
        val change = remember(field.key, onAction) { { value: String -> onAction(ModuleSettingsAction.Field(field.key, value)) } }
        val interactive = model.busy == null
        val supporting = listOfNotNull(field.description.takeIf(String::isNotBlank), field.error?.let { stringResource(it) }).joinToString("\n")
        when (field.type) {
            ModuleSettingType.BOOLEAN -> SwitchPreference(modifier = modifier, title = { Text(field.label) },
                description = supporting.takeIf(String::isNotBlank), checked = field.checked,
                onCheckedChange = { change(it.toString()) }, isEnabled = interactive)
            ModuleSettingType.SELECT -> ListPreference(modifier = modifier, title = { Text(field.label) },
                description = supporting.takeIf(String::isNotBlank), selectedValue = field.json,
                values = field.options.map { it.json },
                valueText = { value -> field.options.firstOrNull { it.json == value }?.label ?: field.selectedLabel },
                valueDescription = { value -> field.options.firstOrNull { it.json == value }?.description.orEmpty() },
                onValueSelected = change, isEnabled = interactive)
            ModuleSettingType.NUMBER -> NumberEditTextPreference(modifier = modifier, title = { Text(field.label) },
                value = field.text, onValueChange = { change(it.trim().toBigDecimalOrNull()?.stripTrailingZeros()?.toString() ?: "null") }, integerOnly = field.integerOnly,
                description = listOf(field.text, supporting).filter(String::isNotBlank).joinToString("\n"),
                isInputValid = { input ->
                    if (input.isBlank()) !field.required else input.trim().toBigDecimalOrNull()?.let { decimal ->
                        val value = decimal.toDouble()
                        value.isFinite() && (!field.integerOnly || decimal.remainder(java.math.BigDecimal.ONE).signum() == 0) &&
                            (field.minimum == null || value >= field.minimum) && (field.maximum == null || value <= field.maximum)
                    } == true
                }, isEnabled = interactive)
            ModuleSettingType.TEXT, ModuleSettingType.PASSWORD -> EditTextPreference(modifier = modifier, title = { Text(field.label) },
                value = field.text, onValueChange = change, masked = field.sensitive,
                description = listOf(if (field.sensitive && field.text.isNotEmpty()) "••••••••" else field.text, supporting)
                    .filter(String::isNotBlank).joinToString("\n"),
                keyboardOptions = KeyboardOptions(keyboardType = when {
                    field.sensitive -> KeyboardType.Password
                    field.key.contains("url", ignoreCase = true) -> KeyboardType.Uri
                    else -> KeyboardType.Text
                }), isInputValid = { !field.required || it.isNotBlank() }, isEnabled = interactive)
            ModuleSettingType.UNSUPPORTED -> PreferenceEntry(modifier = modifier, title = { Text(field.label) },
                description = stringResource(R.string.modules_setting_unsupported), isEnabled = false)
        }
    }
}
